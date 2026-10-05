# Kernel Loder v2.1-universal ⚡

**Universal Android kernel module (.ko) loader — any device, any model, old & new kernels.**

## Highlights

* 🌍 **Universal by design** — the app never checks brand/model/codename, only the kernel release.
* 🧠 **Vermagic-driven driver selection** — reads the *real* kernel release out of each `.ko`
  binary, so any file name (`native_*`, `qx_*`, `rt_*`, custom) works.
* ⚡ **AUTO LOAD (Universal)** — root check → kernel/arch/SELinux → best-match driver →
  vermagic check → auto-fix → insmod → verify, all in one tap.
* 🔧 **Auto-fix engine** — SELinux permissive, chmod/chcon, **binary vermagic patching**,
  busybox `insmod -f`, `sig_enforce` off; each fix applied only after diagnosing the real error.
* 🖥️ **Live terminal** — colour-coded `CMD / OK / ERR / FIX / WARN / OUT` stream + manual root shell.
* 📦 **40 bundled drivers** including new native builds built from source with proton-clang:
  * `native_4.9.337.ko` — vermagic `4.9.337-DaisyForGaming SMP preempt mod_unload modversions aarch64`
  * `native_4.9.307.ko` — vermagic `4.9.307-DaisyForGaming SMP preempt mod_unload modversions aarch64`
* 🧩 **Rename to Kernel Loder** — project, app label and credits are now device-agnostic;
  new portable driver source `driver/kloader_driver.c` (module `kloader_driver`, node `/dev/kloaderctl`).

## Install

1. Download `app-debug.apk` below.
2. Install (allow unknown sources) → open → grant **Superuser** (Magisk / KernelSU).
3. Accept the safety warning → tap **⚡ AUTO LOAD (Universal)**.

## Verify after loading

```bash
lsmod | grep -i kloader
ls -l /dev/kloaderctl
cat /dev/kloaderctl            # -> Kernel Loder 4.9.337 OK
dmesg | grep -i KernelLoder | tail -n 20
```

## Requirements

* Root: Magisk / KernelSU
* arm64 (aarch64), Android 9+ (API 28)

## ⚠️ Warning

Loading kernel modules as root can crash, reboot or brick a device. Force loading a module built
for a different kernel is unstable. Use at your own risk.

## Docs

* [`README.md`](../blob/main/README.md) — features, architecture, build
* [`docs/INSTALL_BANGLA.md`](../blob/main/docs/INSTALL_BANGLA.md) — বাংলা install/use guide
* [`docs/BUILD_DRIVER_BANGLA.md`](../blob/main/docs/BUILD_DRIVER_BANGLA.md) — driver build guide
* [`docs/FAQ_BANGLA.md`](../blob/main/docs/FAQ_BANGLA.md) — FAQ
* [`CHANGELOG.md`](../blob/main/CHANGELOG.md) — full history

**Full changelog:** https://github.com/bmjubairdadu/kernel-loader/blob/main/CHANGELOG.md