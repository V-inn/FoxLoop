//! The FoxLoop Console wire protocol, version 1. Both directions, in one place.
//!
//! # The rule
//!
//! Every byte in both directions is a typed, length-prefixed frame, from the
//! first byte on:
//!
//! ```text
//! u8   type
//! u32  payload_len    big-endian
//! ...  payload
//! ```
//!
//! This is the display protocol's v2 lesson (`daemon/src/protocol.rs`) applied
//! to the upstream direction too, which the display never got: there, input
//! events going up are still unframed 22-byte records, and any disagreement
//! about how many bytes have been consumed is permanent and silent. Here a
//! reader that does not know a type skips it by length, so new messages never
//! need a version bump.
//!
//! Strings inside payloads are `u16 len` + UTF-8.
//!
//! # Messages
//!
//! | Type | Name    | Direction        | Payload                                             |
//! |------|---------|------------------|-----------------------------------------------------|
//! | 0x01 | HELLO   | app -> daemon    | `u32 magic`, `u16 version`, `u64 nonce`, `u32 caps` |
//! | 0x02 | WELCOME | daemon -> app    | `u64 nonce` (echoed), `u16 version`, `u32 caps`, `str hostname` |
//! | 0x10 | DATA    | both             | SSH bytes, at most `MAX_DATA` per frame             |
//! | 0x11 | CLOSE   | both             | `u8 reason`, `str detail`                           |
//! | 0x20 | STATUS  | daemon -> app, 1 s | `u32 throttled`, `i32 cpu_temp_millideg`          |
//! | 0x21 | PING    | app -> daemon, 1 s | empty                                             |
//!
//! # Session rules
//!
//! 1. The app sends HELLO with a fresh random nonce, then ignores every frame
//!    until a WELCOME that echoes it. Bytes left over from a previous session
//!    cannot be mistaken for this one.
//! 2. The daemon discards input until a HELLO with the right magic and version
//!    (`find_hello`). Only then does it open the TCP connection to sshd, one
//!    per session.
//! 3. DATA flows only after WELCOME. CLOSE from either side ends the session.
//! 4. STATUS and PING double as heartbeats. Either side ends the session after
//!    `HEARTBEAT_TIMEOUT` with no frame at all.
//!
//! No flow control lives at this layer: SSH has per-channel windows, TCP pushes
//! back on sshd, and a blocked USB bulk write pushes back on the daemon.

use std::io::{self, Read};
use std::time::Duration;

pub const HELLO: u8 = 0x01;
pub const WELCOME: u8 = 0x02;
pub const DATA: u8 = 0x10;
pub const CLOSE: u8 = 0x11;
pub const STATUS: u8 = 0x20;
pub const PING: u8 = 0x21;

/// `"FXCN"`, deliberately not the display's `"QUIL"`: a display client that
/// somehow reaches a console daemon is refused at the first four bytes of its
/// payload instead of being misread. In practice the AOA model string already
/// keeps the two apart.
pub const MAGIC: [u8; 4] = *b"FXCN";

/// Bumped only when the meaning of an existing field changes. New message
/// types and appended fields do not need it.
pub const VERSION: u16 = 1;

/// Largest DATA payload either side sends.
pub const MAX_DATA: usize = 16 * 1024;

/// Larger than any frame this protocol sends. A length beyond it means the
/// reader is out of step with the stream, not that a huge frame is coming.
pub const MAX_PAYLOAD: usize = 64 * 1024;

pub const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(1);
/// FoxLoop's display needs 15 s because of portal setup; there is none here.
pub const HEARTBEAT_TIMEOUT: Duration = Duration::from_secs(5);

/// STATUS `throttled` when the machine has no way to report it -- anything
/// that is not a Raspberry Pi. No real value sets every bit.
pub const THROTTLED_UNKNOWN: u32 = u32::MAX;
/// STATUS `cpu_temp_millideg` when there is no thermal zone to read.
pub const TEMP_UNKNOWN: i32 = i32::MIN;

/// CLOSE reasons. The `detail` string carries the specifics for a person.
pub mod reason {
    /// The app ended the session (the user closed it). Only the app sends it;
    /// listed so the numbering lives in one place.
    #[allow(dead_code)]
    pub const APP_CLOSED: u8 = 0;
    /// The TCP connection to sshd could not be opened.
    pub const SSHD_REFUSED: u8 = 1;
    /// sshd closed the connection, or it failed mid-session.
    pub const TCP_CLOSED: u8 = 2;
}

/// HELLO's fixed part: magic, version, nonce, caps.
const HELLO_LEN: usize = 4 + 2 + 8 + 4;
/// Room for fields a later version appends, without accepting the kind of
/// length only a stream out of step produces.
const HELLO_MAX_LEN: usize = 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Hello {
    pub version: u16,
    pub nonce: u64,
    pub caps: u32,
}

impl Hello {
    /// `None` unless the payload carries the right magic. Trailing fields a
    /// later version appends are ignored.
    pub fn parse(payload: &[u8]) -> Option<Hello> {
        if payload.len() < HELLO_LEN || payload[0..4] != MAGIC {
            return None;
        }
        Some(Hello {
            version: u16::from_be_bytes(payload[4..6].try_into().unwrap()),
            nonce: u64::from_be_bytes(payload[6..14].try_into().unwrap()),
            caps: u32::from_be_bytes(payload[14..18].try_into().unwrap()),
        })
    }

    /// Only the app sends HELLO; this is for the tests that play its part.
    #[cfg(test)]
    pub fn encode(&self) -> Vec<u8> {
        let mut p = Vec::with_capacity(HELLO_LEN);
        p.extend_from_slice(&MAGIC);
        p.extend_from_slice(&self.version.to_be_bytes());
        p.extend_from_slice(&self.nonce.to_be_bytes());
        p.extend_from_slice(&self.caps.to_be_bytes());
        encode(HELLO, &p)
    }
}

#[derive(Debug)]
pub struct Frame {
    pub kind: u8,
    pub payload: Vec<u8>,
}

/// One frame as a single buffer, so the writer can send it with one
/// `write_all`: frames from different threads then never interleave, which is
/// the lesson behind the display's `encode_message`.
pub fn encode(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut f = Vec::with_capacity(5 + payload.len());
    f.push(kind);
    f.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    f.extend_from_slice(payload);
    f
}

pub fn read_frame(r: &mut impl Read) -> io::Result<Frame> {
    let mut header = [0u8; 5];
    r.read_exact(&mut header)?;
    let len = u32::from_be_bytes(header[1..5].try_into().unwrap()) as usize;
    if len > MAX_PAYLOAD {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("frame type 0x{:02x} claims {len} bytes -- stream out of step", header[0]),
        ));
    }
    let mut payload = vec![0u8; len];
    r.read_exact(&mut payload)?;
    Ok(Frame { kind: header[0], payload })
}

/// Reads until a HELLO with the right magic, whatever comes before it.
///
/// Byte by byte, not frame by frame: what precedes the HELLO may be the tail of
/// a previous session, cut anywhere, and reading it as frames would take a
/// length out of the middle of a DATA payload and swallow the real HELLO with
/// it. Returns the HELLO and how many bytes were skipped to find it.
pub fn find_hello(r: &mut impl Read) -> io::Result<(Hello, u64)> {
    // type, length, magic: the nine bytes that identify a HELLO.
    let mut window = [0u8; 9];
    r.read_exact(&mut window)?;
    let mut discarded = 0u64;
    loop {
        if window[0] == HELLO && window[5..9] == MAGIC {
            let len = u32::from_be_bytes(window[1..5].try_into().unwrap()) as usize;
            if (HELLO_LEN..=HELLO_MAX_LEN).contains(&len) {
                let mut payload = vec![0u8; len];
                payload[0..4].copy_from_slice(&MAGIC);
                r.read_exact(&mut payload[4..])?;
                return Ok((Hello::parse(&payload).unwrap(), discarded));
            }
        }
        window.copy_within(1.., 0);
        r.read_exact(&mut window[8..])?;
        discarded += 1;
    }
}

pub fn welcome(nonce: u64, caps: u32, hostname: &str) -> Vec<u8> {
    let mut p = Vec::new();
    p.extend_from_slice(&nonce.to_be_bytes());
    p.extend_from_slice(&VERSION.to_be_bytes());
    p.extend_from_slice(&caps.to_be_bytes());
    put_str(&mut p, hostname);
    encode(WELCOME, &p)
}

pub fn close(reason: u8, detail: &str) -> Vec<u8> {
    let mut p = vec![reason];
    put_str(&mut p, detail);
    encode(CLOSE, &p)
}

pub fn status(throttled: u32, cpu_temp_millideg: i32) -> Vec<u8> {
    let mut p = Vec::with_capacity(8);
    p.extend_from_slice(&throttled.to_be_bytes());
    p.extend_from_slice(&cpu_temp_millideg.to_be_bytes());
    encode(STATUS, &p)
}

/// Truncated on a character boundary if it would not fit a `u16` length.
fn put_str(buf: &mut Vec<u8>, s: &str) {
    let mut end = s.len().min(u16::MAX as usize);
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    buf.extend_from_slice(&(end as u16).to_be_bytes());
    buf.extend_from_slice(&s.as_bytes()[..end]);
}

/// The reverse of `put_str`, for reading CLOSE and WELCOME back in tests.
#[cfg(test)]
pub fn get_str(buf: &[u8]) -> Option<(String, &[u8])> {
    let len = u16::from_be_bytes(buf.get(0..2)?.try_into().unwrap()) as usize;
    let s = std::str::from_utf8(buf.get(2..2 + len)?).ok()?;
    Some((s.to_string(), &buf[2 + len..]))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hello(nonce: u64) -> Hello {
        Hello { version: VERSION, nonce, caps: 0 }
    }

    #[test]
    fn frame_round_trips() {
        let bytes = encode(DATA, b"SSH-2.0-OpenSSH\r\n");
        assert_eq!(bytes[0], DATA);
        assert_eq!(&bytes[1..5], &17u32.to_be_bytes());
        let f = read_frame(&mut &bytes[..]).unwrap();
        assert_eq!(f.kind, DATA);
        assert_eq!(f.payload, b"SSH-2.0-OpenSSH\r\n");
    }

    #[test]
    fn empty_frame_is_five_bytes() {
        assert_eq!(encode(PING, &[]), vec![PING, 0, 0, 0, 0]);
    }

    #[test]
    fn oversized_length_is_refused_not_allocated() {
        let bytes = [DATA, 0xFF, 0xFF, 0xFF, 0xFF];
        let e = read_frame(&mut &bytes[..]).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn hello_is_found_at_the_start() {
        let bytes = hello(7).encode();
        let (h, skipped) = find_hello(&mut &bytes[..]).unwrap();
        assert_eq!(h, hello(7));
        assert_eq!(skipped, 0);
    }

    #[test]
    fn hello_is_found_after_the_tail_of_an_old_session() {
        // Half a DATA frame whose payload happens to contain a HELLO type byte,
        // a PING, then the real thing.
        let mut bytes = encode(DATA, &[HELLO, 0, 0, 0, 18, b'F', b'X'])[3..].to_vec();
        bytes.extend(encode(PING, &[]));
        let junk = bytes.len() as u64;
        bytes.extend(hello(0xDEAD_BEEF).encode());
        let (h, skipped) = find_hello(&mut &bytes[..]).unwrap();
        assert_eq!(h.nonce, 0xDEAD_BEEF);
        assert_eq!(skipped, junk);
    }

    #[test]
    fn display_handshake_is_not_a_hello() {
        // The display client's first bytes: "QUIL", version, body length.
        let mut bytes = b"QUIL\x00\x02\x00\x29".to_vec();
        bytes.extend([0u8; 41]);
        bytes.extend(hello(1).encode());
        let (h, skipped) = find_hello(&mut &bytes[..]).unwrap();
        assert_eq!(h.nonce, 1);
        assert_eq!(skipped, 49);
    }

    #[test]
    fn hello_with_appended_fields_is_accepted() {
        let mut payload = hello(3).encode()[5..].to_vec();
        payload.extend([1, 2, 3, 4]);
        let bytes = encode(HELLO, &payload);
        let (h, _) = find_hello(&mut &bytes[..]).unwrap();
        assert_eq!(h.nonce, 3);
    }

    #[test]
    fn hello_with_wrong_magic_is_not_parsed() {
        let mut payload = hello(3).encode()[5..].to_vec();
        payload[0] = b'Q';
        assert_eq!(Hello::parse(&payload), None);
    }

    #[test]
    fn welcome_layout() {
        let f = read_frame(&mut &welcome(0x0102030405060708, 0, "pi")[..]).unwrap();
        assert_eq!(f.kind, WELCOME);
        assert_eq!(&f.payload[0..8], &[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(&f.payload[8..10], &VERSION.to_be_bytes());
        assert_eq!(&f.payload[10..14], &[0, 0, 0, 0]);
        assert_eq!(get_str(&f.payload[14..]).unwrap().0, "pi");
    }

    #[test]
    fn close_layout() {
        let f = read_frame(&mut &close(reason::SSHD_REFUSED, "refused")[..]).unwrap();
        assert_eq!(f.payload[0], reason::SSHD_REFUSED);
        assert_eq!(get_str(&f.payload[1..]).unwrap().0, "refused");
    }

    #[test]
    fn status_layout() {
        let f = read_frame(&mut &status(0x50005, -1234)[..]).unwrap();
        assert_eq!(f.payload, [0, 5, 0, 5, 0xFF, 0xFF, 0xFB, 0x2E]);
    }

    // The same bytes the app's ConsoleProtocolTest holds itself to. Each side is
    // tested against the bytes, not against the other side's code, so a change
    // one half makes and the other does not fails here rather than in a session.
    const GOLDEN_NONCE: u64 = 0x0102030405060708;
    const GOLDEN_HELLO: [u8; 23] =
        [0x01, 0, 0, 0, 18, b'F', b'X', b'C', b'N', 0, 1, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0];
    const GOLDEN_WELCOME: [u8; 23] =
        [0x02, 0, 0, 0, 18, 1, 2, 3, 4, 5, 6, 7, 8, 0, 1, 0, 0, 0, 0, 0, 2, b'p', b'i'];
    const GOLDEN_CLOSE: [u8; 10] = [0x11, 0, 0, 0, 5, 1, 0, 2, b'n', b'o'];

    #[test]
    fn golden_hello_is_found_and_parsed() {
        let (h, skipped) = find_hello(&mut &GOLDEN_HELLO[..]).unwrap();
        assert_eq!(skipped, 0);
        assert_eq!(h, Hello { version: 1, nonce: GOLDEN_NONCE, caps: 0 });
    }

    #[test]
    fn golden_welcome_and_close() {
        assert_eq!(welcome(GOLDEN_NONCE, 0, "pi"), GOLDEN_WELCOME);
        assert_eq!(close(reason::SSHD_REFUSED, "no"), GOLDEN_CLOSE);
    }

    #[test]
    fn long_strings_are_cut_on_a_character_boundary() {
        let s = "é".repeat(40_000); // 80 000 bytes, two per character
        let mut buf = Vec::new();
        put_str(&mut buf, &s);
        let (back, rest) = get_str(&buf).unwrap();
        assert!(rest.is_empty());
        assert_eq!(back.len(), 65_534);
    }
}
