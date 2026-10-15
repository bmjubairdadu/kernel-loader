/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _DMSYNC_CORE_H
#define _DMSYNC_CORE_H

/* Safe core: physical-alias R/W (page-table walk + ioremap_cache).
 * No get_user_pages, no FOLL_FORCE, no PTE dirty, no cache flush,
 * no mmap_lock, no rate limiter - same approach as the proven-safe
 * reference drivers, so read/write leaves no fault/COW/pagemap trace.
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
#include <asm/pgtable.h>
#include <asm/io.h>

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
#define KMEM_CLASS_CREATE(name) class_create(name)
#else
#define KMEM_CLASS_CREATE(name) class_create(THIS_MODULE, name)
#endif

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

/* VA -> PA via the target's own page tables. No locks, no faults,
 * PTEs are only read, never modified. Returns 0 when unmapped. */
static uintptr_t kmem_translate(struct mm_struct *mm, uintptr_t va)
{
	pgd_t *pgd;
	pud_t *pud;
	pmd_t *pmd;
	pte_t *pte;

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
		return ((uintptr_t)pmd_pfn(*pmd) << PAGE_SHIFT) |
			(va & ~PMD_MASK);
	pte = pte_offset_kernel(pmd, va);
	if (!pte || !pte_present(*pte))
		return 0;
	return ((uintptr_t)pte_pfn(*pte) << PAGE_SHIFT) | (va & ~PAGE_MASK);
}

static size_t kmem_read_phys(uintptr_t pa, void __user *ubuf, size_t sz)
{
	void __iomem *v;
	size_t off = pa & ~PAGE_MASK;
	size_t rc = 0;

	if (!pfn_valid(pa >> PAGE_SHIFT))
		return 0;
	v = ioremap_cache(pa & PAGE_MASK, PAGE_SIZE);
	if (!v)
		return 0;
	if (!copy_to_user(ubuf, (void *)v + off, sz))
		rc = sz;
	iounmap(v);
	return rc;
}

static size_t kmem_write_phys(uintptr_t pa, void __user *ubuf, size_t sz)
{
	void __iomem *v;
	size_t off = pa & ~PAGE_MASK;
	size_t rc = 0;

	if (!pfn_valid(pa >> PAGE_SHIFT))
		return 0;
	v = ioremap_cache(pa & PAGE_MASK, PAGE_SIZE);
	if (!v)
		return 0;
	if (!copy_from_user((void *)v + off, ubuf, sz))
		rc = sz;
	iounmap(v);
	return rc;
}

static int kmem_rw_process(pid_t pid, uintptr_t addr, void __user *ubuf,
			   size_t size, int is_write)
{
	struct task_struct *task;
	struct mm_struct *mm;
	size_t done = 0;
	size_t moved = 0;

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

	while (done < size) {
		uintptr_t cur = addr + done;
		size_t chunk = PAGE_SIZE - (cur & (PAGE_SIZE - 1));
		uintptr_t pa;

		if (chunk > size - done)
			chunk = size - done;
		pa = kmem_translate(mm, cur);
		if (pa) {
			size_t got = is_write ?
				kmem_write_phys(pa, (char __user *)ubuf + done,
						chunk) :
				kmem_read_phys(pa, (char __user *)ubuf + done,
					      chunk);
			if (got == chunk)
				moved += chunk;
		}
		done += chunk;
		cond_resched();
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

static uintptr_t kmem_get_modbase(pid_t pid, const char *name)
{
	struct task_struct *task;
	struct mm_struct *mm;
	struct vm_area_struct *vma;
	const char *base = name;
	const char *p;
	char buf[256];
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

	for (vma = mm->mmap; vma; vma = vma->vm_next) {
		if (vma->vm_file) {
			char *path = file_path(vma->vm_file, buf, sizeof(buf));
			char *b;

			if (IS_ERR(path))
				continue;
			b = strrchr(path, '/');
			b = b ? b + 1 : path;
			if (!strcmp(b, base)) {
				found = vma->vm_start;
				break;
			}
		}
	}
	mmput(mm);
	return found;
}

#endif
