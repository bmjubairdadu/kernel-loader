/* SPDX-License-Identifier: GPL-2.0 */
/*
 * memacc_qx.c - QX ABI kernel memory driver
 *
 * ioctl ABI: 0x801=read, 0x802=write, 0x803=modbase,
 *            0x804=handshake(returns 2), 0x805=clear, 0x806=unhide(noop)
 * Returns 0 on success, -1 on failure (QX convention).
 * Module name: memacc_qx (matches KNOWN_DRIVER_MODULES in app).
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

#define QX_CMD_READ		0x801
#define QX_CMD_WRITE		0x802
#define QX_CMD_MOD_BASE		0x803
#define QX_CMD_HANDSHAKE	0x804
#define QX_CMD_CLEAR		0x805
#define QX_CMD_UNHIDE		0x806
#define QX_CMD_BATCH_U32	0x807
#define QX_HANDSHAKE_MAGIC	666

static dev_t qx_dev;
static struct cdev qx_cdev;
static struct class *qx_class;
static struct device *qx_device;

#define QX_PERM_MAX_TRIES	8
static int qx_perm_tries;
static void qx_perm_work_fn(struct work_struct *w);
static struct delayed_work qx_perm_work;

static char *devicename = "wanbai";
module_param(devicename, charp, 0644);
MODULE_PARM_DESC(devicename, "device node name");

static char *devname = "wanbai";
module_param(devname, charp, 0644);
MODULE_PARM_DESC(devname, "alias of devicename");

static int qx_open(struct inode *inode, struct file *file)
{
	return 0;
}

static int qx_release(struct inode *inode, struct file *file)
{
	return 0;
}

static long qx_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	copy_memoby_t cm;
	module_base_t mb;
	kmem_batch_u32_t bu;
	char name[256];

	switch (cmd) {
	case QX_CMD_READ:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -1;
		if (kmem_read_process(cm.pid, cm.addr,
				      (void __user *)cm.buffer, cm.size))
			return -1;
		return 0;

	case QX_CMD_WRITE:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -1;
		if (kmem_write_process(cm.pid, cm.addr,
				       (void __user *)cm.buffer, cm.size))
			return -1;
		return 0;

	case QX_CMD_MOD_BASE:
		if (copy_from_user(&mb, (void __user *)arg, sizeof(mb)))
			return -1;
		memset(name, 0, sizeof(name));
		if (copy_from_user(name, (void __user *)mb.name, 255))
			return -1;
		name[255] = '\0';
		mb.base = kmem_get_modbase(mb.pid, name);
		if (copy_to_user((void __user *)arg, &mb, sizeof(mb)))
			return -1;
		return 0;

	case QX_CMD_HANDSHAKE:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -1;
		cm.pid = QX_HANDSHAKE_MAGIC;
		if (copy_to_user((void __user *)arg, &cm, sizeof(cm)))
			return -1;
		return 2;

	case QX_CMD_CLEAR:
		return 0;

	case QX_CMD_UNHIDE:
		return 0;

	case QX_CMD_BATCH_U32:
		if (copy_from_user(&bu, (void __user *)arg, sizeof(bu)))
			return -1;
		if (kmem_batch_read_u32(&bu)) {
			if (copy_to_user((void __user *)arg, &bu, sizeof(bu)))
				return -1;
			return -1;
		}
		if (copy_to_user((void __user *)arg, &bu, sizeof(bu)))
			return -1;
		return 0;

	default:
		return 0;
	}
}

static const struct file_operations qx_fops = {
	.owner		= THIS_MODULE,
	.open		= qx_open,
	.release	= qx_release,
	.unlocked_ioctl	= qx_ioctl,
#ifdef CONFIG_COMPAT
	.compat_ioctl	= qx_ioctl,
#endif
	.llseek		= noop_llseek,
};

static char *qx_devnode(struct device *dev, umode_t *mode)
{
	if (mode)
		*mode = 0666;
	return NULL;
}

static const char *qx_pick_node(void)
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
 * ramdisk), so the DEVMODE=0666 uevent never reaches a node and the char
 * device lands with root-only 0600. Android apps run as unprivileged uids
 * and cannot open it. Locate the freshly-created node through the VFS and
 * widen its mode so it is usable the instant init() returns.
 */
static void qx_perm_work_fn(struct work_struct *w)
{
	struct path path;
	struct dentry *dentry;
	char full[64];

	snprintf(full, sizeof(full), "/dev/%s", qx_pick_node());

	if (!kern_path(full, LOOKUP_FOLLOW, &path)) {
		dentry = path.dentry;
		inode_lock(dentry->d_inode);
		dentry->d_inode->i_mode =
			(dentry->d_inode->i_mode & ~S_IALLUGO) | 0666;
		inode_unlock(dentry->d_inode);
		path_put(&path);
		qx_perm_tries = 0;
		return;
	}

	if (++qx_perm_tries >= QX_PERM_MAX_TRIES)
		return;

	schedule_delayed_work(&qx_perm_work, msecs_to_jiffies(200));
}

static int __init qx_init(void)
{
	const char *node;
	int ret;

	node = qx_pick_node();

	ret = alloc_chrdev_region(&qx_dev, 0, 1, node);
	if (ret)
		return ret;

	cdev_init(&qx_cdev, &qx_fops);
	qx_cdev.owner = THIS_MODULE;
	ret = cdev_add(&qx_cdev, qx_dev, 1);
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
	qx_class = KMEM_CLASS_CREATE("memacc_qx");
	if (IS_ERR(qx_class)) {
		ret = PTR_ERR(qx_class);
		goto err_cdev;
	}
	qx_class->devnode = qx_devnode;

	qx_device = device_create(qx_class, NULL, qx_dev, NULL, node);
	if (IS_ERR(qx_device)) {
		ret = PTR_ERR(qx_device);
		goto err_class;
	}

	/* Force 0666 on /dev/<node> regardless of devtmpfs/ueventd. */
	INIT_DELAYED_WORK(&qx_perm_work, qx_perm_work_fn);
	qx_perm_tries = 0;
	schedule_delayed_work(&qx_perm_work, 0);

	return 0;

err_class:
	class_destroy(qx_class);
err_cdev:
	cdev_del(&qx_cdev);
err_region:
	unregister_chrdev_region(qx_dev, 1);
	return ret;
}

static void __exit qx_exit(void)
{
	cancel_delayed_work_sync(&qx_perm_work);
	device_destroy(qx_class, qx_dev);
	class_destroy(qx_class);
	cdev_del(&qx_cdev);
	unregister_chrdev_region(qx_dev, 1);
}

module_init(qx_init);
module_exit(qx_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("daisy");
MODULE_DESCRIPTION("QX memory accessor");
MODULE_VERSION("3.1.1-337-qx");
