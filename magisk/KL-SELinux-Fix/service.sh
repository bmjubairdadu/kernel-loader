#!/system/bin/sh
MODDIR=${0%/*}

sleep 2
if command -v magiskpolicy >/dev/null 2>&1; then
    magiskpolicy --live "allow ueventd device chr_file { create open read write ioctl getattr setattr relabelfrom relabelto unlink }" 2>/dev/null
    magiskpolicy --live "allow ueventd unlabeled chr_file { create open read write ioctl getattr setattr relabelfrom relabelto unlink }" 2>/dev/null
elif command -v supolicy >/dev/null 2>&1; then
    supolicy --live "allow ueventd device chr_file { create open read write ioctl getattr setattr relabelfrom relabelto unlink }" 2>/dev/null
fi
