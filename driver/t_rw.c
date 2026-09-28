typedef unsigned long u64;
typedef long s64;
typedef unsigned int u32;
typedef int s32;
typedef unsigned char u8;
typedef unsigned short u16;

#define AT_FDCWD (-100)
#define O_RDWR 2
#define SYS_openat 56
#define SYS_readlinkat 78
#define SYS_ioctl 29
#define SYS_getpid 172
#define SYS_write 64
#define SYS_exit_group 94
#define SYS_close 57

static long sc6(long n, long a, long b, long c, long d, long e, long f)
{
	register long x8 __asm__("x8") = n;
	register long x0 __asm__("x0") = a;
	register long x1 __asm__("x1") = b;
	register long x2 __asm__("x2") = c;
	register long x3 __asm__("x3") = d;
	register long x4 __asm__("x4") = e;
	register long x5 __asm__("x5") = f;
	__asm__ volatile ("svc #0"
		: "+r" (x0)
		: "r" (x8), "r" (x1), "r" (x2), "r" (x3), "r" (x4), "r" (x5)
		: "memory");
	return x0;
}

static long sc1(long n, long a) { return sc6(n, a, 0, 0, 0, 0, 0); }
static long sc3(long n, long a, long b, long c) { return sc6(n, a, b, c, 0, 0, 0); }

void *memcpy(void *d, const void *s, u64 n)
{
	u8 *a = (u8 *)d;
	const u8 *b = (const u8 *)s;
	while (n--)
		*a++ = *b++;
	return d;
}

static void putstr(const char *s)
{
	u64 n = 0;
	while (s[n])
		n++;
	sc3(SYS_write, 1, (long)s, n);
}

static void puthex(u64 v)
{
	char b[20];
	int i;
	b[0] = '0'; b[1] = 'x';
	for (i = 0; i < 16; i++) {
		int nyb = (v >> (60 - i * 4)) & 0xf;
		b[2 + i] = nyb < 10 ? '0' + nyb : 'a' + nyb - 10;
	}
	b[18] = '\n';
	sc3(SYS_write, 1, (long)b, 19);
}
__attribute__((unused)) static void (*keep_puthex)(u64) = puthex;

static void putkv(const char *label, u64 v)
{
	char b[64];
	u64 n = 0, i;
	while (label[n] && n < 40) {
		b[n] = label[n];
		n++;
	}
	b[n++] = ' ';
	b[n++] = '0';
	b[n++] = 'x';
	for (i = 0; i < 16; i++) {
		int nyb = (int)((v >> (60 - i * 4)) & 0xf);
		b[n + i] = nyb < 10 ? '0' + nyb : 'a' + nyb - 10;
	}
	b[n + 16] = '\n';
	sc3(SYS_write, 1, (long)b, n + 17);
}

#define FAM_UNKNOWN 0
#define FAM_RT      1
#define FAM_QX      2

static const char *famname(int f)
{
	if (f == FAM_RT)
		return "rt";
	if (f == FAM_QX)
		return "qx";
	return "unknown";
}

struct proc_rw { s32 pid; u32 pad; u64 addr; u64 buf; u64 size; };
struct modbase { s32 pid; u32 pad; u64 name_ptr; u64 base; };

static volatile u32 marker = 0x12345678;
static u8 tmpbuf[32];
static char namebuf[4096];

void _start(void);

__attribute__((naked)) void _start(void)
{
	__asm__ volatile (
		"mov x0, sp\n"
		"ldr x0, [x0]\n"	
		"mov x1, sp\n"
		"add x1, x1, #8\n"	
		"bl tmain\n"
		"mov x8, #94\n"		
		"svc #0\n"
		"1: b 1b\n"
	);
}

long tmain(long argc, char **argv)
{
	char *dev;
	long fd, pid, r;
	int fails = 0;
	int fam = FAM_UNKNOWN;
	struct proc_rw pr;
	struct modbase mb;

	if (argc < 2 || !argv[1]) {
		putstr("usage: t_rw /dev/<node>\n");
		return 99;
	}
	dev = argv[1];

	fd = sc6(SYS_openat, AT_FDCWD, (long)dev, O_RDWR, 0, 0, 0);
	if (fd < 0) {
		putkv("FAIL open", (u64)fd);
		sc1(SYS_exit_group, 98);
	}
	pid = sc1(SYS_getpid, 0);

	{
		int i;
		long r805, r804;

		for (i = 0; i < 32; i++)
			tmpbuf[i] = 0;
		r805 = sc3(SYS_ioctl, fd, 0x805, (long)tmpbuf);
		r804 = sc3(SYS_ioctl, fd, 0x804, 0);
		if (r805 == (long)-22 && r804 == (long)-22)
			fam = FAM_RT;
		else if (r805 == 0 && r804 == 2)
			fam = FAM_QX;

		if (fam == FAM_UNKNOWN) {
			putkv("FAIL abi", (u64)r805);
			fails++;
		}
	}

	{
		static char exepath[256];
		long rl, i, start = 0;
		rl = sc6(SYS_readlinkat, AT_FDCWD, (long)"/proc/self/exe",
			 (long)exepath, 255, 0, 0);
		if (rl <= 0 || rl >= 255) {
			putstr("FAIL modbase\n");
			fails++;
		} else {
			exepath[rl] = 0;
			for (i = 0; i < rl; i++)
				if (exepath[i] == '/')
					start = i + 1;
			for (i = 0; start + i < rl && i < 200; i++)
				namebuf[2048 + i] = exepath[start + i];
			namebuf[2048 + i] = 0;
			mb.pid = (s32)pid;
			mb.pad = 0;
			mb.name_ptr = (u64)&namebuf[2048];
			mb.base = 0;
			r = sc3(SYS_ioctl, fd, 0x803, (long)&mb);
			if (r == 0 && mb.base != 0) {
				
			} else {
				putstr("FAIL modbase\n");
				fails++;
			}
		}
	}

	pr.pid = (s32)pid;
	pr.pad = 0;
	pr.addr = (u64)&marker;
	pr.buf = (u64)tmpbuf;
	pr.size = 4;
	tmpbuf[0] = tmpbuf[1] = tmpbuf[2] = tmpbuf[3] = 0;
	r = sc3(SYS_ioctl, fd, 0x801, (long)&pr);
		if (r == 0 && *(u32 *)tmpbuf == 0x12345678) {
		
	} else {
		putkv("FAIL read got", (u64)*(u32 *)tmpbuf);
		fails++;
	}

	*(u32 *)tmpbuf = 0xAABBCCDD;
	pr.buf = (u64)tmpbuf;
	r = sc3(SYS_ioctl, fd, 0x802, (long)&pr);
		if (r == 0 && marker == 0xAABBCCDD) {
		
	} else {
		putkv("FAIL write marker", (u64)marker);
		fails++;
	}

	{
		u64 code = (u64)&putstr;
		code &= ~0xFFFUL;
		pr.addr = code;
		pr.buf = (u64)tmpbuf;
		pr.size = 4;
		r = sc3(SYS_ioctl, fd, 0x801, (long)&pr);
		if (r != 0) {
			putkv("FAIL xread", (u64)r);
			fails++;
		} else {
			r = sc3(SYS_ioctl, fd, 0x802, (long)&pr);
			if (r != 0) {
				putkv("FAIL xwrite", (u64)r);
				fails++;
			}
		}
	}

	if (fails == 0) {
		putstr("PASS ");
		putstr(famname(fam));
		putstr("\n");
	} else {
		putkv("FAIL count", (u64)fails);
	}
	return fails;
}
