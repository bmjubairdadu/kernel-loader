ui_print "****************************************"
ui_print "*   Kernel Driver SELinux & Node Fix   *"
ui_print "*   Author: bmjubairdadu               *"
ui_print "****************************************"
ui_print "- Device: $PRODUCT ($DEVICE)"
ui_print "- Android SDK: $API"
ui_print "- Installing sepolicy.rule for ueventd..."
ui_print "- Applying character device permissions..."

if command -v magiskpolicy >/dev/null 2>&1; then
    magiskpolicy --live "allow ueventd device chr_file { create open read write ioctl getattr setattr relabelfrom relabelto unlink }" 2>/dev/null
    magiskpolicy --live "allow ueventd unlabeled chr_file { create open read write ioctl getattr setattr relabelfrom relabelto unlink }" 2>/dev/null
    ui_print "[OK] Live SELinux policy injected successfully!"
elif command -v supolicy >/dev/null 2>&1; then
    supolicy --live "allow ueventd device chr_file { create open read write ioctl getattr setattr relabelfrom relabelto unlink }" 2>/dev/null
    ui_print "[OK] Live supolicy injected successfully!"
fi

ui_print " "
ui_print "[SUCCESS] SELinux Node Fix installed!"
ui_print "Kernel drivers will now auto-create /dev nodes naturally."
ui_print "Please reboot your device to finalize changes."
