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

There is no published package yet. Build one where it will run — `$auto`
dependency resolution reads the build machine, so a package for Raspberry Pi OS
(bookworm) is built on bookworm:

```sh
# on the Pi
sudo apt install build-essential pkg-config libusb-1.0-0-dev curl
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
cargo install --locked cargo-deb@3.7.0
cd FoxLoop/console-daemon && cargo deb
sudo apt install ./target/debian/foxloop-console_*.deb
```

Or, on any machine with docker, `packaging/build-deb.sh` does the same in a
clean Debian 12 container for docker's own architecture.

The package installs:

| File | Why |
|---|---|
| `/usr/bin/foxloop-console` | the daemon |
| `/usr/lib/systemd/system/foxloop-console.service` | a *system* unit: a headless machine has no user session to start a `--user` one in |
| `/usr/lib/udev/rules.d/70-foxloop-console.rules` | gives the `foxloop` user Samsung and Google USB devices — never storage, never hubs — and starts the unit on plug |
| `/usr/lib/sysusers.d/foxloop-console.conf` | the `foxloop` system user the unit runs as |

sshd has to be running and has to allow password logins for the account you will
use. Raspberry Pi OS does both by default once SSH is enabled.

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
