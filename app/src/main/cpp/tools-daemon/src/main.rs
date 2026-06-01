mod commands;
mod config;
mod dispatcher;
mod ipc;
mod logging;
mod procfs;
mod shell;
mod sysfs;

mod modules {
    pub mod battery;
    pub mod cpu;
    pub mod ddr;
    pub mod device;
    pub mod memory;
    pub mod process;
}

use config::DaemonConfig;
use dispatcher::Dispatcher;
use std::env;
use std::ffi::CString;
use std::fs::File;
use std::fs::OpenOptions;
use std::io::Read;
use std::os::fd::AsRawFd;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

fn redirect_stdio_to_null() {
    if let Ok(file) = OpenOptions::new().read(true).write(true).open("/dev/null") {
        let fd = file.as_raw_fd();
        unsafe {
            libc::dup2(fd, libc::STDIN_FILENO);
            libc::dup2(fd, libc::STDOUT_FILENO);
            libc::dup2(fd, libc::STDERR_FILENO);
        }
    }
}

fn set_process_name(name: &str) {
    let truncated: String = name.chars().take(15).collect();
    if let Ok(c_name) = CString::new(truncated) {
        unsafe {
            libc::prctl(libc::PR_SET_NAME, c_name.as_ptr(), 0, 0, 0);
        }
    }
}

fn install_signal_handlers() {
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        libc::signal(libc::SIGCHLD, libc::SIG_IGN);
        libc::signal(libc::SIGTERM, libc::SIG_DFL);
    }
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let log_path = args.first().and_then(|exe| {
        let mut path = PathBuf::from(exe);
        path.pop();
        if path.as_os_str().is_empty() {
            None
        } else {
            path.push("daemon.log");
            Some(path)
        }
    });

    logging::init(log_path.as_deref());

    logging::info("tools-daemon-rs: starting external root daemon entry");

    let Some(config_path) = args.get(1) else {
        logging::error("tools-daemon-rs: missing config path, refusing startup");
        std::process::exit(1);
    };

    let mut config_json = String::new();
    match File::open(config_path).and_then(|mut file| file.read_to_string(&mut config_json)) {
        Ok(_) => {}
        Err(err) => {
            logging::error(&format!("tools-daemon-rs: failed to read config: {err}"));
            std::process::exit(1);
        }
    }

    let config = match DaemonConfig::from_json(&config_json) {
        Ok(config) => config,
        Err(err) => {
            logging::warn(&format!(
                "tools-daemon-rs: invalid config, falling back to defaults: {err}"
            ));
            DaemonConfig::default()
        }
    };

    let fork_result = unsafe { libc::fork() };
    if fork_result < 0 {
        logging::error("tools-daemon-rs: fork failed");
        std::process::exit(1);
    }
    if fork_result > 0 {
        logging::info("tools-daemon-rs: waiting for guard socket");
        for _ in 0..40 {
            if ipc::probe_socket_alive(&config) {
                logging::info("tools-daemon-rs: guard socket alive, exiting launcher process");
                std::process::exit(0);
            }
            thread::sleep(Duration::from_millis(50));
        }
        logging::error("tools-daemon-rs: guard socket failed to come alive");
        std::process::exit(1);
    }

    unsafe {
        libc::setsid();
    }
    redirect_stdio_to_null();
    install_signal_handlers();
    set_process_name(&config.daemon_process_name);

    logging::info(&format!(
        "tools-daemon-rs: listening on @{}",
        config.socket_name
    ));

    let mut dispatcher = Dispatcher::new(config.clone());
    if let Err(err) = ipc::serve(&config, &mut dispatcher) {
        logging::error(&format!("tools-daemon-rs: server failed: {err}"));
        std::process::exit(1);
    }
}
