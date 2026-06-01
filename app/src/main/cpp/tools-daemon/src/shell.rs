use std::io::{Read, Write};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::logging;

const MAX_OUTPUT: usize = 1_048_576;

#[derive(Clone, Debug)]
struct DelayedTask {
    id: String,
    cmd: String,
    delay_ms: i64,
}

pub struct ShellModule {
    delayed_tasks: Arc<Mutex<Vec<DelayedTask>>>,
    delayed_running: Arc<AtomicBool>,
    delayed_thread: Option<JoinHandle<()>>,
}

pub struct CommandOutput {
    pub output: String,
}

impl ShellModule {
    pub fn new() -> Self {
        let delayed_tasks = Arc::new(Mutex::new(Vec::new()));
        let delayed_running = Arc::new(AtomicBool::new(true));
        let worker_tasks = Arc::clone(&delayed_tasks);
        let worker_running = Arc::clone(&delayed_running);
        let delayed_thread = thread::spawn(move || delayed_worker(worker_tasks, worker_running));
        Self {
            delayed_tasks,
            delayed_running,
            delayed_thread: Some(delayed_thread),
        }
    }

    pub fn exec_shell(&self, cmd: &str, timeout_ms: u64) -> String {
        run_command(&["sh", "-c", cmd], "", timeout_ms, false, false).output
    }

    pub fn exec_shell_root(&self, cmd: &str, timeout_ms: u64) -> String {
        if !is_root_process() {
            return String::new();
        }
        let started_at = Instant::now();
        logging::info(&format!(
            "exec-shell-root: begin timeout_ms={} cmd={}",
            timeout_ms,
            truncate_cmd(cmd)
        ));
        let output = self.exec_shell(cmd, timeout_ms);
        logging::info(&format!(
            "exec-shell-root: end elapsed_ms={} out_len={} cmd={}",
            started_at.elapsed().as_millis(),
            output.len(),
            truncate_cmd(cmd)
        ));
        output
    }

    pub fn exec_raw(&self, argv: &[&str], stdin_data: &str, timeout_ms: u64) -> String {
        run_command(argv, stdin_data, timeout_ms, true, true).output
    }

    pub fn exec_raw_owned(&self, argv: &[String], stdin_data: &str, timeout_ms: u64) -> String {
        let refs: Vec<&str> = argv.iter().map(String::as_str).collect();
        self.exec_raw(&refs, stdin_data, timeout_ms)
    }

    pub fn exec_raw_root(&self, argv: &[&str], stdin_data: &str, timeout_ms: u64) -> String {
        if !is_root_process() {
            return String::new();
        }
        self.exec_raw(argv, stdin_data, timeout_ms)
    }

    pub fn exec_raw_root_owned(
        &self,
        argv: &[String],
        stdin_data: &str,
        timeout_ms: u64,
    ) -> String {
        if !is_root_process() {
            return String::new();
        }
        self.exec_raw_owned(argv, stdin_data, timeout_ms)
    }

    pub fn delayed_add(&mut self, cmd: &str, delay_ms: u64) -> bool {
        let id = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos().to_string())
            .unwrap_or_else(|_| "0".to_string());
        let mut tasks = self.delayed_tasks.lock().unwrap();
        tasks.push(DelayedTask {
            id,
            cmd: cmd.to_string(),
            delay_ms: delay_ms as i64,
        });
        true
    }

    pub fn delayed_remove(&mut self, id: &str) -> bool {
        let mut tasks = self.delayed_tasks.lock().unwrap();
        let old_len = tasks.len();
        tasks.retain(|task| task.id != id);
        tasks.len() != old_len
    }

    pub fn dumpsys(&self, service: &str) -> String {
        run_command(&["dumpsys", service], "", 10_000, false, false).output
    }
}

impl Default for ShellModule {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for ShellModule {
    fn drop(&mut self) {
        self.delayed_running.store(false, Ordering::Relaxed);
        if let Some(thread) = self.delayed_thread.take() {
            let _ = thread.join();
        }
    }
}

pub fn is_root_process() -> bool {
    unsafe { libc::geteuid() == 0 || libc::getuid() == 0 }
}

fn read_pipe_limited<R: Read + Send + 'static>(
    mut pipe: R,
) -> thread::JoinHandle<std::io::Result<String>> {
    thread::spawn(move || {
        let mut output = Vec::new();
        let mut buf = [0_u8; 4096];
        loop {
            let read = pipe.read(&mut buf)?;
            if read == 0 {
                break;
            }
            if output.len() + read > MAX_OUTPUT {
                output.extend_from_slice(b"[truncated]");
                break;
            }
            output.extend_from_slice(&buf[..read]);
        }
        Ok(String::from_utf8_lossy(&output).into_owned())
    })
}

fn delayed_worker(tasks: Arc<Mutex<Vec<DelayedTask>>>, running: Arc<AtomicBool>) {
    while running.load(Ordering::Relaxed) {
        thread::sleep(Duration::from_millis(100));
        let mut due = Vec::new();
        {
            let mut guard = tasks.lock().unwrap();
            let mut pending = Vec::new();
            for mut task in guard.drain(..) {
                task.delay_ms -= 100;
                if task.delay_ms <= 0 {
                    due.push(task.cmd);
                } else {
                    pending.push(task);
                }
            }
            *guard = pending;
        }
        for cmd in due {
            let _ = run_command(&["sh", "-c", &cmd], "", 30_000, false, false);
        }
    }
}

fn run_command(
    argv: &[&str],
    stdin_data: &str,
    timeout_ms: u64,
    capture_stderr: bool,
    kill_on_timeout: bool,
) -> CommandOutput {
    if argv.is_empty() {
        return CommandOutput {
            output: String::new(),
        };
    }

    let mut command = Command::new(argv[0]);
    command.args(&argv[1..]);
    command.stdout(Stdio::piped());
    command.stderr(if capture_stderr {
        Stdio::piped()
    } else {
        Stdio::null()
    });
    if stdin_data.is_empty() {
        command.stdin(Stdio::null());
    } else {
        command.stdin(Stdio::piped());
    }

    let Ok(mut child) = command.spawn() else {
        return CommandOutput {
            output: String::new(),
        };
    };

    if !stdin_data.is_empty() {
        if let Some(mut stdin) = child.stdin.take() {
            let data = stdin_data.as_bytes().to_vec();
            let _ = thread::spawn(move || {
                let _ = stdin.write_all(&data);
            });
        }
    }

    let stdout_reader = child.stdout.take().map(read_pipe_limited);
    let stderr_reader = child.stderr.take().map(read_pipe_limited);
    let started_at = Instant::now();
    let timed_out = loop {
        if matches!(child.try_wait(), Ok(Some(_))) {
            break false;
        }
        if started_at.elapsed() > Duration::from_millis(timeout_ms) {
            if kill_on_timeout {
                let _ = child.kill();
            }
            let _ = child.wait();
            break true;
        }
        thread::sleep(Duration::from_millis(20));
    };

    let mut output = String::new();
    if let Some(reader) = stdout_reader {
        if let Ok(Ok(text)) = reader.join() {
            output.push_str(&text);
        }
    }
    if capture_stderr {
        if let Some(reader) = stderr_reader {
            if let Ok(Ok(text)) = reader.join() {
                output.push_str(&text);
            }
        }
    }

    if timed_out && !kill_on_timeout {
        logging::warn(&format!(
            "run_command timeout kill_on_timeout=false argv0={} timeout_ms={}",
            argv.first().copied().unwrap_or(""),
            timeout_ms
        ));
        return CommandOutput { output };
    }

    CommandOutput { output }
}

fn truncate_cmd(cmd: &str) -> String {
    const MAX_LEN: usize = 160;
    if cmd.len() <= MAX_LEN {
        cmd.to_string()
    } else {
        format!("{}...", &cmd[..MAX_LEN])
    }
}
