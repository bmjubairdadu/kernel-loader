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

#define QX_CMD_READ		0x801
#define QX_CMD_WRITE		0x802
#define QX_CMD_MOD_BASE		0x803
#define QX_CMD_HANDSHAKE	0x804
#define QX_CMD_CLEAR		0x805
#define QX_HANDSHAKE_MAGIC	666

static dev_t qx_dev;
static struct cdev qx_cdev;
static struct class *qx_class;
static struct device *qx_device;
static struct kmem_alt_node qx_alt;
static unsigned long qx_node_retry;

/* 0 = repair forever - see dmsync_rt.c.  A node touched after the old
 * 60-second window must still look fresh for the next match. */
#define QX_PERM_MAX_TRIES	0
#ifndef KMEM_NO_PERM_WORK
static int qx_perm_tries;
static void qx_perm_work_fn(struct work_struct *w);
static struct delayed_work qx_perm_work;
#endif

static char *devicename = "";
module_param(devicename, charp, 0644);
MODULE_PARM_DESC(devicename, "device node name");

static char *devname = "";
module_param(devname, charp, 0644);
MODULE_PARM_DESC(devname, "alias of devicename (preferred when set)");

static int qx_open(struct inode *inode, struct file *file)
{
	return 0;
}

static int qx_release(struct inode *inode, struct file *file)
{
	return 0;
}

/*
 * ABI selection - see dmsync_rt.c for the details.
 *   QX ABI : read/write return 0 on success, -1 on failure (default)
 *   RT ABI : read/write return -EIO (-5) on success, 0 on failure
 */
static char *abi = "qx";
module_param(abi, charp, 0644);
MODULE_PARM_DESC(abi, "ioctl return convention: 'qx' (success = 0) or 'rt' (success = -EIO)");

static int qx_abi_is_rt(void)
{
	return abi && (!strcmp(abi, "rt") || !strcmp(abi, "RT"));
}

static long qx_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	copy_memoby_t cm;
	module_base_t mb;
	char name[256];

	switch (cmd) {
	case QX_CMD_READ:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -1;
		if (kmem_read_process(cm.pid, cm.addr,
				      (void __user *)cm.buffer, cm.size)) {
			if (qx_abi_is_rt())
				return 0;
			return -1;
		}
		if (qx_abi_is_rt())
			return -EIO;
		return 0;

	case QX_CMD_WRITE:
		if (copy_from_user(&cm, (void __user *)arg, sizeof(cm)))
			return -1;
		if (kmem_write_process(cm.pid, cm.addr,
				       (void __user *)cm.buffer, cm.size)) {
			if (qx_abi_is_rt())
				return 0;
			return -1;
		}
		if (qx_abi_is_rt())
			return -EIO;
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

	default:
		return 0;
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

static long qx_compat_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	void __user *uarg = compat_ptr(arg);
	compat_copy_memoby_t cm32;
	compat_copy_memoby64_t cm64;
	compat_module_base_t mb32;
	char name[256];
	uintptr_t base;

	switch (cmd) {
	case QX_CMD_READ:
		if (copy_from_user(&cm32, uarg, sizeof(cm32)))
			return -1;
		if (cm32.size == 0) {
			if (copy_from_user(&cm64, uarg, sizeof(cm64)))
				return -1;
			if (kmem_read_process(cm64.pid, (uintptr_t)cm64.addr,
					      compat_ptr(cm64.buffer), cm64.size))
				return -1;
		} else {
			if (kmem_read_process(cm32.pid, (uintptr_t)cm32.addr,
					      compat_ptr(cm32.buffer), cm32.size))
				return -1;
		}
		return 0;

	case QX_CMD_WRITE:
		if (copy_from_user(&cm32, uarg, sizeof(cm32)))
			return -1;
		if (cm32.size == 0) {
			if (copy_from_user(&cm64, uarg, sizeof(cm64)))
				return -1;
			if (kmem_write_process(cm64.pid, (uintptr_t)cm64.addr,
					       compat_ptr(cm64.buffer), cm64.size))
				return -1;
		} else {
			if (kmem_write_process(cm32.pid, (uintptr_t)cm32.addr,
					       compat_ptr(cm32.buffer), cm32.size))
				return -1;
		}
		return 0;

	case QX_CMD_MOD_BASE:
		if (copy_from_user(&mb32, uarg, sizeof(mb32)))
			return -1;
		memset(name, 0, sizeof(name));
		if (copy_from_user(name, compat_ptr(mb32.name), 255))
			return -1;
		name[255] = '\0';
		base = kmem_get_modbase(mb32.pid, name);
		mb32.base = (compat_uptr_t)base;
		if (copy_to_user(uarg, &mb32, sizeof(mb32)))
			return -1;
		{
			compat_u64 base64 = base;
			if (copy_to_user((char __user *)uarg + 8, &base64, sizeof(base64)))
				return -1;
		}
		return 0;

	case QX_CMD_HANDSHAKE:
		if (copy_from_user(&cm32, uarg, sizeof(cm32)))
			return -1;
		cm32.pid = QX_HANDSHAKE_MAGIC;
		if (copy_to_user(uarg, &cm32, sizeof(cm32)))
			return -1;
		return 2;

	case QX_CMD_CLEAR:
		return 0;

	default:
		return qx_ioctl(file, cmd, (unsigned long)uarg);
	}
}
#endif

static const struct file_operations qx_fops = {
	.owner		= THIS_MODULE,
	.open		= qx_open,
	.release	= qx_release,
	.unlocked_ioctl	= qx_ioctl,
#if defined(CONFIG_COMPAT) && !defined(KMEM_NO_COMPAT32)
	.compat_ioctl	= qx_compat_ioctl,
#elif !defined(KMEM_NO_COMPAT32)
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

static char qx_active_node[16] = {0};

static const char *qx_pick_node(void)
{
	if (qx_active_node[0])
		return qx_active_node;

	if (devname && *devname)
		snprintf(qx_active_node, sizeof(qx_active_node), "%s", devname);
	else if (devicename && *devicename)
		snprintf(qx_active_node, sizeof(qx_active_node), "%s", devicename);
	else
		kmem_rand_name(qx_active_node, KMEM_RAND_NAME_LEN);

	return qx_active_node;
}

#ifndef KMEM_NO_PERM_WORK
/* Grace window right after insmod */
static unsigned long qx_perm_stable;
static void qx_perm_work_fn(struct work_struct *w)
{
	const char *node = qx_pick_node();
	unsigned long delay;
	int busy = 0;

	if (kmem_repair_primary_node(qx_class, &qx_device, qx_dev, node,
				     &qx_node_retry))
		busy = 1;
	if (kmem_touch_node_mode(node, 0666))
		busy = 1;

	if (QX_PERM_MAX_TRIES && ++qx_perm_tries >= QX_PERM_MAX_TRIES)
		return;

	if (busy)
		delay = 200;          /* active repair - stay responsive */
	else if (time_before(jiffies, qx_perm_stable))
		delay = 200;          /* grace window */
	else
		delay = 1000;         /* stable, nothing to do - save CPU/heat */
	schedule_delayed_work(&qx_perm_work, msecs_to_jiffies(delay));
}
#endif

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

	qx_class = KMEM_CLASS_CREATE(node);
	if (IS_ERR(qx_class)) {
		ret = PTR_ERR(qx_class);
		goto err_cdev;
	}
	qx_class->devnode = (typeof(qx_class->devnode))qx_devnode;

	qx_device = device_create(qx_class, NULL, qx_dev, NULL, node);
	if (IS_ERR(qx_device)) {
		ret = PTR_ERR(qx_device);
		goto err_class;
	}

	/* Accessible + frozen atime from the first moment */
	kmem_force_node_mode(node, 0666);

#ifndef KMEM_NO_PERM_WORK
	INIT_DELAYED_WORK(&qx_perm_work, qx_perm_work_fn);
	qx_perm_tries = 0;
	qx_perm_stable = jiffies + msecs_to_jiffies(60000);
	schedule_delayed_work(&qx_perm_work, 0);
#endif

	/* stealth: leave no lsmod / sysfs trace (reference behaviour) */
	kmem_hide_module(THIS_MODULE);

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
#ifndef KMEM_NO_PERM_WORK
	cancel_delayed_work_sync(&qx_perm_work);
#endif
	device_destroy(qx_class, qx_dev);
	class_destroy(qx_class);
	cdev_del(&qx_cdev);
	unregister_chrdev_region(qx_dev, 1);
}

module_init(qx_init);
module_exit(qx_exit);

MODULE_LICENSE("GPL");
