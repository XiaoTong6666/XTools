use crate::config::DaemonConfig;
use crate::logging;
use serde_json::{json, Value};
use std::io;
use std::mem;
use std::os::fd::RawFd;
use std::ptr;
use std::time::{Duration, Instant};

pub trait IpcHandler {
    fn handle_payload(&mut self, payload: &str) -> String;
    fn tick(&mut self);
    fn should_shutdown(&self) -> bool;
}

struct Fd(RawFd);

impl Fd {
    fn new(fd: RawFd) -> Self {
        Self(fd)
    }

    fn raw(&self) -> RawFd {
        self.0
    }

    fn into_raw(mut self) -> RawFd {
        let fd = self.0;
        self.0 = -1;
        fd
    }
}

impl Drop for Fd {
    fn drop(&mut self) {
        if self.0 >= 0 {
            unsafe {
                libc::close(self.0);
            }
        }
    }
}

pub fn serve<H: IpcHandler>(config: &DaemonConfig, handler: &mut H) -> io::Result<()> {
    let listen_fd = create_listen_socket(&config.socket_name)?;
    let listen_fd = Fd::new(listen_fd);
    let mut last_tick = Instant::now();

    logging::info(&format!(
        "DaemonGuard: child loop started socket=@{}",
        config.socket_name
    ));

    while !handler.should_shutdown() {
        let mut pfd = libc::pollfd {
            fd: listen_fd.raw(),
            events: libc::POLLIN,
            revents: 0,
        };
        let poll_result = unsafe { libc::poll(&mut pfd, 1, 20) };
        if poll_result > 0 && (pfd.revents & libc::POLLIN) != 0 {
            loop {
                let client_fd =
                    unsafe { libc::accept(listen_fd.raw(), ptr::null_mut(), ptr::null_mut()) };
                if client_fd < 0 {
                    let err = io::Error::last_os_error();
                    if is_interrupted_or_would_block(err.raw_os_error()) {
                        break;
                    }
                    logging::warn(&format!("DaemonGuard: accept failed: {err}"));
                    break;
                }
                let client_fd = Fd::new(client_fd);
                set_socket_timeouts(client_fd.raw(), 5000);
                let _ = handle_client(client_fd.raw(), config, handler);
            }
        }

        if last_tick.elapsed() >= Duration::from_millis(20) {
            handler.tick();
            last_tick = Instant::now();
        }
    }

    logging::info("DaemonGuard: child exited");

    Ok(())
}

pub fn probe_socket_alive(config: &DaemonConfig) -> bool {
    let Ok(fd) = connect_socket(&config.socket_name) else {
        return false;
    };
    let fd = Fd::new(fd);
    set_socket_timeouts(fd.raw(), 1000);
    let wire = json!({ "token": config.session_token, "payload": "__probe__" }).to_string();
    let msg_len = (wire.len() as u32).to_ne_bytes();
    if write_fully(fd.raw(), &msg_len).is_err() || write_fully(fd.raw(), wire.as_bytes()).is_err() {
        return false;
    }
    let mut resp_len = [0_u8; 4];
    if read_fully(fd.raw(), &mut resp_len).is_err() {
        return false;
    }
    let resp_len = u32::from_ne_bytes(resp_len) as usize;
    if resp_len == 0 || resp_len >= 4096 {
        return false;
    }
    let mut buffer = vec![0_u8; resp_len];
    read_fully(fd.raw(), &mut buffer).is_ok()
}

fn create_listen_socket(socket_name: &str) -> io::Result<RawFd> {
    if socket_name.is_empty() || socket_name.len() + 1 >= 108 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid socket name",
        ));
    }

    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let fd = Fd::new(fd);

    let flags = unsafe { libc::fcntl(fd.raw(), libc::F_GETFL, 0) };
    if flags >= 0 {
        unsafe {
            libc::fcntl(fd.raw(), libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
    }

    let mut addr: libc::sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    addr.sun_path[0] = 0;
    for (index, byte) in socket_name.as_bytes().iter().enumerate() {
        addr.sun_path[index + 1] = *byte as libc::c_char;
    }

    let len = (mem::size_of::<libc::sa_family_t>() + 1 + socket_name.len()) as libc::socklen_t;
    let bind_result = unsafe {
        libc::bind(
            fd.raw(),
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            len,
        )
    };
    if bind_result < 0 {
        return Err(io::Error::last_os_error());
    }

    if unsafe { libc::listen(fd.raw(), 16) } < 0 {
        return Err(io::Error::last_os_error());
    }

    logging::info(&format!("DaemonGuard: listening on @{}", socket_name));

    Ok(fd.into_raw())
}

fn handle_client<H: IpcHandler>(
    fd: RawFd,
    config: &DaemonConfig,
    handler: &mut H,
) -> io::Result<()> {
    let started_at = Instant::now();
    let peer_uid = match peer_uid(fd) {
        Ok(uid) => uid,
        Err(_) => {
            logging::warn("DaemonGuard: failed to read peer credentials");
            write_response(fd, &error_response("peer_cred_failed"))?;
            return Ok(());
        }
    };
    if config.allowed_uid >= 0 && peer_uid as i32 != config.allowed_uid {
        logging::warn(&format!(
            "DaemonGuard: rejected peer uid={} allowed_uid={}",
            peer_uid, config.allowed_uid
        ));
        write_response(fd, &error_response("unauthorized_peer"))?;
        return Ok(());
    }

    let mut len_buf = [0_u8; 4];
    read_fully(fd, &mut len_buf)?;
    let len = u32::from_ne_bytes(len_buf) as usize;
    if len == 0 || len > 65_536 {
        return Ok(());
    }

    let mut body = vec![0_u8; len];
    read_fully(fd, &mut body)?;
    let request = String::from_utf8_lossy(&body);
    let envelope: Value = match serde_json::from_str(&request) {
        Ok(value) => value,
        Err(_) => {
            write_response(fd, &error_response("bad_envelope"))?;
            return Ok(());
        }
    };

    if !config.session_token.is_empty()
        && envelope
            .get("token")
            .and_then(Value::as_str)
            .unwrap_or_default()
            != config.session_token
    {
        logging::warn(&format!(
            "DaemonGuard: rejected invalid session token from uid={peer_uid}"
        ));
        write_response(fd, &error_response("bad_token"))?;
        return Ok(());
    }

    let payload = envelope
        .get("payload")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if payload == "__probe__" {
        write_response(fd, r#"{"alive":true}"#)?;
        return Ok(());
    }

    let response = handler.handle_payload(payload);
    write_response(fd, &response)?;
    logging::debug(&format!(
        "DaemonGuard: handled type={} req_bytes={} resp_bytes={} elapsed_ms={} uid={}",
        summarize_payload_type(payload),
        len,
        response.len(),
        started_at.elapsed().as_millis(),
        peer_uid,
    ));
    Ok(())
}

fn connect_socket(socket_name: &str) -> io::Result<RawFd> {
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let fd = Fd::new(fd);
    let mut addr: libc::sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    addr.sun_path[0] = 0;
    for (index, byte) in socket_name.as_bytes().iter().enumerate() {
        addr.sun_path[index + 1] = *byte as libc::c_char;
    }
    let len = (mem::size_of::<libc::sa_family_t>() + 1 + socket_name.len()) as libc::socklen_t;
    let result = unsafe {
        libc::connect(
            fd.raw(),
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            len,
        )
    };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(fd.into_raw())
}

fn peer_uid(fd: RawFd) -> io::Result<libc::uid_t> {
    let mut cred: libc::ucred = unsafe { mem::zeroed() };
    let mut len = mem::size_of::<libc::ucred>() as libc::socklen_t;
    let result = unsafe {
        libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut libc::ucred as *mut libc::c_void,
            &mut len,
        )
    };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(cred.uid)
}

fn error_response(error: &str) -> String {
    json!({ "error": error }).to_string()
}

fn summarize_payload_type(payload: &str) -> &str {
    payload
        .split_once(':')
        .map(|(kind, _)| kind)
        .unwrap_or(payload)
}

fn write_response(fd: RawFd, response: &str) -> io::Result<()> {
    let len = (response.len() as u32).to_ne_bytes();
    write_fully(fd, &len)?;
    write_fully(fd, response.as_bytes())
}

fn read_fully(fd: RawFd, buffer: &mut [u8]) -> io::Result<()> {
    let mut total = 0;
    while total < buffer.len() {
        let read = unsafe {
            libc::read(
                fd,
                buffer[total..].as_mut_ptr() as *mut libc::c_void,
                buffer.len() - total,
            )
        };
        if read == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "socket closed",
            ));
        }
        if read < 0 {
            let err = io::Error::last_os_error();
            match err.raw_os_error() {
                Some(libc::EINTR) => continue,
                Some(code) if is_would_block(code) => {
                    poll_fd(fd, libc::POLLIN, 200)?;
                    continue;
                }
                _ => return Err(err),
            }
        }
        total += read as usize;
    }
    Ok(())
}

fn write_fully(fd: RawFd, buffer: &[u8]) -> io::Result<()> {
    let mut total = 0;
    while total < buffer.len() {
        let written = unsafe {
            libc::write(
                fd,
                buffer[total..].as_ptr() as *const libc::c_void,
                buffer.len() - total,
            )
        };
        if written == 0 {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "socket write returned zero",
            ));
        }
        if written < 0 {
            let err = io::Error::last_os_error();
            match err.raw_os_error() {
                Some(libc::EINTR) => continue,
                Some(code) if is_would_block(code) => {
                    poll_fd(fd, libc::POLLOUT, 2000)?;
                    continue;
                }
                _ => return Err(err),
            }
        }
        total += written as usize;
    }
    Ok(())
}

fn poll_fd(fd: RawFd, events: i16, timeout_ms: i32) -> io::Result<()> {
    let mut pfd = libc::pollfd {
        fd,
        events,
        revents: 0,
    };
    let result = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn set_socket_timeouts(fd: RawFd, timeout_ms: i32) {
    let tv = libc::timeval {
        tv_sec: (timeout_ms / 1000) as libc::time_t,
        tv_usec: ((timeout_ms % 1000) * 1000) as libc::suseconds_t,
    };
    unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            &tv as *const libc::timeval as *const libc::c_void,
            mem::size_of_val(&tv) as libc::socklen_t,
        );
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_SNDTIMEO,
            &tv as *const libc::timeval as *const libc::c_void,
            mem::size_of_val(&tv) as libc::socklen_t,
        );
    }
}

fn is_interrupted_or_would_block(code: Option<i32>) -> bool {
    matches!(code, Some(libc::EINTR)) || code.map(is_would_block).unwrap_or(false)
}

fn is_would_block(code: i32) -> bool {
    code == libc::EAGAIN || code == libc::EWOULDBLOCK
}
