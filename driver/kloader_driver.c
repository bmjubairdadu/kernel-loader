#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/miscdevice.h>
#include <linux/fs.h>
#include <linux/uaccess.h>
#include <linux/device.h>
#include <linux/utsname.h>

#define DRIVER_NAME "kloaderctl"
#define DRIVER_DESC "Kernel Loder universal driver - 4.9.337"
#define DRIVER_VERSION "2.0-337"
#define DRIVER_TAG "KernelLoder"

static int kloader_open(struct inode *inode, struct file *file)
{
	pr_info(DRIVER_TAG ": /dev/%s opened\n", DRIVER_NAME);
	return 0;
}

static int kloader_release(struct inode *inode, struct file *file)
{
	pr_info(DRIVER_TAG ": /dev/%s closed\n", DRIVER_NAME);
	return 0;
}

static ssize_t kloader_read(struct file *file, char __user *buf,
			    size_t count, loff_t *ppos)
{
	const char msg[] = "Kernel Loder 4.9.337 OK\n";
	size_t len = sizeof(msg) - 1;

	if (*ppos >= len)
		return 0;
	if (count > len - *ppos)
		count = len - *ppos;
	if (copy_to_user(buf, msg + *ppos, count))
		return -EFAULT;
	*ppos += count;
	return count;
}

static ssize_t kloader_write(struct file *file, const char __user *buf,
			     size_t count, loff_t *ppos)
{
	char kbuf[128];
	size_t n = min(count, sizeof(kbuf) - 1);

	if (copy_from_user(kbuf, buf, n))
		return -EFAULT;
	kbuf[n] = '\0';
	pr_info(DRIVER_TAG ": write: %s\n", kbuf);
	return count;
}

static long kloader_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	pr_info(DRIVER_TAG ": ioctl cmd=0x%x arg=0x%lx (shim -> 0)\n", cmd, arg);
	return 0;
}

#ifdef CONFIG_COMPAT
static long kloader_compat_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	return kloader_ioctl(file, cmd, arg);
}
#endif

static const struct file_operations kloader_fops = {
	.owner		= THIS_MODULE,
	.open		= kloader_open,
	.release	= kloader_release,
	.read		= kloader_read,
	.write		= kloader_write,
	.unlocked_ioctl	= kloader_ioctl,
#ifdef CONFIG_COMPAT
	.compat_ioctl	= kloader_compat_ioctl,
#endif
	.llseek		= default_llseek,
};

static struct miscdevice kloader_misc = {
	.minor	= MISC_DYNAMIC_MINOR,
	.name	= DRIVER_NAME,
	.fops	= &kloader_fops,
	.mode	= 0666,
};

static int __init kloader_init(void)
{
	int ret;

	pr_info(DRIVER_TAG ": loading driver v%s (%s) on %s\n",
		DRIVER_VERSION, DRIVER_DESC, utsname()->release);

	ret = misc_register(&kloader_misc);
	if (ret) {
		pr_err(DRIVER_TAG ": misc_register failed: %d\n", ret);
		return ret;
	}

	pr_info(DRIVER_TAG ": /dev/%s created, minor=%d. Kernel Loder ready.\n",
		DRIVER_NAME, kloader_misc.minor);
	return 0;
}

static void __exit kloader_exit(void)
{
	misc_deregister(&kloader_misc);
	pr_info(DRIVER_TAG ": driver unloaded, /dev/%s removed\n", DRIVER_NAME);
}

module_init(kloader_init);
module_exit(kloader_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("JUBAIR HOSEN");
MODULE_DESCRIPTION(DRIVER_DESC);
MODULE_VERSION(DRIVER_VERSION);
