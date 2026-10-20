//! What the STATUS frame reports: the Pi's throttling flags and CPU
//! temperature, once a second.
//!
//! Both exist for one question -- is this machine short of power? A phone on
//! the cable draws from the same USB budget as the disk, and under-voltage on
//! a Pi shows up as storage dropping out long before anything says "power".

use crate::frame::{TEMP_UNKNOWN, THROTTLED_UNKNOWN};
use std::process::Command;

const THERMAL_ZONE: &str = "/sys/class/thermal/thermal_zone0/temp";

/// Raspberry Pi kernels expose the firmware's throttled word here, which needs
/// no device access. Read first, so `vcgencmd` -- a process per second, and
/// `/dev/vcio` -- is only the fallback.
const THROTTLED_SYSFS: &str = "/sys/devices/platform/soc/soc:firmware/get_throttled";

/// Where the throttled word comes from on this machine, decided once: on
/// anything that is not a Pi the answer is "nowhere", and finding that out
/// again every second would spawn a failing process every second.
pub enum ThrottledSource {
    Sysfs,
    Vcgencmd,
    None,
}

impl ThrottledSource {
    pub fn detect() -> ThrottledSource {
        if read_sysfs().is_some() {
            ThrottledSource::Sysfs
        } else if run_vcgencmd().is_some() {
            ThrottledSource::Vcgencmd
        } else {
            ThrottledSource::None
        }
    }

    pub fn describe(&self) -> &'static str {
        match self {
            ThrottledSource::Sysfs => THROTTLED_SYSFS,
            ThrottledSource::Vcgencmd => "vcgencmd get_throttled",
            ThrottledSource::None => "unavailable (not a Raspberry Pi?)",
        }
    }

    pub fn read(&self) -> u32 {
        match self {
            ThrottledSource::Sysfs => read_sysfs(),
            ThrottledSource::Vcgencmd => run_vcgencmd(),
            ThrottledSource::None => None,
        }
        .unwrap_or(THROTTLED_UNKNOWN)
    }
}

fn read_sysfs() -> Option<u32> {
    parse_throttled(&std::fs::read_to_string(THROTTLED_SYSFS).ok()?)
}

fn run_vcgencmd() -> Option<u32> {
    let out = Command::new("vcgencmd").arg("get_throttled").output().ok()?;
    if !out.status.success() {
        return None;
    }
    parse_throttled(&String::from_utf8_lossy(&out.stdout))
}

/// Accepts `vcgencmd`'s `throttled=0x50005` and sysfs's bare `50005`. Both are
/// hexadecimal, with or without the prefix.
fn parse_throttled(text: &str) -> Option<u32> {
    let value = text.trim();
    let value = value.strip_prefix("throttled=").unwrap_or(value);
    let value = value.strip_prefix("0x").unwrap_or(value);
    u32::from_str_radix(value, 16).ok()
}

pub fn cpu_temp_millideg() -> i32 {
    std::fs::read_to_string(THERMAL_ZONE)
        .ok()
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(TEMP_UNKNOWN)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vcgencmd_output() {
        assert_eq!(parse_throttled("throttled=0x0\n"), Some(0));
        assert_eq!(parse_throttled("throttled=0x50005\n"), Some(0x50005));
    }

    #[test]
    fn sysfs_output() {
        assert_eq!(parse_throttled("0\n"), Some(0));
        assert_eq!(parse_throttled("50005\n"), Some(0x50005));
    }

    #[test]
    fn garbage_is_not_a_flag_word() {
        assert_eq!(parse_throttled("VCHI initialization failed\n"), None);
        assert_eq!(parse_throttled(""), None);
    }
}
