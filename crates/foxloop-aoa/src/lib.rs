//! Android Open Accessory (AOA) transport -- talks to an Android device
//! directly over raw USB bulk transfers via `rusb`/libusb, bypassing adb
//! entirely (no adb-forward relay hop through the host's adb server and the
//! device's adbd). Introduced in MILESTONES.md Milestone 8 for the display
//! daemon; a crate of its own since FoxLoop Console, which needs the same
//! transport with a different identity and a narrower device scan.
//!
//! AOA handshake (standard protocol, not Samsung-specific): send a vendor
//! control request asking the device's protocol version, send six
//! identification strings, then a control request telling the device to
//! switch into accessory mode. The device then disconnects and
//! re-enumerates under Google's AOA vendor/product ID -- we have to find
//! and open *that* new device, not the original one.
//!
//! The six strings are the pairing identity, which is why they are a
//! parameter (`Identity`) rather than constants: the Android app's
//! `accessory_filter.xml` manifest resource must match manufacturer/model
//! for Android to route the USB_ACCESSORY_ATTACHED intent to that app at
//! all, and each FoxLoop product has its own app.

use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Guards the `FOXLOOP_USB_RESET` experiment to one reset per process --
/// see `try_open_accessory`.
static USB_RESET_DONE: AtomicBool = AtomicBool::new(false);

const GOOGLE_VID: u16 = 0x18D1;
const AOA_PID_ACCESSORY: u16 = 0x2D00;
const AOA_PID_ACCESSORY_ADB: u16 = 0x2D01;

const ACCESSORY_GET_PROTOCOL: u8 = 51;
const ACCESSORY_SEND_STRING: u8 = 52;
const ACCESSORY_START: u8 = 53;

const CLASS_MASS_STORAGE: u8 = 0x08;
const CLASS_HUB: u8 = 0x09;

/// The six AOA identification strings, in the order the spec indexes them.
///
/// Android matches `manufacturer` and `model` against the app's
/// `accessory_filter.xml`; the rest are shown to the user or read by the app.
/// Never filter on `version` alone: the AOA documentation warns that a
/// version-only match can reboot Android 10 and below.
pub struct Identity<'a> {
    pub manufacturer: &'a str,
    pub model: &'a str,
    pub description: &'a str,
    pub version: &'a str,
    /// Offered to the user as a download link when no installed app matches.
    pub uri: &'a str,
    pub serial: &'a str,
}

/// Which USB devices `connect` may send AOA vendor requests to.
///
/// Probing means opening the device and sending it `GET_PROTOCOL`. Most
/// devices that are not Android simply stall that request, but "most" is the
/// operative word: on a Raspberry Pi booting from a USB SSD, the SATA bridge is
/// one of the devices on the bus, and sending vendor requests to storage is a
/// risk with nothing to gain.
pub struct Probe<'a> {
    /// Only devices with one of these vendor IDs. `None` probes every vendor.
    pub vendors: Option<&'a [u16]>,
    /// Never probe a hub, or a device exposing a mass-storage interface in any
    /// configuration. Decided from descriptors libusb already cached, so a
    /// skipped device receives no request at all.
    pub skip_storage_and_hubs: bool,
}

impl Probe<'_> {
    /// What the display daemon has always done: ask every device.
    pub const ANY: Probe<'static> = Probe { vendors: None, skip_storage_and_hubs: false };
}

// Separate read/write timeouts, not one shared value: reads (particularly
// the very first one, waiting for Android's handshake) need to tolerate
// real human-interaction delay -- Android cold-start plus the USB
// accessory permission dialog can easily take way longer than a
// reasonable single bulk-transfer timeout would otherwise be. Confirmed
// live: a single 5s timeout used for both caused the input thread to give
// up permanently after 5s with nothing to retry it, even though the
// higher-level clock-sync wait (see portal_capture.rs) was patient for
// much longer. Writes don't have this problem -- if nobody's reading,
// failing reasonably fast is fine (and is what actually happened safely).
const READ_TIMEOUT: Duration = Duration::from_secs(90);
// Tightened from 5s: confirmed live, a stale write (old daemon process
// still mid-write when the Android app reopens its accessory after a
// relaunch) sat blocked for the full timeout before this process noticed
// and exited -- during that window the app's fresh handshake read landed
// on leftover video-frame bytes instead of the real reply, corrupting the
// new session (garbage clock-offset, garbage video-format header,
// immediate crash). 2s is still far more generous than any real frame
// write needs (steady-state frames go out every ~15-30ms) while cutting
// that race window roughly in half; the real fix is the Android side now
// retrying instead of requiring a second manual relaunch (see
// MainActivity.kt's surfaceCreated), this just shrinks how often the
// corruption happens in the first place.
const WRITE_TIMEOUT: Duration = Duration::from_secs(2);
const CONTROL_TIMEOUT: Duration = Duration::from_secs(2);
const REENUMERATE_TIMEOUT: Duration = Duration::from_secs(10);

/// libusb's synchronous transfer functions take `&self` (libusb itself is
/// thread-safe), so the handle is shared via `Arc` between a read half and
/// a write half that can each live on a different thread -- the same shape
/// as `TcpStream::try_clone()`, which the rest of the transport code
/// (`portal_capture.rs`, `input_receiver.rs`) was already written around.
struct Shared {
    handle: rusb::DeviceHandle<rusb::GlobalContext>,
    interface: u8,
}

pub struct AoaTransport {
    shared: Arc<Shared>,
    ep_in: u8,
    ep_out: u8,
}

pub struct AoaReader {
    shared: Arc<Shared>,
    ep_in: u8,
    idle_limit: Option<Duration>,
}

pub struct AoaWriter {
    shared: Arc<Shared>,
    ep_out: u8,
}

impl AoaTransport {
    /// Consumes `self`, splitting into an independent read half and write
    /// half sharing the same underlying USB handle via `Arc`, for use on
    /// separate threads -- each half keeps the handle alive on its own, so
    /// there's nothing left to leak or explicitly keep around afterward.
    pub fn split(self) -> (AoaReader, AoaWriter) {
        (
            AoaReader { shared: self.shared.clone(), ep_in: self.ep_in, idle_limit: None },
            AoaWriter { shared: self.shared, ep_out: self.ep_out },
        )
    }
}

/// Why `connect` gave up. Displays as the plain strings it used to return, so
/// a caller that only prints it sees no difference.
#[derive(Debug)]
pub enum ConnectError {
    /// Nothing the probe allows answered `GET_PROTOCOL` within the scan window.
    NoDevice(Duration),
    /// A device accepted the switch and never came back as an accessory.
    /// Worth asking again: seen on a Galaxy Tab when the request landed while
    /// Android was still applying a USB function change of its own -- it took
    /// the request without error and stayed in MTP mode. The same request a
    /// few seconds later switched it at once.
    NotReenumerated,
    /// libusb itself failed.
    Usb(String),
}

impl std::fmt::Display for ConnectError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConnectError::NoDevice(within) => write!(
                f,
                "no AOA-capable USB device found within {within:?} (is the Android device connected and unlocked?)"
            ),
            ConnectError::NotReenumerated => f.write_str("timed out waiting for AOA re-enumeration"),
            ConnectError::Usb(e) => f.write_str(e),
        }
    }
}

impl std::error::Error for ConnectError {}

fn send_string(
    handle: &rusb::DeviceHandle<rusb::GlobalContext>,
    index: u16,
    value: &str,
) -> rusb::Result<()> {
    let mut bytes = value.as_bytes().to_vec();
    bytes.push(0); // AOA strings are null-terminated
    handle.write_control(0x40, ACCESSORY_SEND_STRING, 0, index, &bytes, CONTROL_TIMEOUT)?;
    Ok(())
}

/// Scans the USB devices `probe` allows for one that answers the AOA
/// `GET_PROTOCOL` vendor request, switches it into accessory mode under
/// `identity`, waits for it to re-enumerate under Google's AOA VID/PID, and
/// opens that new device -- generic device discovery, not hardcoded to any
/// one tablet's own VID/PID (matches the project's no-hardcoding rule: any
/// AOA-capable Android device should work here unmodified).
///
/// A device that is *already* in accessory mode is reused without a switch,
/// and nothing on the bus says which identity it was switched under: the
/// re-enumerated device reports Google's IDs, not ours. So a phone left in
/// accessory mode by the display daemon is picked up by the console daemon
/// too, and stays routed to the display app until it is replugged.
pub fn connect(identity: &Identity, probe: &Probe) -> Result<AoaTransport, ConnectError> {
    // Bounded retry, not a single instant scan-and-fail: confirmed live,
    // this matters. A USB replug takes the tablet a few seconds to work
    // through disconnect -> re-enumerate-as-MTP before it's visible here at
    // all, and now that a failed connect() is fatal (see `setup_transport`
    // in portal_capture.rs -- exits so systemd can retry, rather than
    // silently running forever with a dead transport), an instant failure
    // right after a replug raced systemd's restart against the tablet still
    // mid-enumeration, burned through `StartLimitBurst` in a few hundred
    // milliseconds, and left the unit permanently `failed`, needing a
    // manual `systemctl --user reset-failed`. Retrying the scan here for a
    // while first absorbs that normal enumeration delay internally instead
    // of turning it into a crash loop.
    const DEVICE_SCAN_TIMEOUT: Duration = Duration::from_secs(20);
    let scan_deadline = Instant::now() + DEVICE_SCAN_TIMEOUT;

    loop {
        // Already switched from a previous run (device stays in accessory
        // mode across daemon restarts until physically replugged) -- use it
        // directly instead of trying the switch handshake again, which a
        // device already in accessory mode won't answer the same way.
        if let Some(t) = try_open_accessory() {
            eprintln!("[aoa] device already in accessory mode, reusing it");
            return Ok(t);
        }

        let devices = rusb::devices().map_err(|e| ConnectError::Usb(format!("rusb::devices: {e}")))?;

        let mut switched_any = false;
        for device in devices.iter() {
            if !probe_allows(probe, &device) {
                continue;
            }
            let Ok(handle) = device.open() else { continue };

            let mut protocol_buf = [0u8; 2];
            let protocol = handle.read_control(0xC0, ACCESSORY_GET_PROTOCOL, 0, 0, &mut protocol_buf, CONTROL_TIMEOUT);
            let Ok(n) = protocol else { continue };
            if n != 2 {
                continue;
            }
            let version = u16::from_le_bytes(protocol_buf);
            if version == 0 {
                continue; // doesn't support AOA
            }
            eprintln!(
                "[aoa] found AOA-capable device (protocol v{version}) at bus {} addr {}, switching to accessory mode...",
                device.bus_number(),
                device.address()
            );

            send_string(&handle, 0, identity.manufacturer).map_err(|e| ConnectError::Usb(format!("send manufacturer: {e}")))?;
            send_string(&handle, 1, identity.model).map_err(|e| ConnectError::Usb(format!("send model: {e}")))?;
            send_string(&handle, 2, identity.description).map_err(|e| ConnectError::Usb(format!("send description: {e}")))?;
            send_string(&handle, 3, identity.version).map_err(|e| ConnectError::Usb(format!("send version: {e}")))?;
            send_string(&handle, 4, identity.uri).map_err(|e| ConnectError::Usb(format!("send uri: {e}")))?;
            send_string(&handle, 5, identity.serial).map_err(|e| ConnectError::Usb(format!("send serial: {e}")))?;

            handle
                .write_control(0x40, ACCESSORY_START, 0, 0, &[], CONTROL_TIMEOUT)
                .map_err(|e| ConnectError::Usb(format!("ACCESSORY_START: {e}")))?;

            switched_any = true;
            break;
        }

        if switched_any {
            break;
        }

        if Instant::now() > scan_deadline {
            return Err(ConnectError::NoDevice(DEVICE_SCAN_TIMEOUT));
        }
        std::thread::sleep(Duration::from_millis(500));
    }

    eprintln!("[aoa] waiting for device to re-enumerate as an accessory...");
    let deadline = Instant::now() + REENUMERATE_TIMEOUT;
    loop {
        if Instant::now() > deadline {
            return Err(ConnectError::NotReenumerated);
        }
        if let Some(t) = try_open_accessory() {
            return Ok(t);
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn probe_allows(probe: &Probe, device: &rusb::Device<rusb::GlobalContext>) -> bool {
    let Ok(desc) = device.device_descriptor() else { return false };
    if !probe.skip_storage_and_hubs {
        return vendor_allowed(probe, desc.vendor_id());
    }
    // Every configuration, not just the active one: a device can expose
    // storage in a configuration it is not currently using. Any descriptor we
    // cannot read counts against the device -- the point is to be sure.
    let mut classes = vec![desc.class_code()];
    for i in 0..desc.num_configurations() {
        let Ok(config) = device.config_descriptor(i) else { return false };
        for interface in config.interfaces() {
            classes.extend(interface.descriptors().map(|alt| alt.class_code()));
        }
    }
    vendor_allowed(probe, desc.vendor_id()) && !storage_or_hub(&classes)
}

/// `classes`: the device class, then every interface class it declares.
fn storage_or_hub(classes: &[u8]) -> bool {
    classes.iter().any(|&c| c == CLASS_MASS_STORAGE || c == CLASS_HUB)
}

fn vendor_allowed(probe: &Probe, vendor_id: u16) -> bool {
    probe.vendors.is_none_or(|v| v.contains(&vendor_id))
}

fn try_open_accessory() -> Option<AoaTransport> {
    let devices = rusb::devices().ok()?;
    for device in devices.iter() {
        // Each `continue` here skips to the next device on any failure --
        // one device that can't be inspected shouldn't abort the whole scan.
        let Ok(desc) = device.device_descriptor() else { continue };
        if desc.vendor_id() != GOOGLE_VID {
            continue;
        }
        if desc.product_id() != AOA_PID_ACCESSORY && desc.product_id() != AOA_PID_ACCESSORY_ADB {
            continue;
        }

        let Ok(handle) = device.open() else { continue };

        // Opt-in experiment (MILESTONES.md Milestone 21): a host-side USB reset
        // is the lever the daemon could pull itself instead of telling the user
        // to unplug the cable, which is the app's current status-overlay
        // advice. The one recorded attempt is confounded -- the cable was
        // replugged at the same moment -- so this stays behind an env var until
        // an isolated test says whether the reset alone is enough to make
        // Android re-fire USB_ACCESSORY_ATTACHED.
        //
        // Once per process, not once per scan: `connect`'s retry loop calls
        // this repeatedly, and resetting every time would never let the device
        // finish re-enumerating.
        if std::env::var("FOXLOOP_USB_RESET").is_ok() && !USB_RESET_DONE.swap(true, Ordering::SeqCst) {
            eprintln!(
                "[aoa] FOXLOOP_USB_RESET: resetting bus {} addr {} and waiting for it to re-enumerate...",
                device.bus_number(),
                device.address()
            );
            match handle.reset() {
                // Either way the handle is invalid now -- the device
                // disappears and comes back at a new address, which is what
                // `connect`'s scan loop is already built to wait out.
                Ok(()) => eprintln!("[aoa] reset issued"),
                Err(e) => eprintln!("[aoa] reset failed ({e}) -- carrying on without it"),
            }
            return None;
        }

        let Ok(config) = device.active_config_descriptor() else { continue };
        let Some(interface) = config.interfaces().next() else { continue };
        let Some(interface_desc) = interface.descriptors().next() else { continue };

        let mut ep_in = None;
        let mut ep_out = None;
        for ep in interface_desc.endpoint_descriptors() {
            if ep.transfer_type() != rusb::TransferType::Bulk {
                continue;
            }
            match ep.direction() {
                rusb::Direction::In => ep_in = Some(ep.address()),
                rusb::Direction::Out => ep_out = Some(ep.address()),
            }
        }
        let (Some(ep_in), Some(ep_out)) = (ep_in, ep_out) else {
            continue;
        };

        let interface_num = interface_desc.interface_number();
        if handle.claim_interface(interface_num).is_err() {
            continue;
        }

        // Flush any stale queued data left over from a previous session --
        // confirmed live, reconnecting (daemon restart, app relaunch, or
        // both) without this intermittently corrupted the fresh session's
        // very first handshake read/write with leftover bytes from the
        // prior connection (garbage clock-offset, garbage capability
        // handshake, "Invalid argument" on uinput tablet creation from
        // nonsense pressure/tilt ranges). Claiming the interface fresh
        // doesn't by itself guarantee the endpoints are clear -- clearing
        // the halt/data-toggle state on both bulk endpoints does.
        let _ = handle.clear_halt(ep_in);
        let _ = handle.clear_halt(ep_out);

        eprintln!(
            "[aoa] connected: bus {} addr {}, interface {interface_num}, bulk in=0x{ep_in:02x} out=0x{ep_out:02x}",
            device.bus_number(),
            device.address()
        );

        return Some(AoaTransport {
            shared: Arc::new(Shared { handle, interface: interface_num }),
            ep_in,
            ep_out,
        });
    }
    None
}

impl AoaReader {
    /// How long a read may wait for the first byte before failing with
    /// `ErrorKind::TimedOut`. `None`, the default, waits forever -- see the
    /// `Read` impl for why the display daemon needs that. A protocol with its
    /// own heartbeat wants the opposite: silence past the heartbeat interval
    /// *is* the failure, and only the reading thread can notice it, because a
    /// blocked bulk read cannot be interrupted from outside.
    pub fn set_idle_limit(&mut self, limit: Option<Duration>) {
        self.idle_limit = limit;
    }
}

impl Read for AoaReader {
    /// Loops on a USB bulk timeout instead of surfacing it as an error --
    /// confirmed live: `input_receiver.rs`'s steady-state loop reads one
    /// event at a time and treats any read error as fatal (stream ended,
    /// thread exits for good, no retry). A `rusb` bulk-read timeout just
    /// means "no data arrived within `READ_TIMEOUT`", which is completely
    /// normal during any idle stretch with no pen/finger input -- a real
    /// TCP socket blocks indefinitely in the same situation rather than
    /// erroring, so this makes AOA behave the same way rather than killing
    /// the input thread after 90 seconds of nobody touching the screen.
    /// Genuine failures (device unplugged, etc.) come back as a different
    /// `rusb::Error` variant and still propagate normally.
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            match self.shared.handle.read_bulk(self.ep_in, buf, self.idle_limit.unwrap_or(READ_TIMEOUT)) {
                Ok(n) => return Ok(n),
                Err(rusb::Error::Timeout) if self.idle_limit.is_none() => continue,
                Err(rusb::Error::Timeout) => {
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "AOA bulk read: idle limit reached"))
                }
                Err(e) => return Err(io::Error::other(format!("AOA bulk read: {e}"))),
            }
        }
    }
}

impl Write for AoaWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.shared
            .handle
            .write_bulk(self.ep_out, buf, WRITE_TIMEOUT)
            .map_err(|e| io::Error::other(format!("AOA bulk write: {e}")))
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(()) // bulk transfers are unbuffered, nothing to flush
    }
}

impl Drop for Shared {
    fn drop(&mut self) {
        let _ = self.handle.release_interface(self.interface);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn any_allows_every_vendor() {
        assert!(vendor_allowed(&Probe::ANY, 0x04E8));
        assert!(vendor_allowed(&Probe::ANY, 0x152D));
    }

    #[test]
    fn vendor_list_is_exclusive() {
        let probe = Probe { vendors: Some(&[0x04E8, GOOGLE_VID]), skip_storage_and_hubs: true };
        assert!(vendor_allowed(&probe, 0x04E8));
        assert!(vendor_allowed(&probe, GOOGLE_VID));
        // A JMicron USB-SATA bridge, the usual thing a Pi boots from.
        assert!(!vendor_allowed(&probe, 0x152D));
    }

    #[test]
    fn storage_and_hubs_are_recognised_anywhere_in_the_list() {
        // A phone: device class 0 (per interface), MTP and adb interfaces.
        assert!(!storage_or_hub(&[0x00, 0x06, 0xFF]));
        // A UAS SSD bridge, and the same bridge declaring its class up front.
        assert!(storage_or_hub(&[0x00, 0x08, 0x08]));
        assert!(storage_or_hub(&[0x08]));
        // A hub, which carries class 9 on the device itself.
        assert!(storage_or_hub(&[0x09, 0x09]));
        // Some phones expose a virtual CD-ROM for driver install: also skipped,
        // which is the price of never touching storage.
        assert!(storage_or_hub(&[0x00, 0x08, 0xFF]));
    }
}
