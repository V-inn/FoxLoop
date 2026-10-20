#!/bin/sh
#
# Readies a Raspberry Pi's boot partition to install FoxLoop Console on its next
# boot, with no screen, no keyboard, no SSH and no network.
#
# Run on any Linux or macOS computer with the Pi's SD card or SSD plugged in:
#
#     sh prepare-boot.sh /path/to/bootfs foxloop-console_<version>_arm64.deb
#     sh prepare-boot.sh /path/to/bootfs foxloop-console_<version>_arm64.deb --allow-root-login
#
# /path/to/bootfs is the small FAT partition with cmdline.txt and config.txt in
# it -- usually mounted as "bootfs" (Raspberry Pi OS) or "RASPIFIRM" (Debian).
# It copies install.sh and the .deb into foxloop-console/ there, backs up
# cmdline.txt, and adds one entry to it that runs install.sh once at the next
# boot. install.sh puts cmdline.txt back before doing anything else, installs
# the package, makes sure sshd runs (on loopback only, if it was off), writes
# what happened to foxloop-console/install.log on the same partition, and
# reboots. On Windows, do the same by hand; console-daemon/README.md says how.
#
# --allow-root-login lets root log in with its password from loopback only --
# which means from the cable, and from anything already running on the Pi.
# Only for a machine where root is the account you have.
#
# Plain POSIX sh and tools on purpose: macOS has no GNU sed, and this must not
# depend on which one is installed.
set -eu

usage() {
    echo "usage: sh prepare-boot.sh BOOT_PARTITION FOXLOOP_CONSOLE_DEB [--allow-root-login]" >&2
    exit 2
}

[ $# -ge 2 ] || usage
boot=$1
deb=$2
allow_root=no
[ $# -ge 3 ] && { [ "$3" = "--allow-root-login" ] || usage; allow_root=yes; }

here=$(cd "$(dirname "$0")" && pwd)
# Where the Pi itself mounts this partition. Raspberry Pi OS from bookworm on,
# and Debian's Raspberry Pi images, both use /boot/firmware.
mount_point=${FOXLOOP_BOOT_MOUNT:-/boot/firmware}

die() { echo "prepare-boot.sh: $*" >&2; exit 1; }

[ -f "$boot/cmdline.txt" ] && [ -f "$boot/config.txt" ] ||
    die "$boot has no cmdline.txt and config.txt -- that is not a Raspberry Pi boot partition"
[ -f "$deb" ] || die "no such file: $deb"
case "$(basename "$deb")" in
    foxloop-console_*_arm64.deb) ;;
    foxloop-console_*.deb) die "$(basename "$deb") is not an arm64 package; a 64-bit Raspberry Pi needs the _arm64.deb" ;;
    *) die "$(basename "$deb") is not a foxloop-console package" ;;
esac
# install.sh in the repository, foxloop-console-install.sh as a release asset.
installer=
for name in install.sh foxloop-console-install.sh; do
    [ -f "$here/$name" ] && installer="$here/$name" && break
done
[ -n "$installer" ] || die "foxloop-console-install.sh (or install.sh) must sit next to this script"

kit="$boot/foxloop-console"
line=$(cat "$boot/cmdline.txt")
entry="systemd.run=\"/bin/sh $mount_point/foxloop-console/install.sh\" systemd.run_success_action=reboot systemd.run_failure_action=reboot"

case "$line" in
    *foxloop-console/install.sh*)
        echo "already armed: $boot/cmdline.txt runs the installer at the next boot. Refreshing the files only." ;;
    *systemd.run=*)
        # Another first-boot script is pending -- typically Raspberry Pi
        # Imager's, on a card that has never been booted. Both would run, and
        # each cleans cmdline.txt up its own way.
        die "cmdline.txt already runs another first-boot script (systemd.run=). Boot the Pi once normally first, then run this again." ;;
esac

mkdir -p "$kit"
rm -f "$kit"/foxloop-console_*.deb "$kit/install.log" "$kit/allow-root-login"
cp "$deb" "$kit/"
cp "$installer" "$kit/install.sh"
[ "$allow_root" = yes ] && : > "$kit/allow-root-login"

case "$line" in
    *foxloop-console/install.sh*) ;;
    *)
        cp "$boot/cmdline.txt" "$kit/cmdline.txt.orig"
        # cmdline.txt is one line, and must stay one line.
        printf '%s %s\n' "$(printf '%s' "$line" | tr -d '\r\n')" "$entry" > "$boot/cmdline.txt"
        ;;
esac
sync

cat <<EOF

Ready. On its next boot the Pi installs FoxLoop Console, then reboots by itself.

  1. Eject the partition properly, put the card or SSD back, power the Pi on.
  2. Give it a couple of minutes: one boot to install, one more to come back.
  3. Plug in the phone, unlock it; FoxLoop Console opens.

If the phone never asks, put the card back in this computer and read
  $kit/install.log
EOF
[ "$allow_root" = yes ] && cat <<'EOF'

--allow-root-login: root may log in with its password, from loopback only. Root
needs a password set for that; the log warns if it has none.
EOF
exit 0
