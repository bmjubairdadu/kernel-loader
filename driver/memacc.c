/* SPDX-License-Identifier: GPL-2.0 */
/*
 * memacc.c - RT ABI kernel sync driver
 *
 * ioctl ABI: 0x801=read, 0x802=write, 0x803=modbase, 0x805=hide,
 *            0x806=unhide, 0x807=batch, 0x808=status, 0x809=reserved
 * Returns 0 on success, negative errno on failure.
 * Module name: ksync (matches KNOWN_DRIVER_MODULES in app).
 */
#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/namei.h>
#include <linux/jiffies.h>
#include <linux/workqueue.h>

#include "kmem_337_core.h"

#define CMD_PROC_READ	0x801
#define CMD_PROC_WRITE	0x802
#define CMD_MOD_BASE	0x803
#define CMD_UNHIDE	0x806
#define CMD_HIDE	0x805
#define CMD_STATUS	0x808
#define CMD_FUTURE	0x809
#define CMD_BATCH_U32	0x807

static dev_t rt_dev;
static struct cdev rt_cdev;
static struct class *rt_class;
static struct device *rt_device;

#define RT_PERM_MAX_TRIES	30
#ifndef KMEM_NO_PERM_WORK
static int rt_perm_tries;
static void rt_perm_work_fn(struct work_struct *w);
static struct delayed_work rt_perm_work;
#endif

static char *devicename = "ksync";
module_param(devicename, charp, 0644);
MODULE_PARM_DESC(devicename, "device node name");

static char *devname = "ksync";
module_param(devname, charp, 0644);
MODULE_PARM_DESC(devname, "alias of devicename");

static unsigned long st_read;
module_param(st_read, ulong, 0444);
static unsigned long st_write;
module_param(st_write, ulong, 0444);
static unsigned long st_modbase;
module_param(st_modbase, ulong, 0444);
static unsigned long st_batch;
module_param(st_batch, ulong, 0444);

static int rt_open(struct inode *inode, struct file *file)
{
	return 0;
}

static int rt_release(struct inode *inode, struct file *file)
{
	return 0;
}

static long rt_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	copy_memoby_t cm;
	module_base_t mb;
	kmem_batch_u32_t bu;
	char name[256];
	int ret;

	switch (cmd) {
	case CMD_PROC_READ:
		st_read++;
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -EFAULT;
		ret = kmem_read_process(cm.pid, cm.addr,
					(void __user *)cm.buffer, cm.size);
		return ret ? -EIO : 0;

	case CMD_PROC_WRITE:
		st_write++;
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -EFAULT;
		ret = kmem_write_process(cm.pid, cm.addr,
					 (void __user *)cm.buffer, cm.size);
		return ret ? -EIO : 0;

	case CMD_MOD_BASE:
		st_modbase++;
		if (copy_from_user(&mb, (void __user *)arg, sizeof(mb)))
			return -EFAULT;
		memset(name, 0, sizeof(name));
		if (copy_from_user(name, (void __user *)mb.name, 255))
			return -EFAULT;
		name[255] = '\0';
		mb.base = kmem_get_modbase(mb.pid, name);
		if (copy_to_user((void __user *)arg, &mb, sizeof(mb)))
			return -EFAULT;
		return 0;

	case CMD_HIDE:
		kmem_hide_module(THIS_MODULE);
		return 0;

	case CMD_UNHIDE:
		kmem_unhide_module(THIS_MODULE);
		return 0;

	case CMD_BATCH_U32:
		st_batch++;
		if (copy_from_user(&bu, (void __user *)arg, sizeof(bu)))
			return -EFAULT;
		ret = kmem_batch_read_u32(&bu);
		if (copy_to_user((void __user *)arg, &bu, sizeof(bu)))
			return -EFAULT;
		return ret ? -EIO : 0;

	case CMD_STATUS:
		return kmem_fill_status((void __user *)arg, KMEM_ABI_RT,
					st_read, st_write, st_modbase, st_batch);

	case CMD_FUTURE:
		return -ENOTTY;

	default:
		return -EINVAL;
	}
}

#ifdef CONFIG_COMPAT
#include <linux/compat.h>

typedef struct {
	compat_int_t	pid;
	compat_uptr_t	addr;
	compat_uptr_t	buffer;
	compat_size_t	size;
} compat_copy_memoby_t;

typedef struct {
	compat_int_t	pid;
	compat_u64	addr;
	compat_uptr_t	buffer;
	compat_size_t	size;
} compat_copy_memoby64_t;

typedef struct {
	compat_int_t	pid;
	compat_uptr_t	name;
	compat_uptr_t	base;
} compat_module_base_t;

static long rt_compat_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	void __user *uarg = compat_ptr(arg);
	compat_copy_memoby_t cm32;
	compat_copy_memoby64_t cm64;
	compat_module_base_t mb32;
	char name[256];
	int ret;
	uintptr_t base;

	switch (cmd) {
	case CMD_PROC_READ:
		st_read++;
		if (copy_from_user(&cm32, uarg, sizeof(cm32)))
			return -EFAULT;
		if (cm32.size == 0) {
			if (copy_from_user(&cm64, uarg, sizeof(cm64)))
				return -EFAULT;
			ret = kmem_read_process(cm64.pid, (uintptr_t)cm64.addr,
						compat_ptr(cm64.buffer), cm64.size);
		} else {
			ret = kmem_read_process(cm32.pid, (uintptr_t)cm32.addr,
						compat_ptr(cm32.buffer), cm32.size);
		}
		return ret ? -EIO : 0;

	case CMD_PROC_WRITE:
		st_write++;
		if (copy_from_user(&cm32, uarg, sizeof(cm32)))
			return -EFAULT;
		if (cm32.size == 0) {
			if (copy_from_user(&cm64, uarg, sizeof(cm64)))
				return -EFAULT;
			ret = kmem_write_process(cm64.pid, (uintptr_t)cm64.addr,
						 compat_ptr(cm64.buffer), cm64.size);
		} else {
			ret = kmem_write_process(cm32.pid, (uintptr_t)cm32.addr,
						 compat_ptr(cm32.buffer), cm32.size);
		}
		return ret ? -EIO : 0;

	case CMD_MOD_BASE:
		st_modbase++;
		if (copy_from_user(&mb32, uarg, sizeof(mb32)))
			return -EFAULT;
		memset(name, 0, sizeof(name));
		if (copy_from_user(name, compat_ptr(mb32.name), 255))
			return -EFAULT;
		name[255] = '\0';
		base = kmem_get_modbase(mb32.pid, name);
		mb32.base = (compat_uptr_t)base;
		if (copy_to_user(uarg, &mb32, sizeof(mb32)))
			return -EFAULT;
		{
			compat_u64 base64 = base;
			copy_to_user((char __user *)uarg + 8, &base64, sizeof(base64));
		}
		return 0;

	case CMD_UNHIDE:
		kmem_unhide_module(THIS_MODULE);
		return 0;

	case CMD_BATCH_U32: {
		kmem_batch_u32_t bu;
		st_batch++;
		if (copy_from_user(&bu, uarg, sizeof(bu)))
			return -EFAULT;
		ret = kmem_batch_read_u32(&bu);
		if (copy_to_user(uarg, &bu, sizeof(bu)))
			return -EFAULT;
		return ret ? -EIO : 0;
	}

	default:
		return rt_ioctl(file, cmd, (unsigned long)uarg);
	}
}
#endif

static const struct file_operations rt_fops = {
	.owner		= THIS_MODULE,
	.open		= rt_open,
	.release	= rt_release,
	.unlocked_ioctl	= rt_ioctl,
#ifdef CONFIG_COMPAT
	.compat_ioctl	= rt_compat_ioctl,
#else
	.compat_ioctl	= rt_ioctl,
#endif
	.llseek		= noop_llseek,
};

static char *rt_devnode(struct device *dev, umode_t *mode)
{
	if (mode)
		*mode = 0666;
	return NULL;
}

static const char *rt_pick_node(void)
{
	const char *node = devicename;

	if (!node || !*node)
		node = NULL;
	if (devname && *devname && strcmp(devname, "ksync"))
		node = devname;
	if (!node)
		node = "ksync";
	return node;
}

#ifndef KMEM_NO_PERM_WORK
static void rt_perm_work_fn(struct work_struct *w)
{
	struct path path;
	struct dentry *dentry;
	char full[64];

	snprintf(full, sizeof(full), "/dev/%s", rt_pick_node());

	if (!kern_path(full, LOOKUP_FOLLOW, &path)) {
		dentry = path.dentry;
		inode_lock(dentry->d_inode);
		dentry->d_inode->i_mode =
			(dentry->d_inode->i_mode & ~S_IALLUGO) | 0666;
		inode_unlock(dentry->d_inode);
		path_put(&path);
		rt_perm_tries = 0;
		return;
	}

	if (++rt_perm_tries >= RT_PERM_MAX_TRIES)
		return;

	schedule_delayed_work(&rt_perm_work, msecs_to_jiffies(200));
}
#endif

static int __init rt_init(void)
{
	const char *node;
	int ret;

	node = rt_pick_node();

	ret = alloc_chrdev_region(&rt_dev, 0, 1, node);
	if (ret)
		return ret;

	cdev_init(&rt_cdev, &rt_fops);
	rt_cdev.owner = THIS_MODULE;
	ret = cdev_add(&rt_cdev, rt_dev, 1);
	if (ret)
		goto err_region;

	rt_class = KMEM_CLASS_CREATE("ksync");
	if (IS_ERR(rt_class)) {
		ret = PTR_ERR(rt_class);
		goto err_cdev;
	}
	rt_class->devnode = (typeof(rt_class->devnode))rt_devnode;

	rt_device = device_create(rt_class, NULL, rt_dev, NULL, node);
	if (IS_ERR(rt_device)) {
		ret = PTR_ERR(rt_device);
		goto err_class;
	}

#ifndef KMEM_NO_PERM_WORK
	INIT_DELAYED_WORK(&rt_perm_work, rt_perm_work_fn);
	rt_perm_tries = 0;
	schedule_delayed_work(&rt_perm_work, 0);
#endif

	return 0;

err_class:
	class_destroy(rt_class);
err_cdev:
	cdev_del(&rt_cdev);
err_region:
	unregister_chrdev_region(rt_dev, 1);
	return ret;
}

static void __exit rt_exit(void)
{
#ifndef KMEM_NO_PERM_WORK
	cancel_delayed_work_sync(&rt_perm_work);
#endif
	device_destroy(rt_class, rt_dev);
	class_destroy(rt_class);
	cdev_del(&rt_cdev);
	unregister_chrdev_region(rt_dev, 1);
}

module_init(rt_init);
module_exit(rt_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("ksync");
MODULE_DESCRIPTION("Kernel sync helper");
MODULE_VERSION("3.1.6-337");
