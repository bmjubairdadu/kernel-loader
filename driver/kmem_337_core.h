/* SPDX-License-Identifier: GPL-2.0 */
/*
 * kmem_337_core.h - Shared engine for RT/QX kernel memory drivers
 *
 * Optimized high-performance kernel memory access engine for ARM64:
 * - Stack buffer (1024B) for small I/O (eliminates slab churn)
 * - Direct ARM64 linear mapping + fallback to get_user_pages_remote
 * - Automatic ZRAM swap-in and demand paging support
 * - Safe Copy-On-Write (COW) write support for game code patching
 * - Hardware D-cache clean and I-cache invalidate (instant code modification)
 * - Ultra-fast O(N) modbase search via dentry->d_name with substring matching
 * - Full 32-bit (ARMv7) and 64-bit (ARM64) compat ioctl support
 */
#ifndef _KMEM_337_CORE_H
#define _KMEM_337_CORE_H

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
#include <linux/slab.h>
#include <linux/highmem.h>
#include <asm/pgtable.h>

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 8, 0)
#include <linux/mmap_lock.h>
#define KMEM_MMAP_READ_LOCK(mm) down_read(&(mm)->mmap_lock)
#define KMEM_MMAP_READ_UNLOCK(mm) up_read(&(mm)->mmap_lock)
#else
#define KMEM_MMAP_READ_LOCK(mm) down_read(&(mm)->mmap_sem)
#define KMEM_MMAP_READ_UNLOCK(mm) up_read(&(mm)->mmap_sem)
#endif

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 11, 0)
#define KMEM_KMAP(pg) kmap_local_page(pg)
#define KMEM_KUNMAP(addr) kunmap_local(addr)
#elif defined(CONFIG_ARM64)
/* ARM64 has all RAM in linear mapping (PAGE_OFFSET), directly addressable */
#define KMEM_KMAP(pg) ((void *)page_address(pg))
#define KMEM_KUNMAP(addr) do { } while (0)
#else
#define KMEM_KMAP(pg) kmap_atomic(pg)
#define KMEM_KUNMAP(addr) kunmap_atomic(addr)
#endif

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
#define KMEM_CLASS_CREATE(name) class_create(name)
#else
#define KMEM_CLASS_CREATE(name) class_create(THIS_MODULE, name)
#endif

#define KMEM_CHUNK_RW 32768
#define KMEM_STACK_RW 1024
#define KMEM_BATCH_U32_MAX 32
#define KMEM_NAME_MAX 255
#define KMEM_MODBASE_BUFLEN 256
#define KMEM_BIG_RW 4096

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

static inline void kmem_flush_cache(void *addr, size_t size)
{
#if defined(CONFIG_ARM64)
	uintptr_t start = (uintptr_t)addr;
	uintptr_t end = start + size;
	uintptr_t p;

	for (p = start & ~63UL; p < end; p += 64) {
		asm volatile("dc cvau, %0" : : "r" (p) : "memory");
	}
	asm volatile("dsb ish" : : : "memory");
	for (p = start & ~63UL; p < end; p += 64) {
		asm volatile("ic ivau, %0" : : "r" (p) : "memory");
	}
	asm volatile("dsb ish\nisb" : : : "memory");
#endif
}

static size_t kmem_copy_user_page(struct task_struct *task, struct mm_struct *mm,
				  uintptr_t va, char *kbuf, size_t sz, int is_write)
{
	pgd_t *pgd;
	pud_t *pud;
	pmd_t *pmd;
	pte_t *pte;
	spinlock_t *ptl;
	struct page *page;
	void *mapped;
	size_t off;

	pgd = pgd_offset(mm, va);
	if (pgd_none(*pgd) || !pgd_present(*pgd))
		goto fallback_gup;

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 0, 0)
	{
		p4d_t *p4d = p4d_offset(pgd, va);
		if (p4d_none(*p4d) || !p4d_present(*p4d))
			goto fallback_gup;
		pud = pud_offset(p4d, va);
	}
#else
	pud = pud_offset(pgd, va);
#endif
	if (pud_none(*pud) || !pud_present(*pud))
		goto fallback_gup;

	pmd = pmd_offset(pud, va);
	if (pmd_none(*pmd) || !pmd_present(*pmd))
		goto fallback_gup;

	if (pmd_trans_huge(*pmd)) {
		phys_addr_t pa = pmd_page_paddr(*pmd) + (va & ~PMD_MASK);

		if (!pfn_valid(pa >> PAGE_SHIFT))
			goto fallback_gup;
		page = pfn_to_page(pa >> PAGE_SHIFT);
		if (!get_page_unless_zero(page))
			goto fallback_gup;
		mapped = KMEM_KMAP(page);
		off = pa & ~PAGE_MASK;
		if (is_write) {
			memcpy((char *)mapped + off, kbuf, sz);
			set_page_dirty_lock(page);
			kmem_flush_cache((char *)mapped + off, sz);
		} else {
			memcpy(kbuf, (char *)mapped + off, sz);
		}
		KMEM_KUNMAP(mapped);
		put_page(page);
		return sz;
	}

	pte = pte_offset_map_lock(mm, pmd, va, &ptl);
	if (!pte_present(*pte) || (is_write && !pte_write(*pte))) {
		pte_unmap_unlock(pte, ptl);
		goto fallback_gup;
	}
	page = pte_page(*pte);
	if (!get_page_unless_zero(page)) {
		pte_unmap_unlock(pte, ptl);
		goto fallback_gup;
	}
	pte_unmap_unlock(pte, ptl);
	off = va & (PAGE_SIZE - 1);
	mapped = KMEM_KMAP(page);
	if (is_write) {
		memcpy((char *)mapped + off, kbuf, sz);
		set_page_dirty_lock(page);
		kmem_flush_cache((char *)mapped + off, sz);
	} else {
		memcpy(kbuf, (char *)mapped + off, sz);
	}
	KMEM_KUNMAP(mapped);
	put_page(page);
	return sz;

fallback_gup:
	if (task) {
		struct page *gpage = NULL;
		unsigned int gup_flags = FOLL_FORCE | (is_write ? FOLL_WRITE : 0);
		long gup_rc;

		gup_rc = get_user_pages_remote(task, mm, va & PAGE_MASK, 1,
					       gup_flags, &gpage, NULL);
		if (gup_rc == 1 && gpage) {
			off = va & (PAGE_SIZE - 1);
			mapped = KMEM_KMAP(gpage);
			if (is_write) {
				memcpy((char *)mapped + off, kbuf, sz);
				set_page_dirty_lock(gpage);
				kmem_flush_cache((char *)mapped + off, sz);
			} else {
				memcpy(kbuf, (char *)mapped + off, sz);
			}
			KMEM_KUNMAP(mapped);
			put_page(gpage);
			return sz;
		}
	}
	return 0;
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
	if (!mm) {
		put_task_struct(task);
		return -EIO;
	}

	if (size <= sizeof(stack)) {
		kbuf = stack;
		bsz = sizeof(stack);
	} else {
		bsz = size <= KMEM_CHUNK_RW ? size : KMEM_CHUNK_RW;
		kbuf = kmalloc(bsz, GFP_KERNEL);
		if (!kbuf) {
			mmput(mm);
			put_task_struct(task);
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
		}

		KMEM_MMAP_READ_LOCK(mm);
		while (left > 0) {
			size_t chunk = PAGE_SIZE - (cur & (PAGE_SIZE - 1));
			char *part;
			size_t copied;

			if (chunk > left)
				chunk = left;
			part = kbuf + (step - left);
			copied = kmem_copy_user_page(task, mm, cur, part, chunk, is_write);
			if (copied == chunk) {
				ok = 1;
			} else {
				if (!is_write)
					memset(part + copied, 0, chunk - copied);
			}
			cur += chunk;
			left -= chunk;
		}
		KMEM_MMAP_READ_UNLOCK(mm);

		if (!is_write) {
			if (copy_to_user(dst, kbuf, step))
				break;
		}
		done += step;

		if (big && done < size)
			cond_resched();
	}

	if (dynamic)
		kfree(kbuf);
	mmput(mm);
	put_task_struct(task);
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

static int kmem_batch_u32_locked(struct task_struct *task, struct mm_struct *mm,
				 const __u64 *addr, __u32 *out, __u32 count)
{
	__u32 i;
	int all_ok = 1;

	for (i = 0; i < count; i++) {
		uintptr_t va = (uintptr_t)addr[i];
		__u32 val = 0;

		if (!addr[i]) {
			out[i] = 0;
			all_ok = 0;
			continue;
		}

		if (kmem_copy_user_page(task, mm, va, (char *)&val, sizeof(val), 0) == sizeof(val)) {
			out[i] = val;
		} else {
			out[i] = 0;
			all_ok = 0;
		}
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
	if (!mm) {
		put_task_struct(task);
		return -ESRCH;
	}

	KMEM_MMAP_READ_LOCK(mm);
	rc = kmem_batch_u32_locked(task, mm, b->addr, b->out, b->count);
	KMEM_MMAP_READ_UNLOCK(mm);
	mmput(mm);
	put_task_struct(task);
	return rc;
}

static uintptr_t kmem_get_modbase(pid_t pid, const char *name)
{
	struct task_struct *task;
	struct mm_struct *mm;
	struct vm_area_struct *vma;
	size_t nlen;
	uintptr_t found = 0;
	const char *base = name;
	const char *p;

	if (!name || !*name)
		return 0;
	nlen = strnlen(name, KMEM_NAME_MAX + 1);
	if (nlen == 0 || nlen > KMEM_NAME_MAX)
		return 0;
	for (p = name; *p; p++) {
		if (*p == '/')
			base = p + 1;
	}
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
		if (vma->vm_file && vma->vm_file->f_path.dentry) {
			const unsigned char *dname = vma->vm_file->f_path.dentry->d_name.name;
			if (dname) {
				if (!strcmp((const char *)dname, base) ||
				    strstr((const char *)dname, base)) {
					found = vma->vm_start;
					break;
				}
			}
		}
	}
	KMEM_MMAP_READ_UNLOCK(mm);

	mmput(mm);
	return found;
}

#endif /* _KMEM_337_CORE_H */
