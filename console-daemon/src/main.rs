//! FoxLoop Console's daemon: a dumb, framed pipe between an Android phone on
//! the USB cable and this machine's own sshd on loopback.
//!
//! The phone runs a real SSH client over the pipe, so sshd does the password
//! check, the PTY and any port forwards, and this process needs no root: only
//! the USB device and a loopback socket. With no network at all -- Wi-Fi down,
//! no DHCP -- it still works, because loopback depends on neither.
//!
//! Usage: `foxloop-console [sshd address]`, defaulting to `127.0.0.1:22`.

mod frame;
mod session;
mod status;

use sha2::{Digest, Sha256};
use std::io::BufReader;
use std::net::{SocketAddr, ToSocketAddrs};
use std::time::Duration;

/// Which devices may be sent AOA vendor requests at all. Samsung (`04e8`) and
/// Google (`18d1`, which also covers a phone after it re-enumerates in accessory
/// mode); others join as they are tested. Must match the vendor list in
/// `packaging/70-foxloop-console.rules`, which grants this daemon's user the
/// device nodes in the first place.
const VENDORS: &[u16] = &[0x04E8, 0x18D1];

/// Never a hub or anything exposing mass storage. On a Pi that boots from a USB
/// SSD, the SATA bridge is on this bus -- and Samsung's own portable SSDs carry
/// the same `04e8` as its phones, so the vendor list alone is not enough.
const PROBE: foxloop_aoa::Probe<'static> =
    foxloop_aoa::Probe { vendors: Some(VENDORS), skip_storage_and_hubs: true };

/// Bulk reads are refilled into this. Larger than the biggest frame either
/// side sends (16 KiB of DATA plus its header), so one frame never needs two
/// USB transfers to arrive, and a small read can never overflow a packet.
const LINK_BUFFER: usize = 64 * 1024;

/// After a link fails, before asking for the device again. Without it a link
/// that fails instantly every time would spin.
const RELINK_DELAY: Duration = Duration::from_secs(1);

/// How many times a device that ignored the switch to accessory mode is asked
/// again before giving up. Exiting is final here -- the unit exits 0 and only
/// the next plug starts it -- so one ignored request must not be the end.
const SWITCH_ATTEMPTS: u32 = 3;

struct Link(BufReader<foxloop_aoa::AoaReader>);

impl std::io::Read for Link {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.0.read(buf)
    }
}

impl std::io::BufRead for Link {
    fn fill_buf(&mut self) -> std::io::Result<&[u8]> {
        self.0.fill_buf()
    }
    fn consume(&mut self, amt: usize) {
        self.0.consume(amt)
    }
}

impl session::IdleLimit for Link {
    fn set_idle_limit(&mut self, limit: Option<Duration>) {
        self.0.get_mut().set_idle_limit(limit);
    }
}

fn main() {
    let target_arg = std::env::args().nth(1).unwrap_or_else(|| "127.0.0.1:22".to_string());
    let target = resolve(&target_arg);
    let hostname = hostname();
    let serial = machine_serial();
    let throttled = status::ThrottledSource::detect();

    eprintln!("[console] {hostname}: relaying to sshd at {target}");
    eprintln!("[status] throttled flags: {}", throttled.describe());

    let identity = foxloop_aoa::Identity {
        manufacturer: "FoxLoop",
        // What the app's accessory_filter.xml matches. The display sends
        // "FoxLoop Virtual Display", so each cable reaches the right app.
        model: "FoxLoop Console",
        description: "FoxLoop USB console",
        // Never filtered on: a version-only match can reboot Android 10 and
        // below, per the AOA documentation.
        version: "1",
        // Shown by Android when no installed app matches, so a phone without
        // FoxLoop Console is pointed at where to get it.
        uri: "https://v-inn.github.io/FoxLoop/",
        // The app pins sshd's host key per serial: `connectVia` reports every
        // machine as the same host, so the address cannot tell them apart.
        serial: &serial,
    };

    let config = session::Config {
        target,
        hostname,
        heartbeat_interval: frame::HEARTBEAT_INTERVAL,
        heartbeat_timeout: frame::HEARTBEAT_TIMEOUT,
        status: Box::new(move || (throttled.read(), status::cpu_temp_millideg())),
    };

    let mut ignored_switches = 0;
    loop {
        let transport = match foxloop_aoa::connect(&identity, &PROBE) {
            Ok(t) => t,
            Err(foxloop_aoa::ConnectError::NotReenumerated) if ignored_switches + 1 < SWITCH_ATTEMPTS => {
                ignored_switches += 1;
                eprintln!("[aoa] the device ignored the switch to accessory mode -- asking again");
                std::thread::sleep(RELINK_DELAY);
                continue;
            }
            Err(e) => {
                // Not a failure: udev starts this on any matching plug, and a
                // phone that was unplugged again -- or a Samsung SSD -- leaves
                // nothing to serve. Exit 0 so systemd does not count it, and
                // the next plug starts a fresh process.
                eprintln!("[aoa] {e} -- nothing to serve, exiting");
                return;
            }
        };
        ignored_switches = 0;
        let (r, w) = transport.split();
        let err = session::serve_link(Link(BufReader::with_capacity(LINK_BUFFER, r)), w, &config);
        eprintln!("[link] {err} -- looking for the device again");
        std::thread::sleep(RELINK_DELAY);
    }
}

fn resolve(target: &str) -> SocketAddr {
    match target.to_socket_addrs().ok().and_then(|mut a| a.next()) {
        Some(a) => a,
        None => {
            eprintln!("[console] not an address: {target} (expected host:port, e.g. 127.0.0.1:22)");
            std::process::exit(2);
        }
    }
}

fn hostname() -> String {
    std::fs::read_to_string("/proc/sys/kernel/hostname")
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|_| "unknown".to_string())
}

/// A short, stable ID for this machine, derived from `/etc/machine-id` without
/// revealing it. machine-id(5) asks that the ID itself not be exposed to
/// untrusted parties and that applications derive their own from it instead;
/// the AOA serial string is readable by the phone.
fn machine_serial() -> String {
    let Ok(id) = std::fs::read_to_string("/etc/machine-id") else {
        eprintln!("[console] /etc/machine-id unreadable -- the app cannot pin this machine's host key by serial");
        return String::new();
    };
    let digest = Sha256::digest(format!("foxloop-console:{}", id.trim()));
    digest[..8].iter().map(|b| format!("{b:02x}")).collect()
}
