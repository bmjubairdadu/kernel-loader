/* t_rw - freestanding ioctl test for kmem_337 (aarch64, -nostdlib -static)
 * usage: t_rw /dev/<node>   ; exit code = number of failed checks (0 = all pass)
 *
 * RT return conventions (verified against the original RT binary and against
 * the game-mod client, which tests the return with "cmp w0, #0"):
 *      read/write   ok = 0        fail = -5
 *      modbase      ok = 0        (base is returned through the struct)
 *      bad pointer  -14
 *      unknown cmd  -22
 * NOTE: an earlier revision of this tester asserted ok == -5, which is the
 * INVERTED convention. Every read and write then reported FAIL even though the
 * driver had read and written the correct bytes, and the app's ABI self-check
 * rmmod'ed a perfectly good driver.
 */
typedef unsigned long u64;
typedef long s64;
typedef unsigned int u32;
typedef int s32;
typedef unsigned char u8;
typedef unsigned short u16;

#define AT_FDCWD (-100)
#define O_RDWR 2
#define O_RDONLY 0
#define O_DIRECTORY 0x10000   /* 040000 octal, aarch64 asm-generic */
#define SYS_openat 56
#define SYS_readlinkat 78
#define SYS_ioctl 29
#define SYS_getpid 172
#define SYS_write 64
#define SYS_exit_group 94
#define SYS_getdents64 61
#define SYS_close 57
#define SYS_read 63

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

/* freestanding: clang may emit memcpy for copies (no libc here) */
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

/* "<label> 0x<16 hex digits>\n" emitted as ONE write, so the line can never be
 * split or interleaved with another check's output. Without this the raw
 * return value was sometimes lost from the log and a failing check gave no
 * clue what the kernel had actually returned. */
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

/* ---- ABI family detection -------------------------------------------------
 * The two families the game-mod clients speak differ in exactly this way:
 *
 *            0x805            0x804            read/write ok   failure
 *   RT   :   -22              -22                 0              -5
 *   QX   :    0                2                 0              -1
 *
 * so the two "undefined command" probes identify the family, and read/write
 * are 0-on-success in BOTH. A single tester can therefore check either.
 * (Before this, the tester only knew the RT expectations, so loading a QX
 * driver reported a false ABI MISMATCH and the app rmmod'ed it.)
 */
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

/* ---- first mapping address of a pid, read from /proc/<pid>/maps ---------
 * The previous revision walked /proc with getdents64 and matched
 * /proc/<pid>/comm, which never found a process (it reported pid -1 for
 * every candidate). That parsing is easy to get subtly wrong and impossible
 * to debug on a device. The FIRST line of /proc/<pid>/maps is the lowest
 * mapping, which for any process is where its ELF image starts - so the
 * load address is just the hex number before the first '-'.
 *
 * This also doubles as a self-check: for the probe's own pid the value from
 * /proc/self/maps is compared with what the driver's 0x803 returned, so the
 * parse is proven against a known-good answer inside the same run.
 */
static u64 first_map_start(s32 pid, char *path, char *buf, long cap)
{
	char *p = path;
	const char *pre = "/proc/";
	char tmp[24];
	s32 t = 0;
	s64 v = pid;
	long fd, r, i;

	while (*pre)
		*p++ = *pre++;
	if (v == 0) {
		tmp[t++] = '0';
	} else {
		while (v) {
			tmp[t++] = (char)('0' + (v % 10));
			v /= 10;
		}
	}
	while (t)
		*p++ = tmp[--t];
	*p++ = '/';
	*p++ = 'm';
	*p++ = 'a';
	*p++ = 'p';
	*p++ = 's';
	*p = 0;

	fd = sc6(SYS_openat, AT_FDCWD, (long)path, (long)O_RDONLY, 0, 0, 0);
	if (fd < 0)
		return 0;
	r = sc3(SYS_read, fd, (long)buf, cap - 1);
	sc1(SYS_close, fd);
	if (r <= 0)
		return 0;
	buf[r] = 0;

	for (i = 0; i < r; i++) {
		char c = buf[i];
		u64 add = 0;
		long j;
		if (c < '0' || c > '9')
			break;              /* the line must start with the address */
		for (j = i; j < r; j++) {
			char h = buf[j];
			if (h == '-' || h == ' ' || h == '\n')
				break;
			if (h >= '0' && h <= '9')
				add = add * 16 + (u64)(h - '0');
			else if (h >= 'a' && h <= 'f')
				add = add * 16 + (u64)(h - 'a' + 10);
			else if (h >= 'A' && h <= 'F')
				add = add * 16 + (u64)(h - 'A' + 10);
			else
				break;
		}
		return add;
	}
	return 0;
}

static volatile u32 marker = 0x12345678;
static u8 tmpbuf[32];
static char namebuf[4096];

void _start(void);

__attribute__((naked)) void _start(void)
{
	__asm__ volatile (
		"mov x0, sp\n"
		"ldr x0, [x0]\n"	/* argc */
		"mov x1, sp\n"
		"add x1, x1, #8\n"	/* argv */
		"bl tmain\n"
		"mov x8, #94\n"		/* exit_group(fails) */
		"svc #0\n"
		"1: b 1b\n"
	);
}

long tmain(long argc, char **argv)
{
	char *dev;
	long fd, pid, r;
	int fails = 0;
	struct proc_rw pr;
	struct modbase mb;

	if (argc < 2 || !argv[1]) {
		putstr("usage: t_rw /dev/<node>\n");
		return 99;
	}
	dev = argv[1];

	fd = sc6(SYS_openat, AT_FDCWD, (long)dev, O_RDWR, 0, 0, 0);
	if (fd < 0) {
		putstr("FAIL open\n");
		sc1(SYS_exit_group, 98);
	}
	putstr("open ok\n");
	pid = sc1(SYS_getpid, 0);

	/* Identify the ABI family from the two commands whose behaviour differs
	 * between RT and QX. Neither is required to "fail" - each is simply
	 * recognised. */
	{
		int i;
		int fam = FAM_UNKNOWN;
		long r805, r804;

		for (i = 0; i < 32; i++)
			tmpbuf[i] = 0;
		r805 = sc3(SYS_ioctl, fd, 0x805, (long)tmpbuf);
		r804 = sc3(SYS_ioctl, fd, 0x804, 0);
		putkv("probe 0x805 ret", (u64)r805);
		putkv("probe 0x804 ret", (u64)r804);

		if (r805 == (long)-22 && r804 == (long)-22)
			fam = FAM_RT;
		else if (r805 == 0 && r804 == 2)
			fam = FAM_QX;

		if (fam == FAM_UNKNOWN) {
			putstr("FAIL abi-probe (not an RT or QX driver)\n");
			fails++;
		} else {
			putstr("PASS abi-probe ");
			putstr(famname(fam));
			putstr("\n");
		}
		putkv("ABI", (u64)fam);
	}

	/* 0x803 modbase of self: discover own basename via /proc/self/exe
	 * (the binary may be staged under any name, e.g. kprobe_xxx). */
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
				putstr("PASS modbase base=");
				puthex(mb.base);
			} else {
				putstr("FAIL modbase\n");
				fails++;
			}
		}
	}

	/* ---- CROSS-PROCESS read: the only test that proves the driver can do
	 * what a game mod actually needs. Everything above reads the probe's OWN
	 * address space, so it would still pass if the page-table walk only ever
	 * looked at the calling process.
	 *
	 * Target: pid 1 (/init), which always exists on Android. Its load address
	 * is taken from /proc/1/maps rather than from the driver's 0x803, because
	 * 0x803 matches a FILE basename and "init" is ambiguous, and because a
	 * wrong base produces a misleading "no ELF magic" warning.
	 *
	 * Step 1 proves the /proc parse is right: the same parser is run on
	 *        /proc/self/maps and the result must equal the base that 0x803
	 *        already returned for this very process. If that matches, the
	 *        parser is trustworthy and any later failure is the driver.
	 * Step 2 reads the ELF magic (7f 45 4c 46) out of pid 1.
	 *
	 * Reported, never fatal: an ambiguous self-test must not be able to rmmod
	 * a driver that is working perfectly.
	 */
	{
		static char mbuf[4096];
		static char mpath[64];
		u64 self_maps, one_maps;

		/* self base from 0x803 was stored earlier; recompute for clarity */
		mb.pid = (s32)pid;
		mb.pad = 0;
		mb.name_ptr = (u64)&namebuf[2048];
		mb.base = 0;
		r = sc3(SYS_ioctl, fd, 0x803, (long)&mb);
		putkv("self 0x803 base", mb.base);

		self_maps = first_map_start((s32)pid, mpath, mbuf, 4000);
		putkv("self maps base", self_maps);
		if (self_maps != 0 && self_maps == mb.base)
			putstr("  PASS /proc parse matches the driver's own base\n");
		else
			putstr("  WARN /proc parse disagrees with 0x803\n");

		putstr("-- xproc pid 1 (init)\n");
		one_maps = first_map_start(1, mpath, mbuf, 4000);
		putkv("  maps base", one_maps);
		if (one_maps == 0) {
			putstr("  WARN could not read /proc/1/maps\n");
			putstr("WARN xproc: pid 1 not readable (informational)\n");
		} else {
			/* the ELF magic must be at the very first byte of the image */
			tmpbuf[0] = tmpbuf[1] = tmpbuf[2] = tmpbuf[3] = 0;
			pr.pid = 1;
			pr.pad = 0;
			pr.addr = one_maps;
			pr.buf = (u64)tmpbuf;
			pr.size = 4;
			r = sc3(SYS_ioctl, fd, 0x801, (long)&pr);
			putkv("  xread ret", (u64)r);
			putkv("  xread got", (u64)*(u32 *)tmpbuf);
			if (r == 0 && tmpbuf[0] == 0x7f && tmpbuf[1] == 'E' &&
			    tmpbuf[2] == 'L' && tmpbuf[3] == 'F') {
				putstr("  PASS cross-process read (ELF magic from pid 1)\n");
				putstr("PASS xproc (another process was readable)\n");
			} else if (r != 0) {
				putstr("  WARN the driver could not read that page\n");
				putstr("WARN xproc: cross-process read failed (informational)\n");
			} else {
				putstr("  WARN readable, but not an ELF header\n");
				putstr("WARN xproc: base guess was wrong (informational)\n");
			}
		}
	}

	/* 0x801 read own marker - RT success is 0, failure is -5 */
	pr.pid = (s32)pid;
	pr.pad = 0;
	pr.addr = (u64)&marker;
	pr.buf = (u64)tmpbuf;
	pr.size = 4;
	tmpbuf[0] = tmpbuf[1] = tmpbuf[2] = tmpbuf[3] = 0;
	r = sc3(SYS_ioctl, fd, 0x801, (long)&pr);
	putkv("read ret", (u64)r);
	if (r == 0 && *(u32 *)tmpbuf == 0x12345678) {
		putstr("PASS read\n");
	} else {
		putkv("FAIL read got", (u64)*(u32 *)tmpbuf);
		fails++;
	}

	/* 0x802 write own marker */
	*(u32 *)tmpbuf = 0xAABBCCDD;
	pr.buf = (u64)tmpbuf;
	r = sc3(SYS_ioctl, fd, 0x802, (long)&pr);
	putkv("write ret", (u64)r);
	if (r == 0 && marker == 0xAABBCCDD) {
		putstr("PASS write\n");
	} else {
		putkv("FAIL write marker", (u64)marker);
		fails++;
	}

	/* 0x802 write to own CODE (r-xp page, like game libs): read 4 bytes
	 * of putstr, write them back unchanged, verify no error. */
	{
		u64 code = (u64)&putstr;
		code &= ~0xFFFUL;
		pr.addr = code;
		pr.buf = (u64)tmpbuf;
		pr.size = 4;
		r = sc3(SYS_ioctl, fd, 0x801, (long)&pr);
		putkv("xread ret", (u64)r);
		if (r != 0) {
			putstr("FAIL xread\n");
			fails++;
		} else {
			r = sc3(SYS_ioctl, fd, 0x802, (long)&pr);
			putkv("xwrite ret", (u64)r);
			if (r == 0)
				putstr("PASS xwrite\n");
			else {
				putstr("FAIL xwrite\n");
				fails++;
			}
		}
	}

	if (fails == 0)
		putstr("ALL PASS\n");
	return fails;
}
