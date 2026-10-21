/* SPDX-License-Identifier: GPL-2.0 */
#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/namei.h>
#include <linux/jiffies.h>
#include <linux/workqueue.h>

#include "dmsync_core.h"

#define CMD_PROC_READ	0x801
#define CMD_PROC_WRITE	0x802
#define CMD_MOD_BASE	0x803
/* init key: a client that sends it gets the QX return convention */
#define CMD_INIT_KEY	0x800

static dev_t rt_dev;
static struct cdev rt_cdev;
static struct class *rt_class;
static struct device *rt_device;
static struct kmem_alt_node rt_alt;

#define RT_PERM_MAX_TRIES	300
#ifndef KMEM_NO_PERM_WORK
static int rt_perm_tries;
static void rt_perm_work_fn(struct work_struct *w);
static struct delayed_work rt_perm_work;
#endif

static char *devicename = "dmsync";
module_param(devicename, charp, 0644);
MODULE_PARM_DESC(devicename, "device node name");

static char *devname = "dmsync";
module_param(devname, charp, 0644);
MODULE_PARM_DESC(devname, "alias of devicename");

static int rt_open(struct inode *inode, struct file *file)
{
	return 0;
}

static int rt_release(struct inode *inode, struct file *file)
{
	return 0;
}

/*
 * ABI selection.
 *
 * Two client families talk to this driver and they disagree on what a
 * successful ioctl must return:
 *
 *   RT ABI : read/write return -EIO (-5) on success, 0 on failure
 *   QX ABI : read/write return 0 on success, -1 on failure
 *
 * The Aincrad Mods client (libservice.so) uses the QX convention - it
 * evaluates the result as "cmp w0,#0 ; cset w0,eq".  The classic
 * "Driver Loader V3" clients use the RT convention.
 *
 * The same .ko can serve both:
 *   - "abi=qx" (module parameter) makes the whole driver speak QX
 *   - a client that sends the init key (0x800) gets QX semantics for
 *     that file descriptor only, so one driver can serve both at once
 */
static char *abi = "compat";
module_param(abi, charp, 0644);
MODULE_PARM_DESC(abi, "ioctl return convention: 'compat' (success = 0, 3.2.x behaviour), 'rt' (success = -EIO), 'qx' (success = 0)");

static int rt_abi_is_qx(void)
{
	return abi && (!strcmp(abi, "qx") || !strcmp(abi, "QX"));
}

/* strict reference RT: success is -EIO.  Opt-in only (abi=rt), because
 * the shipped clients of this tree expect success = 0. */
static int rt_abi_is_strict(void)
{
	return abi && (!strcmp(abi, "rt") || !strcmp(abi, "RT"));
}

/* per-fd override, set by the 0x800 init key */
static int rt_fd_is_qx(struct file *file)
{
	if (file && file->private_data)
		return 1;
	return rt_abi_is_qx();
}

static long rt_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	copy_memoby_t cm;
	module_base_t mb;
	char name[256];
	int ret;

	/* Legacy RT surface is exactly these three commands, matching the
	 * reference RT driver's dispatch_ioctl.  Everything else -EINVAL. */
	switch (cmd) {
	case CMD_INIT_KEY:
		/* The client proved it speaks the QX ABI: remember it for
		 * this descriptor and answer 0 (= success for QX). */
		file->private_data = (void *)(uintptr_t)1;
		return 0;

	case CMD_PROC_READ:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -EFAULT;
		ret = kmem_read_process(cm.pid, cm.addr,
					(void __user *)cm.buffer, cm.size);
		if (rt_fd_is_qx(file))
			return ret ? -1 : 0;
		if (rt_abi_is_strict())
			return ret ? 0 : -EIO;
		/* 3.2.x compatibility (default): success is 0, failure -EIO.
		 * This is the convention the shipped clients expect. */
		return ret ? -EIO : 0;

	case CMD_PROC_WRITE:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -EFAULT;
		ret = kmem_write_process(cm.pid, cm.addr,
					 (void __user *)cm.buffer, cm.size);
		if (rt_fd_is_qx(file))
			return ret ? -1 : 0;
		if (rt_abi_is_strict())
			return ret ? 0 : -EIO;
		return ret ? -EIO : 0;

	case CMD_MOD_BASE:
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

	default:
		return -EINVAL;
	}
}

#if defined(CONFIG_COMPAT) && !defined(KMEM_NO_COMPAT32)
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
		return ret ? 0 : -EIO;

	case CMD_PROC_WRITE:
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
		return ret ? 0 : -EIO;

	case CMD_MOD_BASE:
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
			if (copy_to_user((char __user *)uarg + 8, &base64, sizeof(base64)))
				return -EFAULT;
		}
		return 0;

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
#if defined(CONFIG_COMPAT) && !defined(KMEM_NO_COMPAT32)
	.compat_ioctl	= rt_compat_ioctl,
#elif !defined(KMEM_NO_COMPAT32)
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
	if (devname && *devname && strcmp(devname, "dmsync"))
		node = devname;
	if (!node)
		node = "dmsync";
	return node;
}

#ifndef KMEM_NO_PERM_WORK
static void rt_perm_work_fn(struct work_struct *w)
{
	const char *node = rt_pick_node();

	/* Keep the node world-accessible AND "fresh" (atime == ctime) for a
	 * while after the load: the loader app runs chmod/chcon on the node
	 * right after insmod, which bumps ctime and hides the node from a
	 * /dev scanner.  kmem_force_node_mode() repairs both without ever
	 * touching ctime. */
	kmem_force_node_mode(node, 0666);
	if (rt_alt.cls)
		kmem_force_node_mode(rt_alt.name, 0666);

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

	rt_class = KMEM_CLASS_CREATE("dmsync");
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

	/* Make the requested node world-accessible and freeze its atime right
	 * away, so it is discoverable before the loader app gets a chance to
	 * chmod/chcon it. */
	kmem_force_node_mode(node, 0666);

	/* Second node with a reference-style random name.  The loader app
	 * chmod/chcon's the node it loaded, which bumps ctime and makes the
	 * node stop looking "fresh" to a /dev scanner (atime == ctime).  The
	 * scanner then finds nothing and the client stays "Disconnected".
	 * This node is never touched, so it keeps atime == ctime. */
	if (!kmem_create_alt_node(&rt_alt, &rt_fops))
		pr_debug("dmsync: fresh node /dev/%s\n", rt_alt.name);
	else
		pr_warn("dmsync: fresh node failed\n");

#ifndef KMEM_NO_PERM_WORK
	INIT_DELAYED_WORK(&rt_perm_work, rt_perm_work_fn);
	rt_perm_tries = 0;
	schedule_delayed_work(&rt_perm_work, 0);
#endif

	/* stealth: leave no lsmod / sysfs trace (reference behaviour) */
	kmem_hide_module(THIS_MODULE);

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
	kmem_destroy_alt_node(&rt_alt);
	device_destroy(rt_class, rt_dev);
	class_destroy(rt_class);
	cdev_del(&rt_cdev);
	unregister_chrdev_region(rt_dev, 1);
}

module_init(rt_init);
module_exit(rt_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("dmsync");
MODULE_DESCRIPTION("Device state sync helper");
MODULE_VERSION("3.3.0");
