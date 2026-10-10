/* SPDX-License-Identifier: GPL-2.0 */
/*
 * memacc.c - RT ABI kernel memory driver
 *
 * ioctl ABI: 0x801=read, 0x802=write, 0x803=modbase, 0x806=unhide(noop)
 * Returns 0 on success, negative errno on failure.
 * Module name: memacc (matches KNOWN_DRIVER_MODULES in app).
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
#define CMD_BATCH_U32	0x807

static dev_t rt_dev;
static struct cdev rt_cdev;
static struct class *rt_class;
static struct device *rt_device;

#define RT_PERM_MAX_TRIES	8
static int rt_perm_tries;
static void rt_perm_work_fn(struct work_struct *w);
static struct delayed_work rt_perm_work;

static char *devicename = "wanbai";
module_param(devicename, charp, 0644);
MODULE_PARM_DESC(devicename, "device node name");

static char *devname = "wanbai";
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

static long rt_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	copy_memoby_t cm;
	module_base_t mb;
	kmem_batch_u32_t bu;
	char name[256];
	int ret;

	switch (cmd) {
	case CMD_PROC_READ:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -EFAULT;
		ret = kmem_read_process(cm.pid, cm.addr,
					(void __user *)cm.buffer, cm.size);
		return ret ? -EIO : 0;

	case CMD_PROC_WRITE:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -EFAULT;
		ret = kmem_write_process(cm.pid, cm.addr,
					 (void __user *)cm.buffer, cm.size);
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

	case CMD_UNHIDE:
		return 0;

	case CMD_BATCH_U32:
		if (copy_from_user(&bu, (void __user *)arg, sizeof(bu)))
			return -EFAULT;
		ret = kmem_batch_read_u32(&bu);
		if (copy_to_user((void __user *)arg, &bu, sizeof(bu)))
			return -EFAULT;
		return ret ? -EIO : 0;

	default:
		return -EINVAL;
	}
}

static const struct file_operations rt_fops = {
	.owner		= THIS_MODULE,
	.open		= rt_open,
	.release	= rt_release,
	.unlocked_ioctl	= rt_ioctl,
#ifdef CONFIG_COMPAT
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
	if (devname && *devname && strcmp(devname, "wanbai"))
		node = devname;
	if (!node)
		node = "wanbai";
	return node;
}

/*
 * devtmpfs is NOT mounted on this Android build (/dev is a read-only
 * ramdisk), and Android's ueventd creates the node asynchronously with
 * root-only 0600 regardless of our DEVMODE=0666 hint. Apps run as
 * unprivileged uids and cannot open it. Widen the mode ourselves once the
 * node actually exists. Because ueventd may lag behind init(), retry a few
 * times on a short delay before giving up.
 */
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

	/*
	 * The sysfs class name must be UNIQUE across modules. RT and QX both
	 * default their /dev node to "wanbai", so naming the class after the
	 * node makes the second insmod fail with
	 *   sysfs: cannot create duplicate filename '/class/wanbai'
	 *   kobject_add_internal failed ... -EEXIST
	 * Use the module name instead: the /dev node keeps its own name, only
	 * the class is per-module.
	 */
	rt_class = KMEM_CLASS_CREATE("memacc");
	if (IS_ERR(rt_class)) {
		ret = PTR_ERR(rt_class);
		goto err_cdev;
	}
	rt_class->devnode = rt_devnode;

	rt_device = device_create(rt_class, NULL, rt_dev, NULL, node);
	if (IS_ERR(rt_device)) {
		ret = PTR_ERR(rt_device);
		goto err_class;
	}

	/* Force 0666 on /dev/<node> regardless of devtmpfs/ueventd. */
	INIT_DELAYED_WORK(&rt_perm_work, rt_perm_work_fn);
	rt_perm_tries = 0;
	schedule_delayed_work(&rt_perm_work, 0);

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
	cancel_delayed_work_sync(&rt_perm_work);
	device_destroy(rt_class, rt_dev);
	class_destroy(rt_class);
	cdev_del(&rt_cdev);
	unregister_chrdev_region(rt_dev, 1);
}

module_init(rt_init);
module_exit(rt_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("daisy");
MODULE_DESCRIPTION("RT memory accessor");
MODULE_VERSION("3.1.1-337");
