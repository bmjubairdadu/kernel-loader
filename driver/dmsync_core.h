/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _DMSYNC_CORE_H
#define _DMSYNC_CORE_H

/*
 * dmsync core - external process memory access.
 *
 * Design notes (3.3.0)
 * --------------------
 *
 * 1. No ioremap_cache()/iounmap() in the hot path.
 *
 *    The previous implementation created and destroyed a vmalloc mapping for
 *    every single 4 KiB page it touched.  iounmap() ends in a kernel TLB
 *    invalidate, and on arm64 that is a broadcast operation: every other CPU
 *    - including the game's render, physics and audio threads - takes an IPI.
 *    An ESP reads a few hundred struct fields per frame, i.e. thousands of
 *    pages per second, so the phone was being peppered with IPIs and TLB
 *    shootdowns while the game was trying to hold 60 fps.  That is exactly
 *    what "hitching" and input lag look like from the outside.
 *
 *    The target page is ordinary RAM (we already require pfn_valid()), so it
 *    is permanently mapped in the kernel linear map.  __va() gives a Normal
 *    write-back cacheable alias with the same attributes as the target's user
 *    mapping, which is coherent by construction (same physical page, same
 *    attributes, no VIPT aliasing on arm64).  A plain copy through that alias
 *    is correct, allocation-free, and does not touch the TLB at all.
 *
 * 2. Page-table walks run in an RCU-sched read-side section.
 *
 *    Walking another task's page tables without protection is a latent
 *    use-after-free: if the target munmaps (or a THP collapses) while we are
 *    between the pmd and pte dereference, the table page can be recycled and
 *    pte_offset_kernel() yields a wild pointer -> oops -> reboot.
 *    arm64 selects HAVE_RCU_TABLE_FREE, so page-table pages are released with
 *    call_rcu_sched() after a grace period; rcu_read_lock_sched() (a preempt
 *    disable, no contention, no IPI) is enough to keep them alive.
 *
 *    We deliberately do not take mmap_sem.  It is not needed for correctness
 *    here, and taking it around a copy_to_user() would be a deadlock waiting
 *    to happen: rwsem is fair, so if the target queues a writer while we hold
 *    the read lock, the page fault taken by our own copy would block behind
 *    that writer and the writer would block behind us.
 *
 * 3. Physically contiguous pages are merged into a single copy.
 *
 *    Consecutive pages of a .so or of a heap arena are usually physically
 *    contiguous, so a 256 KiB window is one memcpy instead of 64 of them.
 *    That mostly speeds up module scans / bulk reads; the per-frame ESP reads
 *    are single-page and therefore unaffected.
 *
 * 4. Kernel-space addresses are rejected.
 *
 *    A client working from a stale offset list can produce a pointer in the
 *    kernel half.  kmem_translate() would happily resolve it (the top half is
 *    shared with init_mm) and a stray write would corrupt the kernel direct
 *    map.  Refusing anything >= TASK_SIZE_64 turns "instant reboot" into a
 *    clean -EIO.
 */

#include <linux/version.h>
#include <linux/types.h>
#include <linux/string.h>
#include <linux/rwsem.h>
#include <linux/mm.h>
#include <linux/sched.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 11, 0)
#include <linux/sched/mm.h>
#include <linux/sched/task.h>
#endif
#include <linux/pid.h>
#include <linux/uaccess.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/kobject.h>
#include <linux/fs.h>
#include <linux/dcache.h>
#include <linux/random.h>
#include <linux/rcupdate.h>
#include <asm/pgtable.h>
#include <asm/memory.h>
#include <asm/io.h>
#include <asm/processor.h>

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
#define KMEM_CLASS_CREATE(name) class_create(name)
#else
#define KMEM_CLASS_CREATE(name) class_create(THIS_MODULE, name)
#endif

/* User-space limit.  TASK_SIZE is per-thread (a 32-bit caller would get 4 GiB
 * and could not ask us to read a 64-bit target above it), so use the raw
 * 64-bit limit when the arch provides it. */
#ifdef TASK_SIZE_64
#define KMEM_USER_LIMIT	TASK_SIZE_64
#else
#define KMEM_USER_LIMIT	TASK_SIZE
#endif

/* Hard cap per ioctl.  Keeps a bogus size from turning into a multi-second
 * uninterruptible loop.  16 MiB is orders of magnitude above anything an
 * ESP/modbase client asks for. */
#define KMEM_MAX_XFER		(16u * 1024u * 1024u)
/* Longest physically contiguous run handed to a single copy. */
#define KMEM_MAX_RUN		(256u * 1024u)
/* Give the scheduler a chance after this many mapping runs. */
#define KMEM_MAX_RUNS		64

static int kmem_hidden;
static struct list_head *kmem_hide_prev;

/* Hide from lsmod AND /sys/module (reference drivers do both on load). */
static void kmem_hide_module(struct module *mod)
{
	if (kmem_hidden)
		return;
	mutex_lock(&module_mutex);
	kmem_hide_prev = mod->list.prev;
	list_del(&mod->list);
	mutex_unlock(&module_mutex);
	kobject_del(&mod->mkobj.kobj);
	kmem_hidden = 1;
}

#define KMEM_NAME_MAX 255
/* d_path() needs room for the full executable path; the reference driver
 * uses 1024.  A 256-byte buffer makes d_path() fail with -ENAMETOOLONG for
 * deeply nested Android app paths, so modbase would silently return 0. */
#define KMEM_MODBASE_BUFLEN 1024

/*
 * Fresh-node discovery.
 *
 * A client (the Aincrad Mods / gpdeku overlay, the "Driver Loader V3"
 * engines) finds the driver by scanning /dev for a character device that
 * still looks brand new: root:root, size 0 and, crucially,
 * atime == ctime.  Every stock node in /dev carries the boot-time
 * timestamp, so "atime == ctime" is what marks the driver's own node.
 *
 * The loader app, however, runs "chmod 666" and "chcon" on the node it
 * loaded.  Both of those bump ctime, so the node stops looking fresh and
 * the client never picks it up - it reports "Disconnected" and no
 * read/write ever happens, even though the driver works perfectly.
 *
 * The reference drivers avoid this by creating their node with a random
 * name that the loader never touches.  We do the same: the requested
 * node keeps the app's flow working, and a second node with a random
 * name stays untouched so the scanner can still find us.
 *
 * The random name uses the same alphabet and length as the reference
 * drivers ("BYNBBYNBBYNBBYNBABCDEFG", 23 chars), one get_random_bytes()
 * draw per character, exactly like the reference implementation.
 */
#define KMEM_RAND_POOL		"BYNBBYNBBYNBBYNBABCDEFG"
#define KMEM_RAND_POOL_LEN	23
#define KMEM_RAND_NAME_LEN	23

static void kmem_rand_name(char *out, int len)
{
	unsigned int r;
	int i;

	for (i = 0; i < len; i++) {
		get_random_bytes(&r, sizeof(r));
		out[i] = KMEM_RAND_POOL[r % KMEM_RAND_POOL_LEN];
	}
	out[len] = '\0';
}

/* The second ("fresh") device node - see the comment above. */
struct kmem_alt_node {
	dev_t		devt;
	struct cdev	cdev;
	struct class	*cls;
	struct device	*devp;
	char		name[KMEM_RAND_NAME_LEN + 1];
};

static char *kmem_alt_devnode(struct device *dev, umode_t *mode)
{
	if (mode)
		*mode = 0666;
	return NULL;
}

/* Force a node's permissions by writing i_mode directly, keep atime == ctime
 * and make the inode immune to atime updates.
 *
 * Everything here is deliberately NOT done through the VFS: a chmod() or a
 * chcon() goes through the inode_setattr path and bumps the inode's ctime,
 * which is exactly the timestamp a /dev scanner uses to decide "this node was
 * just created by a driver".  Once the loader app chmod's the node, the node
 * stops looking fresh, the client never picks it up and it reports
 * "Disconnected" - even though the driver itself works perfectly.
 *
 * Writing i_mode / i_atime in place leaves ctime alone, so the node stays
 * discoverable.  i_atime is re-synced to i_ctime on every pass, which repairs
 * a node the loader app already touched.
 *
 * S_NOATIME is the part that makes it stick: without it the first open()
 * after the repair window would bump atime through touch_atime() (relatime),
 * the node would stop looking fresh, and a client that rescans /dev later
 * (engine restart after a crash, game relaunch) would report "Disconnected"
 * again.  With S_NOATIME set, atime is frozen for the lifetime of the node. */
static int kmem_force_node_mode(const char *name, umode_t mode)
{
	struct path path;
	struct dentry *dentry;
	struct inode *inode;
	char full[64];
	int ret = -ENOENT;

	snprintf(full, sizeof(full), "/dev/%s", name);

	if (kern_path(full, LOOKUP_FOLLOW, &path))
		return ret;
	dentry = path.dentry;
	inode = d_inode(dentry);
	if (!inode)
		goto out;
	inode_lock(inode);
	inode->i_mode = (inode->i_mode & ~S_IALLUGO) | mode;
	inode->i_flags |= S_NOATIME;
	inode->i_atime = inode->i_ctime;
	inode->i_atime.tv_nsec = inode->i_ctime.tv_nsec;
	inode_unlock(inode);
	ret = 0;
out:
	path_put(&path);
	return ret;
}

static int kmem_create_alt_node(struct kmem_alt_node *alt,
				const struct file_operations *fops)
{
	int ret;

	memset(alt, 0, sizeof(*alt));
	kmem_rand_name(alt->name, KMEM_RAND_NAME_LEN);

	ret = alloc_chrdev_region(&alt->devt, 0, 1, alt->name);
	if (ret)
		return ret;

	cdev_init(&alt->cdev, fops);
	alt->cdev.owner = THIS_MODULE;
	ret = cdev_add(&alt->cdev, alt->devt, 1);
	if (ret)
		goto err_region;

	alt->cls = KMEM_CLASS_CREATE(alt->name);
	if (IS_ERR(alt->cls)) {
		ret = PTR_ERR(alt->cls);
		goto err_cdev;
	}
	alt->cls->devnode = (typeof(alt->cls->devnode))kmem_alt_devnode;

	alt->devp = device_create(alt->cls, NULL, alt->devt, NULL, alt->name);
	if (IS_ERR(alt->devp)) {
		ret = PTR_ERR(alt->devp);
		goto err_class;
	}
	/* 0666 so any app can open it.  Done by writing i_mode directly so
	 * the node keeps atime == ctime and stays discoverable. */
	kmem_force_node_mode(alt->name, 0666);
	return 0;

err_class:
	class_destroy(alt->cls);
err_cdev:
	cdev_del(&alt->cdev);
err_region:
	unregister_chrdev_region(alt->devt, 1);
	return ret;
}

static void kmem_destroy_alt_node(struct kmem_alt_node *alt)
{
	if (!alt->cls)
		return;
	device_destroy(alt->cls, alt->devt);
	class_destroy(alt->cls);
	cdev_del(&alt->cdev);
	unregister_chrdev_region(alt->devt, 1);
	alt->cls = NULL;
}

typedef struct {
	pid_t		pid;
	uintptr_t	addr;
	void		*buffer;
	size_t		size;
} copy_memoby_t;

typedef struct {
	pid_t		pid;
	char		*name;
	uintptr_t	base;
} module_base_t;

static inline struct task_struct *kmem_get_task(pid_t pid)
{
	struct task_struct *task;
	rcu_read_lock();
	task = pid_task(find_vpid(pid), PIDTYPE_PID);
	if (task)
		get_task_struct(task);
	rcu_read_unlock();
	return task;
}

/* VA -> PA through the target's page tables.  PTEs are only read.
 * Returns 0 when the page is not mapped.
 *
 * The walk is RCU-sched protected (see the header comment): page-table pages
 * on arm64 are freed with call_rcu_sched(), so a preempt-disabled section
 * keeps every table we dereference alive.  Nothing in here sleeps. */
static uintptr_t kmem_translate(struct mm_struct *mm, uintptr_t va)
{
	pgd_t *pgd;
	pud_t *pud;
	pmd_t *pmd;
	pte_t *pte;
	uintptr_t pa = 0;

	rcu_read_lock_sched();

	pgd = pgd_offset(mm, va);
	if (pgd_none(*pgd) || !pgd_present(*pgd))
		goto out;

	pud = pud_offset(pgd, va);
	if (pud_none(*pud) || !pud_present(*pud))
		goto out;

	pmd = pmd_offset(pud, va);
	if (pmd_none(*pmd) || !pmd_present(*pmd))
		goto out;

	if (pmd_trans_huge(*pmd)) {	/* 2 MiB block / THP */
		pa = ((uintptr_t)pmd_pfn(*pmd) << PAGE_SHIFT) |
			(va & ~PMD_MASK);
		goto out;
	}

	pte = pte_offset_kernel(pmd, va);
	if (!pte || !pte_present(*pte))
		goto out;

	pa = ((uintptr_t)pte_pfn(*pte) << PAGE_SHIFT) | (va & ~PAGE_MASK);
out:
	rcu_read_unlock_sched();
	return pa;
}

/* Read or write one physically contiguous run through the linear map.
 * ubuf is the user buffer for this run.  Returns the number of bytes moved. */
static size_t kmem_copy_run(uintptr_t pa, void __user *ubuf, size_t sz,
			    int is_write)
{
	void *kaddr;

	if (!pfn_valid(pa >> PAGE_SHIFT))
		return 0;
	if (!pfn_valid((pa + sz - 1) >> PAGE_SHIFT))
		return 0;

	kaddr = __va(pa);
	/* __va() is a permanent, cacheable alias of ordinary RAM: no fault,
	 * no TLB work, no cache maintenance.  Only the user side can fault. */
	if (is_write) {
		if (copy_from_user(kaddr, ubuf, sz))
			return 0;
	} else {
		if (copy_to_user(ubuf, kaddr, sz))
			return 0;
	}
	return sz;
}

static int kmem_rw_process(pid_t pid, uintptr_t addr, void __user *ubuf,
			   size_t size, int is_write)
{
	struct task_struct *task;
	struct mm_struct *mm;
	size_t done = 0;
	size_t moved = 0;
	unsigned int runs = 0;
	uintptr_t next_va = 0;
	uintptr_t next_pa = 0;
	int have_next = 0;

	if (!size || !ubuf)
		return -EIO;
	if (pid <= 0)
		return -EIO;
	if (size > KMEM_MAX_XFER)
		return -EIO;
	/* never resolve (and therefore never write) kernel space */
	if (addr >= KMEM_USER_LIMIT || size > KMEM_USER_LIMIT - addr)
		return -EIO;

	task = kmem_get_task(pid);
	if (!task)
		return -EIO;
	mm = get_task_mm(task);
	put_task_struct(task);
	if (!mm)
		return -EIO;

	while (done < size) {
		uintptr_t cur = addr + done;
		size_t chunk = PAGE_SIZE - (cur & ~PAGE_MASK);
		uintptr_t pa;
		size_t run;

		if (chunk > size - done)
			chunk = size - done;

		/* Reuse the translation the merge probe already did, so a
		 * non-contiguous run costs one walk per page, not two. */
		if (have_next && next_va == cur) {
			pa = next_pa;
		} else {
			pa = kmem_translate(mm, cur);
		}
		have_next = 0;
		run = chunk;

		if (pa) {
			/* Merge physically contiguous follow-up pages. */
			while (run < size - done && run < KMEM_MAX_RUN) {
				uintptr_t ncur = cur + run;
				uintptr_t npa;
				size_t nchunk;

				if (ncur & ~PAGE_MASK)
					break;
				nchunk = PAGE_SIZE - (ncur & ~PAGE_MASK);
				if (nchunk > size - done - run)
					nchunk = size - done - run;
				npa = kmem_translate(mm, ncur);
				if (!npa || !pfn_valid(npa >> PAGE_SHIFT))
					break;
				if (npa != pa + run) {
					/* not contiguous - keep it for the
					 * next iteration instead of walking
					 * this page again */
					have_next = 1;
					next_va = ncur;
					next_pa = npa;
					break;
				}
				run += nchunk;
			}
			moved += kmem_copy_run(pa, (char __user *)ubuf + done,
					       run, is_write);
		}

		done += run;
		if (++runs >= KMEM_MAX_RUNS) {
			runs = 0;
			cond_resched();
		}
	}

	mmput(mm);
	return moved ? 0 : -EIO;
}

static inline int kmem_read_process(pid_t pid, uintptr_t addr,
				    void __user *buf, size_t size)
{
	return kmem_rw_process(pid, addr, buf, size, 0);
}

static inline int kmem_write_process(pid_t pid, uintptr_t addr,
				     void __user *buf, size_t size)
{
	return kmem_rw_process(pid, addr, buf, size, 1);
}

/* Base address of the first VMA backed by a file called @name.
 *
 * The VMA list itself is NOT RCU protected (VMAs are kmem_cache_free()d
 * immediately), so the walk needs mmap_sem.  Nothing sleeps inside the loop
 * except d_path()-style string work on a caller-supplied kernel buffer, which
 * is why taking the read lock here is safe. */
static uintptr_t kmem_get_modbase(pid_t pid, const char *name)
{
	struct task_struct *task;
	struct mm_struct *mm;
	struct vm_area_struct *vma;
	const char *base = name;
	const char *p;
	char buf[KMEM_MODBASE_BUFLEN];
	uintptr_t found = 0;

	if (!name || !*name)
		return 0;
	for (p = name; *p; p++) {
		if (*p == '/')
			base = p + 1;
	}
	if (!*base || strnlen(base, KMEM_NAME_MAX + 1) > KMEM_NAME_MAX)
		return 0;
	if (pid <= 0)
		return 0;

	task = kmem_get_task(pid);
	if (!task)
		return 0;
	mm = get_task_mm(task);
	put_task_struct(task);
	if (!mm)
		return 0;

	down_read(&mm->mmap_sem);
	for (vma = mm->mmap; vma; vma = vma->vm_next) {
		char *path;
		char *b;

		if (!vma->vm_file)
			continue;
		path = file_path(vma->vm_file, buf, sizeof(buf));
		if (IS_ERR(path))
			continue;
		b = strrchr(path, '/');
		b = b ? b + 1 : path;
		if (!strcmp(b, base)) {
			found = vma->vm_start;
			break;
		}
	}
	up_read(&mm->mmap_sem);

	mmput(mm);
	return found;
}

#endif
