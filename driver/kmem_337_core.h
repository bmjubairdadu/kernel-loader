/* SPDX-License-Identifier: GPL-2.0 */
/*
 * kmem_337_core.h - Shared engine for RT/QX kernel memory drivers
 *
 * Fast path for 4.9 arm64: kernel bounce per ioctl (chunked past 32K),
 * stack buffer for small I/O (no slab alloc), mmap read lock held across
 * the page walk, batched u32 reads. Observable behaviour matches the 3.0
 * driver exactly: partial transfers succeed, any size works, failures
 * report -EIO like before.
 */
#ifndef _KMEM_337_CORE_H
#define _KMEM_337_CORE_H

#include <linux/version.h>
#include <linux/types.h>
#include <linux/string.h>
#include <linux/rwsem.h>
#include <linux/mm.h>
#include <linux/sched.h>
#include <linux/pid.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/highmem.h>
#include <asm/pgtable.h>

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 8, 0)
#include <linux/mmap_lock.h>
#define KMEM_MMAP_READ_LOCK(mm) mmap_read_lock(mm)
#define KMEM_MMAP_READ_UNLOCK(mm) mmap_read_unlock(mm)
#else
#define KMEM_MMAP_READ_LOCK(mm) down_read(&(mm)->mmap_sem)
#define KMEM_MMAP_READ_UNLOCK(mm) up_read(&(mm)->mmap_sem)
#endif

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
#define KMEM_CLASS_CREATE(name) class_create(name)
#else
#define KMEM_CLASS_CREATE(name) class_create(THIS_MODULE, name)
#endif

#define KMEM_CHUNK_RW 32768
#define KMEM_STACK_RW 256
#define KMEM_BATCH_U32_MAX 32
#define KMEM_NAME_MAX 63
#define KMEM_MODBASE_BUFLEN 256
#define KMEM_BIG_RW 8192

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

typedef struct {
	pid_t		pid;
	__u32		count;
	__u64		addr[KMEM_BATCH_U32_MAX];
	__u32		out[KMEM_BATCH_U32_MAX];
} kmem_batch_u32_t;

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

static phys_addr_t kmem_translate_va(struct mm_struct *mm, uintptr_t va)
{
	pgd_t *pgd;
	pud_t *pud;
	pmd_t *pmd;
	pte_t *pte;
	phys_addr_t pa;

	pgd = pgd_offset(mm, va);
	if (pgd_none(*pgd) || !pgd_present(*pgd))
		return 0;

	pud = pud_offset(pgd, va);
	if (pud_none(*pud) || !pud_present(*pud))
		return 0;

	pmd = pmd_offset(pud, va);
	if (pmd_none(*pmd) || !pmd_present(*pmd))
		return 0;

	if (pmd_trans_huge(*pmd))
		return pmd_page_paddr(*pmd) + (va & ~PMD_MASK);

	pte = pte_offset_kernel(pmd, va);
	if (!pte || !pte_present(*pte))
		return 0;

	pa = (phys_addr_t)pte_pfn(*pte) << PAGE_SHIFT;
	return pa | (va & ~PAGE_MASK);
}

static size_t kmem_copy_phys(phys_addr_t pa, char *kbuf, size_t sz,
			     int is_write)
{
	struct page *page;
	void *mapped;

	if (!pfn_valid(pa >> PAGE_SHIFT))
		return 0;

	page = pfn_to_page(pa >> PAGE_SHIFT);
	mapped = kmap_atomic(page);
	if (is_write)
		memcpy((char *)mapped + (pa & ~PAGE_MASK), kbuf, sz);
	else
		memcpy(kbuf, (char *)mapped + (pa & ~PAGE_MASK), sz);
	kunmap_atomic(mapped);
	return sz;
}

static int kmem_rw_locked(struct mm_struct *mm, uintptr_t addr, char *kbuf,
			  size_t size, int is_write)
{
	size_t done = 0;
	int big = (size > KMEM_BIG_RW);

	while (done < size) {
		uintptr_t cur = addr + done;
		size_t chunk = PAGE_SIZE - (cur & (PAGE_SIZE - 1));
		phys_addr_t pa;

		if (chunk > size - done)
			chunk = size - done;
		pa = kmem_translate_va(mm, cur);
		if (!pa)
			break;
		if (!kmem_copy_phys(pa, kbuf + done, chunk, is_write))
			break;
		done += chunk;
		if (big)
			cond_resched();
	}
	return (done == size) ? 0 : -EIO;
}

static int kmem_rw_process(pid_t pid, uintptr_t addr, void __user *ubuf,
			   size_t size, int is_write)
{
	struct task_struct *task;
	struct mm_struct *mm;
	char stack[KMEM_STACK_RW];
	char *kbuf;
	size_t bsz;
	size_t done = 0;
	int dynamic = 0;
	int ok = 0;

	if (!size || !ubuf)
		return -EIO;
	if (pid <= 0)
		return -EIO;

	task = kmem_get_task(pid);
	if (!task)
		return -EIO;
	mm = get_task_mm(task);
	put_task_struct(task);
	if (!mm)
		return -EIO;

	if (size <= sizeof(stack)) {
		kbuf = stack;
		bsz = sizeof(stack);
	} else {
		bsz = size <= KMEM_CHUNK_RW ? size : KMEM_CHUNK_RW;
		kbuf = kmalloc(bsz, GFP_KERNEL);
		if (!kbuf) {
			mmput(mm);
			return -EIO;
		}
		dynamic = 1;
	}

	while (done < size) {
		size_t step = size - done;
		size_t left = step;
		uintptr_t cur = addr + done;
		char __user *dst = (char __user *)ubuf + done;
		int big = (size > KMEM_BIG_RW);

		if (step > bsz)
			step = bsz;
		if (is_write) {
			if (copy_from_user(kbuf, dst, step))
				break;
		} else {
			memset(kbuf, 0, step);
		}
		KMEM_MMAP_READ_LOCK(mm);
		while (left > 0) {
			size_t chunk = PAGE_SIZE - (cur & (PAGE_SIZE - 1));
			phys_addr_t pa;
			char *part;

			if (chunk > left)
				chunk = left;
			part = kbuf + (step - left);
			pa = kmem_translate_va(mm, cur);
			if (pa && kmem_copy_phys(pa, part, chunk,
						 is_write) == chunk)
				ok = 1;
			cur += chunk;
			left -= chunk;
			if (big)
				cond_resched();
		}
		KMEM_MMAP_READ_UNLOCK(mm);
		if (!is_write) {
			if (copy_to_user(dst, kbuf, step))
				break;
		}
		done += step;
	}

	if (dynamic)
		kfree(kbuf);
	mmput(mm);
	return ok ? 0 : -EIO;
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

static int kmem_batch_u32_locked(struct mm_struct *mm, const __u64 *addr,
				 __u32 *out, __u32 count)
{
	__u32 i;
	int all_ok = 1;

	for (i = 0; i < count; i++) {
		uintptr_t va = (uintptr_t)addr[i];
		__u32 val = 0;
		phys_addr_t pa;

		if (!addr[i]) {
			out[i] = 0;
			all_ok = 0;
			continue;
		}
		if ((va & (PAGE_SIZE - 1)) <= PAGE_SIZE - sizeof(val)) {
			pa = kmem_translate_va(mm, va);
			if (!pa ||
			    !kmem_copy_phys(pa, (char *)&val, sizeof(val), 0)) {
				out[i] = 0;
				all_ok = 0;
				continue;
			}
			out[i] = val;
			continue;
		}
		if (kmem_rw_locked(mm, va, (char *)&val, sizeof(val), 0)) {
			out[i] = 0;
			all_ok = 0;
			continue;
		}
		out[i] = val;
	}
	return all_ok ? 0 : -EIO;
}

static int kmem_batch_read_u32(kmem_batch_u32_t *b)
{
	struct task_struct *task;
	struct mm_struct *mm;
	int rc;

	if (!b || b->count == 0 || b->count > KMEM_BATCH_U32_MAX)
		return -EINVAL;
	if (b->pid <= 0)
		return -ESRCH;

	task = kmem_get_task(b->pid);
	if (!task)
		return -ESRCH;
	mm = get_task_mm(task);
	put_task_struct(task);
	if (!mm)
		return -ESRCH;

	KMEM_MMAP_READ_LOCK(mm);
	rc = kmem_batch_u32_locked(mm, b->addr, b->out, b->count);
	KMEM_MMAP_READ_UNLOCK(mm);
	mmput(mm);
	return rc;
}

static uintptr_t kmem_get_modbase(pid_t pid, const char *name)
{
	struct task_struct *task;
	struct mm_struct *mm;
	struct vm_area_struct *vma;
	char buf[KMEM_MODBASE_BUFLEN];
	char *base;
	char *path;
	size_t nlen;
	uintptr_t found = 0;

	if (!name || !*name)
		return 0;
	nlen = strnlen(name, KMEM_NAME_MAX + 1);
	if (nlen == 0 || nlen > KMEM_NAME_MAX)
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

	KMEM_MMAP_READ_LOCK(mm);
	for (vma = mm->mmap; vma; vma = vma->vm_next) {
		if (!vma->vm_file)
			continue;

		buf[0] = '\0';
		path = d_path(&vma->vm_file->f_path, buf, sizeof(buf));
		if (IS_ERR(path))
			continue;

		base = strrchr(path, '/');
		base = base ? base + 1 : path;

		if (!strcmp(base, name)) {
			found = vma->vm_start;
			break;
		}
	}
	KMEM_MMAP_READ_UNLOCK(mm);

	mmput(mm);
	return found;
}

#endif /* _KMEM_337_CORE_H */
