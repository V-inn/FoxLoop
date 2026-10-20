//! The session loop: one AOA link, any number of sessions over it, one TCP
//! connection to sshd per session.
//!
//! The daemon never parses a keystroke or an HTTP request. It moves DATA
//! payloads between the cable and one TCP connection, and everything above
//! that -- the password check, the PTY, dashboard port forwards -- is an
//! ordinary SSH session between the app and sshd.
//!
//! Threads, the same std-thread shape as the display daemon, no async runtime:
//!
//! - **This thread** reads frames from the phone: DATA goes to the TCP socket,
//!   PING only proves the app is alive.
//! - **TCP reader**: up to `MAX_DATA` bytes from sshd per DATA frame.
//! - **Status ticker**: one STATUS frame per `HEARTBEAT_INTERVAL`.
//! - **Writer**: owns the link's write half and sends each frame with a single
//!   `write_all`. One owner means frames never interleave. It lives as long as
//!   the link, not the session, and everything else reaches it through a
//!   bounded channel -- so a stalled USB write stops the TCP reader, which
//!   stops reading from sshd, which is all the flow control this needs.

use crate::frame::{self, Hello};
use std::io::{self, BufRead, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TrySendError};
use std::thread;
use std::time::Duration;

/// Frames queued for the writer. Small: anything more is latency, and a full
/// queue is exactly the backpressure that should reach sshd.
const WRITE_QUEUE: usize = 16;

/// How long to wait for sshd to accept. It is on loopback, so anything slower
/// than this is an sshd that is not coming.
const TCP_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// A link's read half whose reads can be made to give up. Only the reading
/// thread can notice that the phone went quiet: a blocked USB bulk read cannot
/// be interrupted from outside.
pub trait IdleLimit {
    fn set_idle_limit(&mut self, limit: Option<Duration>);
}

pub struct Config {
    /// sshd, normally `127.0.0.1:22`.
    pub target: SocketAddr,
    pub hostname: String,
    pub heartbeat_interval: Duration,
    pub heartbeat_timeout: Duration,
    /// `(throttled, cpu_temp_millideg)` for each STATUS frame.
    pub status: Box<dyn Fn() -> (u32, i32) + Send + Sync>,
}

/// Runs sessions over one link until the link itself fails, and returns why.
/// The caller reconnects the link; nothing here exits the process.
pub fn serve_link<R, W>(mut reader: R, writer: W, config: &Config) -> io::Error
where
    R: BufRead + IdleLimit,
    W: Write + Send + 'static,
{
    let (tx, rx) = mpsc::sync_channel::<Vec<u8>>(WRITE_QUEUE);
    let writer_thread = thread::spawn(move || write_frames(writer, rx));

    let mut next_hello = None;
    let err = loop {
        // Between sessions the phone may take as long as it likes: the app has
        // to be opened, and possibly granted the accessory, by a person.
        reader.set_idle_limit(None);
        let hello = match next_hello.take() {
            Some(h) => h,
            None => match wait_for_hello(&mut reader) {
                Ok(h) => h,
                Err(e) => break e,
            },
        };
        match run_session(&mut reader, &tx, hello, config) {
            Ended::Link(e) => break e,
            Ended::NewHello(h) => next_hello = Some(h),
            Ended::Session => {}
        }
    };

    // The writer exits once every sender is gone, and the session threads that
    // held clones have been joined by now.
    drop(tx);
    let _ = writer_thread.join();
    err
}

fn wait_for_hello(reader: &mut impl Read) -> io::Result<Hello> {
    loop {
        let (hello, discarded) = frame::find_hello(reader)?;
        if discarded > 0 {
            eprintln!("[session] skipped {discarded} byte(s) before HELLO (a previous session's tail)");
        }
        if hello.version == frame::VERSION {
            return Ok(hello);
        }
        // Discarded rather than answered: the app ignores everything before a
        // WELCOME carrying its nonce, so a CLOSE here would go unread anyway.
        eprintln!(
            "[session] ignoring HELLO for protocol version {} (this daemon speaks {})",
            hello.version,
            frame::VERSION
        );
    }
}

enum Ended {
    /// The session is over and the link is fine: wait for the next HELLO.
    Session,
    /// The app started a new session without closing the old one -- a
    /// reconnect from the same open accessory.
    NewHello(Hello),
    Link(io::Error),
}

fn run_session<R: BufRead + IdleLimit>(
    reader: &mut R,
    tx: &SyncSender<Vec<u8>>,
    hello: Hello,
    config: &Config,
) -> Ended {
    eprintln!("[session] HELLO nonce={:016x} caps=0x{:x}", hello.nonce, hello.caps);

    let tcp = match TcpStream::connect_timeout(&config.target, TCP_CONNECT_TIMEOUT) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[session] cannot reach sshd at {}: {e}", config.target);
            let detail = format!("cannot reach sshd at {}: {e}", config.target);
            return send(tx, frame::close(frame::reason::SSHD_REFUSED, &detail));
        }
    };
    let _ = tcp.set_nodelay(true);
    let (tcp_read, mut tcp_write) = match tcp.try_clone() {
        Ok(r) => (r, tcp),
        Err(e) => return send(tx, frame::close(frame::reason::TCP_CLOSED, &e.to_string())),
    };

    if let Ended::Link(e) = send(tx, frame::welcome(hello.nonce, 0, &config.hostname)) {
        return Ended::Link(e);
    }
    eprintln!("[session] WELCOME sent, relaying to {}", config.target);

    let ended = AtomicBool::new(false);
    // Scoped, so the session's threads can borrow `config` and `ended`, and are
    // certainly gone -- along with their channel senders -- when this returns.
    let outcome = thread::scope(|s| {
        let tcp_thread = s.spawn(|| pump_from_sshd(tcp_read, tx.clone(), &ended));
        let ticker = s.spawn(|| tick_status(tx.clone(), &ended, config));

        reader.set_idle_limit(Some(config.heartbeat_timeout));
        let outcome = pump_from_phone(reader, &mut tcp_write, tx, &ended, config.heartbeat_timeout);

        // Set before the shutdown, so the TCP reader sees its read fail as the
        // end it was asked for and does not answer it with a CLOSE of its own.
        ended.store(true, Ordering::SeqCst);
        let _ = tcp_write.shutdown(Shutdown::Both);
        let _ = tcp_thread.join();
        ticker.thread().unpark();
        let _ = ticker.join();
        outcome
    });
    eprintln!("[session] ended");
    outcome
}

/// Phone to sshd, on the calling thread, until the session ends.
fn pump_from_phone(
    reader: &mut impl Read,
    tcp: &mut TcpStream,
    tx: &SyncSender<Vec<u8>>,
    ended: &AtomicBool,
    heartbeat_timeout: Duration,
) -> Ended {
    loop {
        let f = match frame::read_frame(reader) {
            Ok(f) => f,
            Err(e) if matches!(e.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock) => {
                // The app is gone without a word -- killed, or the screen
                // locked under it. The link may well be fine; a CLOSE would
                // only sit unread, so none is sent.
                eprintln!("[session] no frame from the phone for {heartbeat_timeout:?}");
                return Ended::Session;
            }
            Err(e) => return Ended::Link(e),
        };
        match f.kind {
            frame::DATA => {
                if let Err(e) = tcp.write_all(&f.payload) {
                    if !ended.load(Ordering::SeqCst) {
                        eprintln!("[session] write to sshd failed: {e}");
                        return send(tx, frame::close(frame::reason::TCP_CLOSED, &e.to_string()));
                    }
                }
            }
            frame::PING => {}
            frame::CLOSE => {
                eprintln!("[session] app closed the session");
                return Ended::Session;
            }
            frame::HELLO => match Hello::parse(&f.payload) {
                Some(h) if h.version == frame::VERSION => {
                    eprintln!("[session] new HELLO mid-session; starting over");
                    return Ended::NewHello(h);
                }
                _ => {}
            },
            // Unknown types are skipped by length; read_frame already did.
            _ => {}
        }
        // The TCP side finished and already sent CLOSE. Checked after a frame,
        // not before a read, which costs at most one PING interval.
        if ended.load(Ordering::SeqCst) {
            return Ended::Session;
        }
    }
}

/// sshd to phone, on its own thread.
fn pump_from_sshd(mut tcp: TcpStream, tx: SyncSender<Vec<u8>>, ended: &AtomicBool) {
    let mut buf = vec![0u8; frame::MAX_DATA];
    let detail = loop {
        match tcp.read(&mut buf) {
            Ok(0) => break "sshd closed the connection".to_string(),
            Ok(n) => {
                if tx.send(frame::encode(frame::DATA, &buf[..n])).is_err() {
                    return; // the writer is gone, so is the link
                }
            }
            Err(e) => break e.to_string(),
        }
    };
    if !ended.swap(true, Ordering::SeqCst) {
        eprintln!("[session] {detail}");
        let _ = tx.send(frame::close(frame::reason::TCP_CLOSED, &detail));
    }
}

fn tick_status(tx: SyncSender<Vec<u8>>, ended: &AtomicBool, config: &Config) {
    while !ended.load(Ordering::SeqCst) {
        let (throttled, temp) = (config.status)();
        // try_send: a heartbeat stuck behind a full queue is no heartbeat, and
        // the DATA already queued proves liveness just as well.
        match tx.try_send(frame::status(throttled, temp)) {
            Ok(()) | Err(TrySendError::Full(_)) => {}
            Err(TrySendError::Disconnected(_)) => return,
        }
        thread::park_timeout(config.heartbeat_interval);
    }
}

fn write_frames<W: Write>(mut writer: W, rx: Receiver<Vec<u8>>) {
    for f in rx {
        if let Err(e) = writer.write_all(&f).and_then(|_| writer.flush()) {
            // Returning drops the receiver, so the next send anywhere fails and
            // the link is torn down from the reading side.
            eprintln!("[link] write failed: {e}");
            return;
        }
    }
}

fn send(tx: &SyncSender<Vec<u8>>, f: Vec<u8>) -> Ended {
    match tx.send(f) {
        Ok(()) => Ended::Session,
        Err(_) => Ended::Link(io::Error::new(io::ErrorKind::BrokenPipe, "link writer stopped")),
    }
}

/// The whole session loop end to end, with no USB and no real sshd: a TCP pair
/// stands in for the cable (its read timeout behaves like the AOA reader's idle
/// limit), and a listener on loopback stands in for sshd.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::{get_str, read_frame, Frame};
    use std::io::BufReader;
    use std::net::TcpListener;
    use std::time::Instant;

    const BANNER: &[u8] = b"SSH-2.0-fake\r\n";
    const STATUS_FIXTURE: (u32, i32) = (0x50005, 48_312);

    impl IdleLimit for BufReader<TcpStream> {
        fn set_idle_limit(&mut self, limit: Option<Duration>) {
            self.get_ref().set_read_timeout(limit).unwrap();
        }
    }

    /// Accepts any number of connections; each gets the banner and then an
    /// echo, unless `close_after_banner`. Reports each connection's end.
    fn fake_sshd(close_after_banner: bool) -> (SocketAddr, Receiver<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let (done_tx, done_rx) = mpsc::channel();
        thread::spawn(move || {
            for conn in listener.incoming() {
                let mut conn = conn.unwrap();
                let done_tx = done_tx.clone();
                thread::spawn(move || {
                    conn.write_all(BANNER).unwrap();
                    if !close_after_banner {
                        let mut buf = [0u8; 4096];
                        while let Ok(n) = conn.read(&mut buf) {
                            if n == 0 || conn.write_all(&buf[..n]).is_err() {
                                break;
                            }
                        }
                    }
                    let _ = done_tx.send(());
                });
            }
        });
        (addr, done_rx)
    }

    /// Nothing listening: bind, note the port, let it go.
    fn dead_port() -> SocketAddr {
        TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap()
    }

    /// The daemon on one end of a TCP "cable", the test playing the app on the
    /// other.
    fn start(target: SocketAddr, heartbeat_timeout: Duration) -> TcpStream {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let app = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (daemon_side, _) = listener.accept().unwrap();
        thread::spawn(move || {
            let config = Config {
                target,
                hostname: "testpi".into(),
                heartbeat_interval: Duration::from_millis(50),
                heartbeat_timeout,
                status: Box::new(|| STATUS_FIXTURE),
            };
            let reader = BufReader::new(daemon_side.try_clone().unwrap());
            serve_link(reader, daemon_side, &config)
        });
        app.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        app
    }

    fn hello(nonce: u64) -> Vec<u8> {
        Hello { version: frame::VERSION, nonce, caps: 0 }.encode()
    }

    /// The next frame that is not a STATUS heartbeat.
    fn next(app: &mut TcpStream) -> Frame {
        loop {
            let f = read_frame(app).unwrap();
            if f.kind != frame::STATUS {
                return f;
            }
            assert_eq!(f.payload[0..4], STATUS_FIXTURE.0.to_be_bytes());
            assert_eq!(f.payload[4..8], STATUS_FIXTURE.1.to_be_bytes());
        }
    }

    fn expect_welcome(app: &mut TcpStream, nonce: u64) {
        let f = next(app);
        assert_eq!(f.kind, frame::WELCOME, "expected WELCOME, got 0x{:02x}", f.kind);
        assert_eq!(f.payload[0..8], nonce.to_be_bytes());
        assert_eq!(f.payload[8..10], frame::VERSION.to_be_bytes());
        assert_eq!(get_str(&f.payload[14..]).unwrap().0, "testpi");
    }

    /// DATA frames until `want` bytes have arrived; sshd's bytes may be split
    /// across frames however TCP delivered them.
    fn expect_data(app: &mut TcpStream, want: &[u8]) {
        let mut got = Vec::new();
        while got.len() < want.len() {
            let f = next(app);
            assert_eq!(f.kind, frame::DATA, "expected DATA, got 0x{:02x}", f.kind);
            got.extend(f.payload);
        }
        assert_eq!(got, want);
    }

    fn expect_close(app: &mut TcpStream, reason: u8) -> String {
        let f = next(app);
        assert_eq!(f.kind, frame::CLOSE, "expected CLOSE, got 0x{:02x}", f.kind);
        assert_eq!(f.payload[0], reason);
        get_str(&f.payload[1..]).unwrap().0
    }

    #[test]
    fn hello_welcome_and_data_round_trip() {
        let (sshd, _) = fake_sshd(false);
        let mut app = start(sshd, Duration::from_secs(5));

        // A previous session's tail ahead of the HELLO must be skipped.
        app.write_all(&frame::encode(frame::DATA, b"stale")[2..]).unwrap();
        app.write_all(&hello(42)).unwrap();
        expect_welcome(&mut app, 42);
        expect_data(&mut app, BANNER);

        app.write_all(&frame::encode(frame::DATA, b"uname -a\n")).unwrap();
        expect_data(&mut app, b"uname -a\n");

        // Unknown types are skipped by length, without ending the session.
        app.write_all(&frame::encode(0x7F, b"from the future")).unwrap();
        app.write_all(&frame::encode(frame::PING, &[])).unwrap();
        app.write_all(&frame::encode(frame::DATA, b"still here")).unwrap();
        expect_data(&mut app, b"still here");
    }

    #[test]
    fn a_full_size_data_frame_passes_whole() {
        let (sshd, _) = fake_sshd(false);
        let mut app = start(sshd, Duration::from_secs(5));
        app.write_all(&hello(1)).unwrap();
        expect_welcome(&mut app, 1);
        expect_data(&mut app, BANNER);

        let big: Vec<u8> = (0..frame::MAX_DATA).map(|i| (i % 251) as u8).collect();
        app.write_all(&frame::encode(frame::DATA, &big)).unwrap();
        expect_data(&mut app, &big);
    }

    #[test]
    fn close_from_the_app_ends_the_tcp_connection_and_allows_another_session() {
        let (sshd, sshd_done) = fake_sshd(false);
        let mut app = start(sshd, Duration::from_secs(5));
        app.write_all(&hello(1)).unwrap();
        expect_welcome(&mut app, 1);
        expect_data(&mut app, BANNER);

        app.write_all(&frame::close(0, "user closed it")).unwrap();
        sshd_done.recv_timeout(Duration::from_secs(5)).expect("sshd connection was not closed");

        app.write_all(&hello(2)).unwrap();
        expect_welcome(&mut app, 2);
        expect_data(&mut app, BANNER);
    }

    #[test]
    fn a_new_hello_mid_session_starts_a_fresh_one() {
        let (sshd, sshd_done) = fake_sshd(false);
        let mut app = start(sshd, Duration::from_secs(5));
        app.write_all(&hello(1)).unwrap();
        expect_welcome(&mut app, 1);
        expect_data(&mut app, BANNER);

        app.write_all(&hello(2)).unwrap();
        sshd_done.recv_timeout(Duration::from_secs(5)).expect("old sshd connection was not closed");
        expect_welcome(&mut app, 2);
        expect_data(&mut app, BANNER);
    }

    #[test]
    fn sshd_closing_is_reported_as_close() {
        let (sshd, _) = fake_sshd(true);
        let mut app = start(sshd, Duration::from_secs(5));
        app.write_all(&hello(9)).unwrap();
        expect_welcome(&mut app, 9);
        expect_data(&mut app, BANNER);
        let detail = expect_close(&mut app, frame::reason::TCP_CLOSED);
        assert!(detail.contains("closed"), "{detail}");

        // The daemon leaves the session on the next frame from the phone, and
        // is then ready for a new one.
        app.write_all(&frame::encode(frame::PING, &[])).unwrap();
        app.write_all(&hello(10)).unwrap();
        expect_welcome(&mut app, 10);
    }

    #[test]
    fn unreachable_sshd_is_reported_as_close_without_welcome() {
        let mut app = start(dead_port(), Duration::from_secs(5));
        app.write_all(&hello(5)).unwrap();
        let detail = expect_close(&mut app, frame::reason::SSHD_REFUSED);
        assert!(detail.contains("cannot reach sshd"), "{detail}");

        // Still listening for the next attempt.
        app.write_all(&hello(6)).unwrap();
        expect_close(&mut app, frame::reason::SSHD_REFUSED);
    }

    #[test]
    fn a_silent_phone_ends_the_session() {
        let (sshd, sshd_done) = fake_sshd(false);
        let mut app = start(sshd, Duration::from_millis(300));
        app.write_all(&hello(1)).unwrap();
        expect_welcome(&mut app, 1);
        expect_data(&mut app, BANNER);

        // No PING, no anything: the daemon should hang up on sshd by itself.
        let t = Instant::now();
        sshd_done.recv_timeout(Duration::from_secs(5)).expect("silent session was never ended");
        assert!(t.elapsed() >= Duration::from_millis(250), "ended before the timeout: {:?}", t.elapsed());
    }

    #[test]
    fn pings_keep_a_quiet_session_alive() {
        let (sshd, sshd_done) = fake_sshd(false);
        let mut app = start(sshd, Duration::from_millis(300));
        app.write_all(&hello(1)).unwrap();
        expect_welcome(&mut app, 1);
        expect_data(&mut app, BANNER);

        for _ in 0..8 {
            app.write_all(&frame::encode(frame::PING, &[])).unwrap();
            assert!(sshd_done.recv_timeout(Duration::from_millis(100)).is_err(), "session ended despite PINGs");
        }
        app.write_all(&frame::encode(frame::DATA, b"x")).unwrap();
        expect_data(&mut app, b"x");
    }

    /// Real OpenSSH through the pipe: the machine's own sshd as the target, the
    /// `ssh` client on the app's end, this test translating between the
    /// client's plain TCP and frames. Proves key exchange survives the framing
    /// byte for byte. Needs both installed, so opt in:
    ///
    ///     cargo test -- --ignored
    #[test]
    #[ignore]
    fn real_openssh_negotiates_through_the_pipe() {
        let mut app = start("127.0.0.1:22".parse().unwrap(), Duration::from_secs(5));
        app.write_all(&hello(77)).unwrap();
        expect_welcome(&mut app, 77);

        let bridge = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = bridge.local_addr().unwrap().port().to_string();
        let ssh = std::process::Command::new("ssh")
            .args(["-v", "-p", &port, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no"])
            .args(["-o", "UserKnownHostsFile=/dev/null", "-o", "ConnectTimeout=10"])
            .args(["127.0.0.1", "true"])
            .stderr(std::process::Stdio::piped())
            .spawn()
            .unwrap();
        let (client, _) = bridge.accept().unwrap();

        let (mut to_client, mut from_client) = (client.try_clone().unwrap(), client);
        let mut app_write = app.try_clone().unwrap();
        thread::spawn(move || {
            let mut buf = [0u8; frame::MAX_DATA];
            while let Ok(n) = from_client.read(&mut buf) {
                if n == 0 || app_write.write_all(&frame::encode(frame::DATA, &buf[..n])).is_err() {
                    break;
                }
            }
            let _ = app_write.write_all(&frame::close(0, "client done"));
        });
        thread::spawn(move || {
            app.set_read_timeout(None).unwrap();
            while let Ok(f) = read_frame(&mut app) {
                match f.kind {
                    frame::DATA => {
                        if to_client.write_all(&f.payload).is_err() {
                            break;
                        }
                    }
                    frame::CLOSE => break,
                    _ => {}
                }
            }
            let _ = to_client.shutdown(Shutdown::Both);
        });

        let out = ssh.wait_with_output().unwrap();
        let log = String::from_utf8_lossy(&out.stderr);
        assert!(log.contains("SSH2_MSG_NEWKEYS received"), "key exchange did not complete:\n{log}");
        // Either outcome of authentication means the encrypted transport worked.
        assert!(
            log.contains("Authenticated to") || log.contains("Permission denied"),
            "never reached authentication:\n{log}"
        );
    }

    #[test]
    fn wrong_version_is_ignored_until_a_right_one() {
        let (sshd, _) = fake_sshd(false);
        let mut app = start(sshd, Duration::from_secs(5));
        app.write_all(&Hello { version: 99, nonce: 1, caps: 0 }.encode()).unwrap();
        app.write_all(&hello(2)).unwrap();
        expect_welcome(&mut app, 2);
    }
}
