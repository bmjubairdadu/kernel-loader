#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define KALLSYMS_PATH "/proc/kallsyms"
#define KCORE_PATH "/proc/kcore"
#define KCRCTAB_PREFIX "__kcrctab_"
#define KSYMTAB_PREFIX "__ksymtab_"
#define KSYMTAB_GPL_PREFIX "__ksymtab_gpl_"

struct strset {
    char **items;
    size_t count;
    size_t cap;
};

static void strset_add(struct strset *s, const char *v) {
    if (s->count == s->cap) {
        size_t ncap = s->cap ? s->cap * 2 : 256;
        char **ni = realloc(s->items, ncap * sizeof(*ni));
        if (!ni) {
            fprintf(stderr, "out of memory\n");
            exit(1);
        }
        s->items = ni;
        s->cap = ncap;
    }
    s->items[s->count] = strdup(v);
    if (!s->items[s->count]) {
        fprintf(stderr, "out of memory\n");
        exit(1);
    }
    s->count++;
}

static int strset_has(const struct strset *s, const char *v) {
    size_t i;
    for (i = 0; i < s->count; i++) {
        if (strcmp(s->items[i], v) == 0)
            return 1;
    }
    return 0;
}

struct crc_entry {
    char *name;
    unsigned long long addr;
};

struct crclist {
    struct crc_entry *items;
    size_t count;
    size_t cap;
};

static void crclist_add(struct crclist *l, const char *name, unsigned long long addr) {
    if (l->count == l->cap) {
        size_t ncap = l->cap ? l->cap * 2 : 1024;
        struct crc_entry *ni = realloc(l->items, ncap * sizeof(*ni));
        if (!ni) {
            fprintf(stderr, "out of memory\n");
            exit(1);
        }
        l->items = ni;
        l->cap = ncap;
    }
    l->items[l->count].name = strdup(name);
    l->items[l->count].addr = addr;
    if (!l->items[l->count].name) {
        fprintf(stderr, "out of memory\n");
        exit(1);
    }
    l->count++;
}

static ssize_t pread_all(int fd, void *buf, size_t n, unsigned long long off) {
    size_t got = 0;
    while (got < n) {
        ssize_t r = pread(fd, (char *)buf + got, n - got, (off_t)off + got);
        if (r < 0)
            return -1;
        if (r == 0)
            break;
        got += (size_t)r;
    }
    return (ssize_t)got;
}

struct segment {
    unsigned long long vaddr;
    unsigned long long memsz;
    unsigned long long offset;
};

int main(int argc, char **argv) {
    FILE *kf;
    FILE *out = stdout;
    char *line = NULL;
    size_t llen = 0;
    ssize_t len;
    struct crclist crcs = {0};
    struct strset gpl = {0};
    struct segment *segs = NULL;
    size_t nsegs = 0, segcap = 0;
    int kcore_fd;
    Elf64_Ehdr ehdr;
    size_t i, j;
    size_t written = 0;
    size_t gpl_written = 0;
    size_t skipped = 0;
    unsigned long nlines = 0;

    setvbuf(stderr, NULL, _IONBF, 0);
    fprintf(stderr, "dbg: start\n");

    if (argc > 2) {
        fprintf(stderr, "usage: kcrc_dump [output-file]\n");
        return 1;
    }
    {
        FILE *rf = fopen("/proc/sys/kernel/kptr_restrict", "r");
        if (rf) {
            char rb[16];
            if (fgets(rb, sizeof(rb), rf)) {
                if (rb[0] != '0') {
                    FILE *wf = fopen("/proc/sys/kernel/kptr_restrict", "w");
                    if (wf) {
                        fputs("0\n", wf);
                        fclose(wf);
                        fprintf(stderr, "dbg: kptr_restrict lowered\n");
                    } else {
                        fprintf(stderr, "dbg: kptr_restrict locked, addresses may stay hidden\n");
                    }
                } else {
                    fprintf(stderr, "dbg: kptr_restrict already 0\n");
                }
            }
            fclose(rf);
        }
    }
    if (argc == 2) {
        out = fopen(argv[1], "w");
        if (!out) {
            fprintf(stderr, "cannot open output: %s\n", argv[1]);
            return 1;
        }
    }

    kf = fopen(KALLSYMS_PATH, "r");
    if (!kf) {
        fprintf(stderr, "cannot open %s: run as root, KALLSYMS required\n", KALLSYMS_PATH);
        return 2;
    }
    fprintf(stderr, "dbg: kallsyms open ok\n");
    while ((len = getline(&line, &llen, kf)) >= 0) {
        unsigned long long addr;
        char *name;
        nlines++;
        if (len < 19)
            continue;
        addr = strtoull(line, NULL, 16);
        if (addr == 0) {
            skipped++;
            continue;
        }
        name = strchr(line, ' ');
        if (!name)
            continue;
        name = strchr(name + 1, ' ');
        if (!name)
            continue;
        name++;
        name[strcspn(name, " \t\r\n")] = '\0';
        if (strncmp(name, KCRCTAB_PREFIX, strlen(KCRCTAB_PREFIX)) == 0) {
            crclist_add(&crcs, name + strlen(KCRCTAB_PREFIX), addr);
        } else if (strncmp(name, KSYMTAB_GPL_PREFIX, strlen(KSYMTAB_GPL_PREFIX)) == 0) {
            strset_add(&gpl, name + strlen(KSYMTAB_GPL_PREFIX));
        }
    }
    fclose(kf);
    free(line);
    fprintf(stderr, "dbg: kallsyms done lines=%lu kcrctab=%lu gpl=%lu zeroaddr=%lu\n",
            nlines, (unsigned long)crcs.count, (unsigned long)gpl.count, (unsigned long)skipped);

    if (crcs.count == 0) {
        fprintf(stderr, "no __kcrctab_ entries: kernel has no MODVERSIONS or kallsyms hidden\n");
        return 4;
    }

    kcore_fd = open(KCORE_PATH, O_RDONLY);
    if (kcore_fd < 0) {
        fprintf(stderr, "cannot open %s: run as root\n", KCORE_PATH);
        return 3;
    }
    fprintf(stderr, "dbg: kcore open ok\n");
    if (pread_all(kcore_fd, &ehdr, sizeof(ehdr), 0) != (ssize_t)sizeof(ehdr) ||
        memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr.e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr.e_ident[EI_DATA] != ELFDATA2LSB) {
        fprintf(stderr, "unsupported kcore format: need 64-bit LE ELF\n");
        return 3;
    }
    fprintf(stderr, "dbg: ehdr phoff=%llu phnum=%u phentsize=%u\n",
            (unsigned long long)ehdr.e_phoff, ehdr.e_phnum, ehdr.e_phentsize);
    if (ehdr.e_phnum > 4096 || ehdr.e_phentsize < sizeof(Elf64_Phdr)) {
        fprintf(stderr, "insane phdr table: refusing to walk it\n");
        return 3;
    }
    for (i = 0; i < ehdr.e_phnum; i++) {
        Elf64_Phdr ph;
        if (pread_all(kcore_fd, &ph, sizeof(ph),
                      ehdr.e_phoff + i * ehdr.e_phentsize) != (ssize_t)sizeof(ph))
            break;
        if (ph.p_type != PT_LOAD || ph.p_memsz == 0)
            continue;
        if (nsegs == segcap) {
            size_t ncap = segcap ? segcap * 2 : 16;
            struct segment *ns = realloc(segs, ncap * sizeof(*ns));
            if (!ns) {
                fprintf(stderr, "out of memory\n");
                return 1;
            }
            segs = ns;
            segcap = ncap;
        }
        segs[nsegs].vaddr = ph.p_vaddr;
        segs[nsegs].memsz = ph.p_memsz;
        segs[nsegs].offset = ph.p_offset;
        nsegs++;
    }
    if (nsegs == 0) {
        fprintf(stderr, "no load segments in kcore\n");
        return 3;
    }
    fprintf(stderr, "dbg: segs=%lu\n", (unsigned long)nsegs);

    {
        FILE *vf = fopen("/proc/version", "r");
        if (vf) {
            char vb[512];
            if (fgets(vb, sizeof(vb), vf)) {
                vb[strcspn(vb, "\r\n")] = '\0';
                fprintf(stderr, "uname: %s\n", vb);
            }
            fclose(vf);
        }
    }

    for (i = 0; i < crcs.count; i++) {
        unsigned long long fileoff = 0;
        int found = 0;
        unsigned long long v = 0;
        unsigned int crc32;
        const char *flavor;
        if ((i % 500) == 0)
            fprintf(stderr, "dbg: read %lu/%lu\n", (unsigned long)i, (unsigned long)crcs.count);
        for (j = 0; j < nsegs; j++) {
            if (crcs.items[i].addr >= segs[j].vaddr &&
                crcs.items[i].addr + 8 <= segs[j].vaddr + segs[j].memsz) {
                fileoff = segs[j].offset + (crcs.items[i].addr - segs[j].vaddr);
                found = 1;
                break;
            }
        }
        if (!found)
            continue;
        if (pread_all(kcore_fd, &v, 8, fileoff) != 8)
            continue;
        crc32 = (unsigned int)(v & 0xffffffffu);
        if (strset_has(&gpl, crcs.items[i].name)) {
            flavor = "EXPORT_SYMBOL_GPL";
            gpl_written++;
        } else {
            flavor = "EXPORT_SYMBOL";
        }
        fprintf(out, "0x%08x\t%s\tvmlinux\t%s\n", crc32, crcs.items[i].name, flavor);
        written++;
    }
    if (out != stdout)
        fclose(out);
    fprintf(stderr, "entries=%lu gpl=%lu kcrctab=%lu\n",
            (unsigned long)written, (unsigned long)gpl_written,
            (unsigned long)crcs.count);
    return written ? 0 : 4;
}
