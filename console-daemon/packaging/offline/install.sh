#!/bin/sh
#
# FoxLoop Console's offline installer. Runs once, as root, early in the boot of
# a machine nobody can see or reach: prepare-boot.sh puts it on the boot
# partition with the .deb beside it, and adds a `systemd.run=` entry to
# cmdline.txt that boots the machine into this script instead of its normal
# targets. systemd-run-generator(8) does the rest; it is the same mechanism
# Raspberry Pi Imager's own first-boot script uses.
#
# No network is touched. Everything this prints goes to install.log next to it,
# on the boot partition, because that is the one place the person can read
# without a screen: put the card or SSD back in a computer and open it.
#
# Order matters, and the first step is why:
#
# 1. Disarm. cmdline.txt goes back to how it was *before* anything can fail.
#    The kernel line also says to reboot on failure, so a failed install that
#    left itself armed would reboot into itself forever -- on a machine with no
#    screen to show it happening.
# 2. Install the .deb with dpkg. Offline, so its dependencies (libusb-1.0-0,
#    openssh-server) must already be installed; on Raspberry Pi OS they are.
# 3. Make sure sshd will run, because the console is a pipe to it. If SSH was
#    off, it is turned on for loopback only, so installing a USB console does
#    not open SSH to the network as a side effect.
# 4. Optionally, root may log in with its password -- from loopback only.
#
# Then the machine reboots normally, and the phone can be plugged in.

set -u

kit=$(cd "$(dirname "$0")" && pwd)
boot=$(dirname "$kit")
log=$kit/install.log
exec >>"$log" 2>&1

echo
echo "=== $(date -u '+%Y-%m-%d %H:%M:%S UTC') foxloop-console offline install"

# --- 1. Disarm ---------------------------------------------------------------
if [ -f "$kit/cmdline.txt.orig" ]; then
    cp "$kit/cmdline.txt.orig" "$boot/cmdline.txt"
else
    # No backup: cut our own three words out instead, and nothing else.
    sed -i \
        -e 's| systemd\.run="/bin/sh [^"]*foxloop-console/install\.sh"||' \
        -e 's| systemd\.run_success_action=reboot||' \
        -e 's| systemd\.run_failure_action=reboot||' \
        "$boot/cmdline.txt"
fi
sync
if grep -q 'foxloop-console/install\.sh' "$boot/cmdline.txt"; then
    # Should be impossible, and is the one failure that would loop.
    echo "FAILED: could not take this script back out of $boot/cmdline.txt -- refusing to go on."
    exit 1
fi
echo "disarmed: $boot/cmdline.txt is back to how it was"

fail() {
    echo "FAILED: $*"
    echo "The machine will now reboot normally. Nothing else was changed after this point."
    exit 1
}

# --- 2. Install ----------------------------------------------------------------
deb=$(ls "$kit"/foxloop-console_*.deb 2>/dev/null | tail -n 1)
[ -n "$deb" ] || fail "no foxloop-console_*.deb in $kit"

want=$(dpkg --print-architecture)
have=$(dpkg-deb -f "$deb" Architecture)
[ "$have" = "$want" ] || fail "$(basename "$deb") is for $have, this machine is $want"

echo "installing $(basename "$deb")"
if ! dpkg -i "$deb"; then
    dpkg --purge foxloop-console >/dev/null 2>&1 || true
    fail "dpkg could not install it. If a dependency is named above, this machine is missing it, and being offline cannot fetch it: install that package (apt install ...) the next time it has a network, then run this again."
fi

# --- 3. sshd -------------------------------------------------------------------
enabled() { systemctl is-enabled --quiet "$1" 2>/dev/null; }

if [ ! -x /usr/sbin/sshd ]; then
    fail "openssh-server is not installed, and the console logs in through it"
fi

if enabled ssh.service || enabled ssh.socket || enabled sshd.service; then
    echo "sshd: already enabled, left exactly as it was"
else
    # A drop-in rather than an edit to sshd_config: the administrator's file
    # stays untouched, and removing this one file undoes it.
    printf '%s\n' \
        '# Written by the FoxLoop Console offline installer: sshd was off, and the' \
        '# console needs it on loopback only. Delete this file to listen everywhere.' \
        'ListenAddress 127.0.0.1' \
        'ListenAddress ::1' \
        > /etc/ssh/sshd_config.d/40-foxloop-console-loopback.conf
    systemctl enable ssh.service
    echo "sshd: was off; now enabled, listening on 127.0.0.1 and ::1 only"
fi

# Host keys are normally made on the first start, which may never have happened.
ssh-keygen -A

# --- 4. Root, if asked for -----------------------------------------------------
if [ -e "$kit/allow-root-login" ]; then
    printf '%s\n' \
        '# Written by the FoxLoop Console offline installer, because it was asked to.' \
        '# Root may log in with its password from this machine itself only, which is' \
        '# where the console connects from. From the network, nothing changes.' \
        'Match Address 127.0.0.1,::1' \
        '    PermitRootLogin yes' \
        > /etc/ssh/sshd_config.d/50-foxloop-console-root.conf
    echo "root: may now log in with its password, from loopback (the cable) only"
    case "$(passwd -S root 2>/dev/null | cut -d' ' -f2)" in
        P) ;;
        *) echo "WARNING: root has no usable password, so root still cannot log in. Set one, or log in as another user." ;;
    esac
fi

if ! /usr/sbin/sshd -t; then
    rm -f /etc/ssh/sshd_config.d/40-foxloop-console-loopback.conf \
          /etc/ssh/sshd_config.d/50-foxloop-console-root.conf
    fail "sshd rejected its configuration with the files above added; they have been removed again"
fi

# --- Done ----------------------------------------------------------------------
echo
echo "Installed. After this reboot, plug in the phone and unlock it."
echo "On the first login the app shows this machine's SSH host key. It should be one of:"
for k in /etc/ssh/ssh_host_*_key.pub; do
    ssh-keygen -lf "$k"
done
sync
exit 0
