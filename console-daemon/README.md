# FoxLoop Console daemon

The machine half of FoxLoop Console: plug an Android phone into a headless
machine — a Raspberry Pi with no screen — and get its shell on the phone, over
the USB cable, with no network at all.

The daemon is a dumb, framed pipe. It switches the phone into Android Open
Accessory mode, waits for the FoxLoop Console app to say HELLO, opens one TCP
connection to the machine's own sshd on `127.0.0.1:22`, and moves bytes. The app
runs a real SSH client over that pipe, so sshd checks the password, opens the
PTY and enforces its own rate limits. The daemon parses nothing above its frames
and needs no root.

The wire protocol is documented in [`src/frame.rs`](src/frame.rs); the app's
half is `android-client/app/src/console/.../ConsoleProtocol.kt`.

## Install on the machine

The package is `foxloop-console_<version>_arm64.deb` for a 64-bit Raspberry Pi
(and `_amd64.deb` for a PC). Built on Debian 12, it installs on Debian 12 and 13,
Raspberry Pi OS included. On a machine you can already reach:

```sh
sudo apt install ./foxloop-console_*_arm64.deb
```

On a machine you **cannot** reach — no screen, no SSH, no network — see the next
section: that is the case this project is for.

To build it yourself, `packaging/build-deb.sh` builds in a clean Debian 12
container, and `packaging/build-deb.sh arm64` cross-compiles the Pi's package
from a PC, no qemu needed. Without docker, on the machine itself:

```sh
sudo apt install build-essential pkg-config libusb-1.0-0-dev curl
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
cargo install --locked cargo-deb@3.7.0
cd FoxLoop/console-daemon && cargo deb
```

The package installs:

| File | Why |
|---|---|
| `/usr/bin/foxloop-console` | the daemon |
| `/usr/lib/systemd/system/foxloop-console.service` | a *system* unit: a headless machine has no user session to start a `--user` one in |
| `/usr/lib/udev/rules.d/70-foxloop-console.rules` | gives the `foxloop` user Samsung and Google USB devices — never storage, never hubs — and starts the unit on plug |
| `/usr/lib/sysusers.d/foxloop-console.conf` | the `foxloop` system user the unit runs as |

sshd has to be running and has to allow password logins for the account you will
use. Raspberry Pi OS allows them by default once SSH is enabled; the offline
installer below enables it for you, on loopback only.

## Install with no screen, no SSH and no network

The phone cannot deliver the package by itself: until `foxloop-console` is on the
Pi, the Pi ignores a phone on the cable, and Android cannot pose as a USB stick or
a keyboard without root. What every Pi does have is a small FAT boot partition
that any computer can write. So the package goes there, with a one-shot installer,
and the next boot runs it — the same mechanism Raspberry Pi Imager uses for its
own first-boot setup (`systemd.run=` in `cmdline.txt`, see
systemd-run-generator(8)).

You need the arm64 `.deb`, `foxloop-console-install.sh` and
`foxloop-console-prepare-boot.sh` from the release, and the Pi's SD card or SSD in
another computer.

**Linux or macOS:**

```sh
sh foxloop-console-prepare-boot.sh /path/to/bootfs foxloop-console_*_arm64.deb
```

`/path/to/bootfs` is the partition holding `cmdline.txt` and `config.txt`,
usually mounted as `bootfs` (Raspberry Pi OS) or `RASPIFIRM` (Debian). Add
`--allow-root-login` if root is the only account you have (see below).

**Windows**, by hand:

1. On the boot partition, make a folder `foxloop-console` and put the `.deb` in it,
   and `foxloop-console-install.sh` renamed to `install.sh`.
2. Copy `cmdline.txt` into that folder as `cmdline.txt.orig` — the installer puts
   it back from there.
3. Open `cmdline.txt` (one line — keep it one line) and add to the end, after a
   space:
   `systemd.run="/bin/sh /boot/firmware/foxloop-console/install.sh" systemd.run_success_action=reboot systemd.run_failure_action=reboot`
4. For root logins, also create an empty file `allow-root-login` in the folder.

Then put the card or SSD back and power the Pi on. It boots once into the
installer, which, in this order:

1. **puts `cmdline.txt` back** first, so nothing it does next can make it run
   again;
2. installs the package with `dpkg`, offline — its dependencies (`libusb-1.0-0`,
   `openssh-server`) are already on Raspberry Pi OS;
3. makes sure sshd will run. If SSH was off, it is turned on **listening on
   127.0.0.1 only**, through a drop-in in `/etc/ssh/sshd_config.d/`, so installing
   a USB console does not open SSH to your network. If it was on, it is left alone;
4. with `--allow-root-login`, lets root log in with its password **from loopback
   only** — the cable, and anything already running on the Pi. From the network,
   root stays key-only. Root needs a password for this; the log warns if it has
   none.

and reboots normally. Plug in the phone. Everything the installer did, including
the machine's SSH host key fingerprints for the app's first-login prompt, is in
`foxloop-console/install.log` on the boot partition — readable from any computer,
which is the point: there is no screen to show it.

Do this on a Pi that has booted at least once. A freshly flashed card still has
its own first-boot setup pending, and the helper refuses rather than race it.

## Use

Install FoxLoop Console on the phone, plug it in, unlock it. Android opens the app
by itself. Log in with the machine's user name and password. The first time, the
app shows the machine's SSH host key fingerprint to accept; compare it with

```sh
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

on the machine. A different key later is refused.

## Dashboards

While logged in, **Dashboards** (top right) lists web pages served on the
machine — Uptime Kuma, Pi-hole, anything on HTTP — saved per machine as a name,
a port and a path. Opening one starts an SSH local port forward on the
terminal's own session and loads it in a WebView: behind the same login, over
the same cable, no network involved.

The daemon does nothing new for this; sshd carries the forwards. So sshd must
allow them (`AllowTcpForwarding`, on by default), and the dashboard must listen
on the machine itself — on `127.0.0.1` or all addresses. A Docker container's
port has to be published to the host (`-p 3001:3001`); one reachable only inside
a Docker network is not on the machine's loopback. Plain HTTP only, and an app
configured with a public "base URL" that redirects there will be stopped — leave
that setting empty.

## Run it by hand

```sh
cargo build --release
./target/release/foxloop-console              # relays to 127.0.0.1:22
./target/release/foxloop-console 127.0.0.1:2222
```

It exits 0 when no phone is found within 20 s — udev starts it on the next plug.

### On a laptop that also runs the display daemon

Both daemons switch any Samsung device they find into accessory mode, and a
device already in accessory mode is reused by whichever daemon asks next: nothing
on the bus says which identity it was switched under. So for console testing,
keep the display daemon out of the way — its udev rule starts it on every
Samsung plug, including the re-enumerations the switch itself causes.

`systemctl --user mask --runtime` does **not** do it when the display unit was
installed by `install.sh`: that puts the unit in `~/.config/systemd/user`, which
outranks the `/run` directory a runtime mask is written to, so the mask is
silently ignored. A runtime drop-in works, because drop-ins are merged from every
directory:

```sh
d=$XDG_RUNTIME_DIR/systemd/user/foxloop-daemon.service.d
mkdir -p $d && printf '[Unit]\nConditionPathExists=/nonexistent\n' > $d/50-console-testing.conf
systemctl --user daemon-reload && systemctl --user stop foxloop-daemon
# undo (or reboot):
rm -r $d && systemctl --user daemon-reload && systemctl --user reset-failed foxloop-daemon
```

A device last used with the display has to leave accessory mode before the
console daemon starts, or it stays routed to the display app. Replug it, or
without touching the cable:

```sh
adb shell svc usb setFunctions mtp
```

Give it a few seconds before starting the console daemon. A switch request sent
while Android is still applying that function change is accepted and ignored;
the daemon now asks again (up to three times) when that happens.

## Tests

```sh
cargo test                  # frame codec, status parsing, the session loop end to end
cargo test -- --ignored     # real OpenSSH through the pipe; needs sshd on :22 and ssh
```

The session tests need no hardware: a TCP pair stands in for the cable and a
loopback listener for sshd. The ignored one drives the real `ssh` client through
the framing against the machine's own sshd and checks that key exchange completes.

## Status and power

Once a second the daemon sends the CPU temperature
(`/sys/class/thermal/thermal_zone0/temp`) and the Pi's throttled flags (from
`/sys/devices/platform/soc/soc:firmware/get_throttled`, falling back to
`vcgencmd get_throttled`), and the app shows them in its status line. A phone draws from the same USB budget as the Pi's disk — the Pi 4's
ports share 1.2 A — so check `vcgencmd get_throttled` before relying on this on
a machine whose SSD has been dropping out: `0x0` is clean.
