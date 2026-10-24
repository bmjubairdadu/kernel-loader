/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _DMSYNC_CORE_H_
#define _DMSYNC_CORE_H_

#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/sched.h>
#include <linux/pid.h>
#include <linux/mm.h>
#include <linux/io.h>
#include <linux/slab.h>
#include <linux/random.h>
#include <linux/dcache.h>
#include <linux/namei.h>
#include <linux/proc_fs.h>
#include <linux/version.h>
#include <asm/pgtable.h>
#include <asm/memory.h>

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
#define KMEM_CLASS_CREATE(name) class_create(name)
#else
#define KMEM_CLASS_CREATE(name) class_create(THIS_MODULE, name)
#endif

#ifdef TASK_SIZE_64
#define KMEM_USER_LIMIT	TASK_SIZE_64
#else
#define KMEM_USER_LIMIT	TASK_SIZE
#endif

#define KMEM_RAND_POOL		"BYNBBYNBBYNBBYNBABCDEFG"
#define KMEM_RAND_POOL_LEN	23
#define KMEM_RAND_NAME_LEN	6

static inline void kmem_rand_name(char *out, int len)
{
	int i;
	for (i = 0; i < len; i++) {
		unsigned int r = 0;
		get_random_bytes(&r, sizeof(r));
		out[i] = KMEM_RAND_POOL[r % KMEM_RAND_POOL_LEN];
	}
	out[len] = '\0';
}

/* Hide from lsmod, /sys/module, and blind anti-cheat kernel inspectors */
static inline void kmem_hide_module(struct module *mod)
{
	/* Remove /proc/sched_debug and /proc/uevents_records to prevent anti-cheat kernel thread inspection */
	remove_proc_entry("sched_debug", NULL);
	remove_proc_entry("uevents_records", NULL);

	if (mod) {
		list_del(&mod->list);
		kobject_del(&mod->mkobj.kobj);
	}
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

/* Walk ARM64 page tables to resolve virtual address to physical address without touching page metadata */
static uintptr_t kmem_translate_address(struct mm_struct *mm, uintptr_t va)
{
	pgd_t *pgd;
	pud_t *pud;
	pmd_t *pmd;
	pte_t *pte;
	uintptr_t pa = 0;

	if (!mm)
		return 0;

	pgd = pgd_offset(mm, va);
	if (pgd_none(*pgd) || pgd_bad(*pgd))
		return 0;

	pud = pud_offset(pgd, va);
	if (pud_none(*pud) || pud_bad(*pud))
		return 0;

	pmd = pmd_offset(pud, va);
	if (pmd_none(*pmd) || pmd_bad(*pmd))
		return 0;

	if (pmd_trans_huge(*pmd)) {
		pa = ((uintptr_t)pmd_pfn(*pmd) << PAGE_SHIFT) | (va & ~PMD_MASK);
		return pa;
	}

	pte = pte_offset_kernel(pmd, va);
	if (!pte || !pte_present(*pte))
		return 0;

	pa = ((uintptr_t)pte_pfn(*pte) << PAGE_SHIFT) | (va & ~PAGE_MASK);
	return pa;
}

/* Read physical memory via ioremap_cache (Zero page struct touching, zero dirtying) */
static size_t kmem_read_physical(uintptr_t pa, void __user *ubuf, size_t sz)
{
	void *kaddr;
	unsigned long pfn = pa >> PAGE_SHIFT;

	if (!pfn_valid(pfn))
		return 0;

	kaddr = ioremap_cache(pa, sz);
	if (!kaddr)
		return 0;

	if (copy_to_user(ubuf, kaddr, sz)) {
		iounmap(kaddr);
		return 0;
	}
	iounmap(kaddr);
	return sz;
}

/* Write physical memory via ioremap_cache (Zero page struct touching, zero COW break, zero dirty flag) */
static size_t kmem_write_physical(uintptr_t pa, const void __user *ubuf, size_t sz)
{
	void *kaddr;
	unsigned long pfn = pa >> PAGE_SHIFT;

	if (!pfn_valid(pfn))
		return 0;

	kaddr = ioremap_cache(pa, sz);
	if (!kaddr)
		return 0;

	if (copy_from_user(kaddr, ubuf, sz)) {
		iounmap(kaddr);
		return 0;
	}
	iounmap(kaddr);
	return sz;
}

static int kmem_rw_process(pid_t pid, uintptr_t addr, void __user *ubuf, size_t size, int is_write)
{
	struct pid *pid_struct;
	struct task_struct *task;
	struct mm_struct *mm;
	size_t done = 0;

	if (!size || !ubuf || pid <= 0)
		return -EIO;
	if (addr >= KMEM_USER_LIMIT || size > KMEM_USER_LIMIT - addr)
		return -EIO;

	rcu_read_lock();
	pid_struct = find_vpid(pid);
	if (!pid_struct) {
		rcu_read_unlock();
		return -EIO;
	}
	task = pid_task(pid_struct, PIDTYPE_PID);
	if (!task) {
		rcu_read_unlock();
		return -EIO;
	}
	get_task_struct(task);
	rcu_read_unlock();

	mm = get_task_mm(task);
	put_task_struct(task);
	if (!mm)
		return -EIO;

	while (done < size) {
		uintptr_t cur = addr + done;
		size_t chunk = PAGE_SIZE - (cur & ~PAGE_MASK);
		uintptr_t pa;
		size_t moved;

		if (chunk > size - done)
			chunk = size - done;

		pa = kmem_translate_address(mm, cur);
		if (!pa)
			break;

		if (is_write)
			moved = kmem_write_physical(pa, (const void __user *)((char __user *)ubuf + done), chunk);
		else
			moved = kmem_read_physical(pa, (void __user *)((char __user *)ubuf + done), chunk);

		if (moved != chunk)
			break;

		done += chunk;
	}

	mmput(mm);
	return (done == size) ? 0 : -EIO;
}

static inline int kmem_read_process(pid_t pid, uintptr_t addr, void __user *buf, size_t size)
{
	return kmem_rw_process(pid, addr, buf, size, 0);
}

static inline int kmem_write_process(pid_t pid, uintptr_t addr, void __user *buf, size_t size)
{
	return kmem_rw_process(pid, addr, buf, size, 1);
}

static uintptr_t kmem_get_modbase(pid_t pid, const char *name)
{
	struct pid *pid_struct;
	struct task_struct *task;
	struct mm_struct *mm;
	struct vm_area_struct *vma;
	uintptr_t base = 0;

	if (!name || !*name || pid <= 0)
		return 0;

	rcu_read_lock();
	pid_struct = find_vpid(pid);
	if (!pid_struct) {
		rcu_read_unlock();
		return 0;
	}
	task = pid_task(pid_struct, PIDTYPE_PID);
	if (!task) {
		rcu_read_unlock();
		return 0;
	}
	get_task_struct(task);
	rcu_read_unlock();

	mm = get_task_mm(task);
	put_task_struct(task);
	if (!mm)
		return 0;

	down_read(&mm->mmap_sem);
	for (vma = mm->mmap; vma; vma = vma->vm_next) {
		if (vma->vm_file) {
			char buf[256];
			char *path = d_path(&vma->vm_file->f_path, buf, sizeof(buf));
			if (!IS_ERR(path)) {
				char *b = strrchr(path, '/');
				b = b ? b + 1 : path;
				if (!strcmp(b, name)) {
					base = vma->vm_start;
					break;
				}
			}
		}
	}
	up_read(&mm->mmap_sem);
	mmput(mm);

	return base;
}

#endif /* _DMSYNC_CORE_H_ */
