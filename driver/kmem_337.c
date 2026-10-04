#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/mm.h>
#include <linux/sched.h>
#include <linux/pid.h>
#include <linux/io.h>
#include <linux/slab.h>
#include <linux/highmem.h>
#include <linux/random.h>
#include <asm/pgtable.h>

#undef noinline	

typedef struct _COPY_MEMOBY {
	pid_t		pid;
	uintptr_t	addr;
	void		*buffer;
	size_t		size;
} COPY_MEMOBY;

typedef struct _MODULE_BASE {
	pid_t		pid;
	char		*name;
	uintptr_t	base;
} MODULE_BASE;

static dev_t mem_tool_dev_t;
static struct class *mem_tool_class;
static struct device *mem_tool_device;
static struct cdev char_dev;

char *devicename = "wanbai";
module_param(devicename, charp, 0644);
MODULE_PARM_DESC(devicename, "device node name (clients look for /dev/wanbai)");

static char *devname = "wanbai";
module_param(devname, charp, 0644);
MODULE_PARM_DESC(devname, "alias of devicename");

#define CMD_PROC_READ	0x801
#define CMD_PROC_WRITE	0x802
#define CMD_MOD_BASE	0x803

__attribute__((noinline)) char *get_rand_str(void)
{
	static const char tbl[] =
		"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	static char string[16];
	char *str = string;
	int i, lstr, flag;
	unsigned int seed;

	get_random_bytes(&seed, sizeof(seed));
	flag = (int)(seed % 4);
	lstr = 6 + (int)(seed % 4) + flag;
	if (lstr > 15)
		lstr = 15;

	for (i = 0; i < lstr; i++) {
		get_random_bytes(&seed, sizeof(seed));
		str[i] = tbl[seed % (sizeof(tbl) - 1)];
	}
	str[lstr] = '\0';

	return str;
}

__attribute__((noinline)) phys_addr_t translate_linear_address(struct mm_struct *mm, uintptr_t va)
{
	pgd_t *pgd;
	pud_t *pud;
	pmd_t *pmd;
	pte_t *pte;
	uintptr_t page_offset;
	phys_addr_t page_addr;

	if (!mm)
		return 0;

	page_offset = va & ~PAGE_MASK;

	pgd = pgd_offset(mm, va);
	if (!pgd_present(*pgd) || pgd_none(*pgd))
		return 0;

	pud = pud_offset(pgd, va);
	if (!pud_present(*pud) || pud_none(*pud))
		return 0;

	pmd = pmd_offset(pud, va);
	if (!pmd_present(*pmd) || pmd_none(*pmd))
		return 0;

	if (pmd_trans_huge(*pmd)) {
		page_addr = pmd_page_paddr(*pmd) + (va & ~PMD_MASK);
		return page_addr;
	}

	pte = pte_offset_kernel(pmd, va);
	if (!pte || !pte_present(*pte))
		return 0;

	page_addr = (phys_addr_t)pte_pfn(*pte) << PAGE_SHIFT;
	return page_addr | (va & ~PAGE_MASK);
}

__attribute__((noinline)) size_t read_physical_address(phys_addr_t pa, void *buffer, size_t size)
{
	struct page *page;
	void *mapped;
	void *bounce;

	if (!pfn_valid(pa >> PAGE_SHIFT))
		return 0;

	bounce = kmalloc(size, GFP_KERNEL);
	if (!bounce)
		return 0;

	page = pfn_to_page(pa >> PAGE_SHIFT);
	mapped = kmap_atomic(page);
	memcpy(bounce, (char *)mapped + (pa & ~PAGE_MASK), size);
	kunmap_atomic(mapped);

	if (__arch_copy_to_user((void __user *)buffer, bounce, size)) {
		kfree(bounce);
		return 0;
	}
	kfree(bounce);

	return size;
}

__attribute__((noinline)) size_t write_physical_address(phys_addr_t pa, void *buffer, size_t size)
{
	struct page *page;
	void *mapped;
	void *bounce;

	if (!pfn_valid(pa >> PAGE_SHIFT))
		return 0;

	bounce = kmalloc(size, GFP_KERNEL);
	if (!bounce)
		return 0;

	if (__arch_copy_from_user(bounce, (void __user *)buffer, size)) {
		kfree(bounce);
		return 0;
	}

	page = pfn_to_page(pa >> PAGE_SHIFT);
	mapped = kmap_atomic(page);
	memcpy((char *)mapped + (pa & ~PAGE_MASK), bounce, size);
	kunmap_atomic(mapped);
	kfree(bounce);

	return size;
}

__attribute__((noinline)) bool read_process_memory(pid_t pid, uintptr_t addr, void *buffer, size_t size)
{
	struct task_struct *task;
	struct mm_struct *mm;
	phys_addr_t pa;
	size_t count = size, max;
	int ok = 0;

	if (!size || !buffer)
		return false;

	rcu_read_lock();
	task = pid_task(find_vpid(pid), PIDTYPE_PID);
	rcu_read_unlock();
	if (!task)
		return false;

	mm = get_task_mm(task);
	if (!mm)
		return false;

	while (count > 0) {
		max = 0x1000 - (addr & 0xfff);
		if (count < max)
			max = count;

		pa = translate_linear_address(mm, addr);
		if (pa && read_physical_address(pa, buffer, max) == max)
			ok = 1;

		addr += max;
		buffer = (void *)((char *)buffer + max);
		count -= max;
		cond_resched();
	}

	mmput(mm);
	return ok ? true : false;
}

__attribute__((noinline)) bool write_process_memory(pid_t pid, uintptr_t addr, void *buffer, size_t size)
{
	struct task_struct *task;
	struct mm_struct *mm;
	phys_addr_t pa;
	size_t count = size, max;
	int ok = 0;

	if (!size || !buffer)
		return false;

	rcu_read_lock();
	task = pid_task(find_vpid(pid), PIDTYPE_PID);
	rcu_read_unlock();
	if (!task)
		return false;

	mm = get_task_mm(task);
	if (!mm)
		return false;

	while (count > 0) {
		max = 0x1000 - (addr & 0xfff);
		if (count < max)
			max = count;

		pa = translate_linear_address(mm, addr);
		if (pa && write_physical_address(pa, buffer, max) == max)
			ok = 1;

		addr += max;
		buffer = (void *)((char *)buffer + max);
		count -= max;
		cond_resched();
	}

	mmput(mm);
	return ok ? true : false;
}

static size_t get_module_base(pid_t pid, char *name)
{
	struct task_struct *task;
	struct mm_struct *mm;
	struct vm_area_struct *vma;
	char buf[1024];
	char *path_nm;
	size_t count;

	if (!name)
		return 0;

	rcu_read_lock();
	task = pid_task(find_vpid(pid), PIDTYPE_PID);
	rcu_read_unlock();
	if (!task)
		return 0;

	mm = get_task_mm(task);
	if (!mm)
		return 0;

	count = 0;
	for (vma = mm->mmap; vma; vma = vma->vm_next) {
		if (!vma->vm_file)
			continue;

		buf[0] = '\0';
		path_nm = d_path(&vma->vm_file->f_path, buf, sizeof(buf));
		if (IS_ERR(path_nm))
			continue;

		path_nm = strrchr(path_nm, '/');
		if (!path_nm)
			path_nm = buf;
		else
			path_nm++;

		if (!strcmp(path_nm, name)) {
			count = vma->vm_start;
			break;
		}
	}

	mmput(mm);
	return count;
}

__attribute__((noinline)) int dispatch_open(struct inode *node, struct file *file)
{
	return 0;
}

__attribute__((noinline)) int dispatch_close(struct inode *node, struct file *file)
{
	return 0;
}

__attribute__((noinline)) long dispatch_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	COPY_MEMOBY cm;
	MODULE_BASE mb;
	char name[256];

	switch (cmd) {
	case CMD_PROC_READ: {
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -14;
		
		if (read_process_memory(cm.pid, cm.addr, cm.buffer, cm.size))
			return 0;
		return -5;
	}
	case CMD_PROC_WRITE: {
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -14;
		if (write_process_memory(cm.pid, cm.addr, cm.buffer, cm.size))
			return 0;
		return -5;
	}
	case CMD_MOD_BASE: {
		if (copy_from_user(&mb, (void __user *)arg, sizeof(mb)))
			return -14;
		if (copy_from_user(name, (void __user *)mb.name, 255))
			return -14;
		name[255] = '\0';
		mb.base = get_module_base(mb.pid, name);
		if (copy_to_user((void __user *)arg, &mb, sizeof(mb)))
			return -14;
		return 0;
	}
	default:
		return -22;
	}
}

struct file_operations dispatch_functions = {
	.owner		= THIS_MODULE,
	.open		= dispatch_open,
	.release	= dispatch_close,
	.unlocked_ioctl	= dispatch_ioctl,
#ifdef CONFIG_COMPAT
	.compat_ioctl	= dispatch_ioctl,
#endif
	.llseek		= noop_llseek,
};

static char *kmem_node(struct device *dev, umode_t *mode)
{
	if (mode)
		*mode = 0666;
	return NULL;
}

static int __init driver_entry(void)
{
	int ret;
	const char *node;

	node = devicename;
	if (!node || !*node)
		node = NULL;
	if (devname && *devname && strcmp(devname, "wanbai"))
		node = devname;
	if (!node)
		node = get_rand_str();

	ret = alloc_chrdev_region(&mem_tool_dev_t, 0, 1, node);
	if (ret)
		return ret;

	cdev_init(&char_dev, &dispatch_functions);
	char_dev.owner = THIS_MODULE;
	ret = cdev_add(&char_dev, mem_tool_dev_t, 1);
	if (ret)
		goto err_region;

	mem_tool_class = class_create(THIS_MODULE, node);
	if (IS_ERR(mem_tool_class)) {
		ret = PTR_ERR(mem_tool_class);
		goto err_cdev;
	}
	mem_tool_class->devnode = kmem_node;

	mem_tool_device = device_create(mem_tool_class, NULL, mem_tool_dev_t,
					NULL, node);
	if (IS_ERR(mem_tool_device)) {
		ret = PTR_ERR(mem_tool_device);
		goto err_class;
	}

	pr_info("kmem: /dev/%s created (major %d). ready.\n",
		node, MAJOR(mem_tool_dev_t));
	return 0;

err_class:
	class_destroy(mem_tool_class);
err_cdev:
	cdev_del(&char_dev);
err_region:
	unregister_chrdev_region(mem_tool_dev_t, 1);
	return ret;
}

static void __exit driver_unload(void)
{
	if (mem_tool_device)
		device_destroy(mem_tool_class, mem_tool_dev_t);
	if (mem_tool_class)
		class_destroy(mem_tool_class);
	cdev_del(&char_dev);
	unregister_chrdev_region(mem_tool_dev_t, 1);
	pr_info("kmem: /dev/%s removed\n", devicename);
}

module_init(driver_entry);
module_exit(driver_unload);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("wanbai");
MODULE_DESCRIPTION("wanbai");
MODULE_VERSION("2.0-337");
