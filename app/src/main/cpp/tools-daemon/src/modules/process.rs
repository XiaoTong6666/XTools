use crate::procfs;
use crate::shell::ShellModule;
use crate::sysfs;
use serde::Serialize;
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::os::unix::fs::MetadataExt;

use crate::commands::{to_json, ThreadIdsResponse};

const PF_KTHREAD: u64 = 0x0020_0000;
const ANDROID_USER_OFFSET: i32 = 100_000;
const ANDROID_APP_START: i32 = 10_000;
const ANDROID_APP_END: i32 = 19_999;
const ANDROID_SDK_SANDBOX_START: i32 = 20_000;
const ANDROID_SDK_SANDBOX_END: i32 = 29_999;
const ANDROID_ISOLATED_START: i32 = 90_000;
const ANDROID_ISOLATED_END: i32 = 99_999;
const SCHED_RESET_ON_FORK: i32 = 0x4000_0000;
const SCHED_OTHER_POLICY: i32 = 0;
const SCHED_FIFO_POLICY: i32 = 1;
const SCHED_RR_POLICY: i32 = 2;
const SCHED_BATCH_POLICY: i32 = 3;
const SCHED_IDLE_POLICY: i32 = 5;
const SCHED_DEADLINE_POLICY: i32 = 6;
const MAX_IO_SAMPLE_INTERVAL_MS: u64 = 5_000;

#[derive(Clone, Debug, Serialize)]
pub struct ProcessInfo {
    pub pid: i32,
    pub ppid: i32,
    pub uid: i32,
    pub name: String,
    pub state: String,
    pub vmsize: i64,
    pub rss_kb: i64,
    pub threads: i64,
    pub oom_adj: i32,
    pub cpu_percent: f32,
    pub cmdline: String,
    pub is_kernel_thread: bool,
    pub is_app_process: bool,
    pub command: String,
    pub user: String,
    pub shared_kb: i64,
    pub swap_kb: i64,
    pub cpuset: String,
    pub cgroup: String,
    pub allowed_cpus: String,
    pub oom_score_adj: i32,
    pub pss_kb: i64,
    pub priority: i64,
    pub nice: i64,
    pub scheduler: String,
    pub selinux_context: String,
    pub elapsed_ms: i64,
    pub io_read_bytes: i64,
    pub io_write_bytes: i64,
    pub io_read_bps: f64,
    pub io_write_bps: f64,
    #[serde(skip)]
    starttime_ticks: u64,
}

#[derive(Serialize)]
struct ProcessListItem<'a> {
    pid: i32,
    uid: i32,
    name: &'a str,
    state: &'a str,
    rss_kb: i64,
    cpu_percent: f32,
    is_app_process: bool,
}

#[derive(Clone, Debug)]
struct PrevCpuSample {
    ticks: u64,
    starttime: u64,
}

#[derive(Clone, Debug)]
struct PrevIoSample {
    read_bytes: u64,
    write_bytes: u64,
    starttime: u64,
    sampled_at_ms: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct ProcessMemoryUsage {
    pss_kb: i64,
    swap_kb: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct ProcessIoCounters {
    read_bytes: u64,
    write_bytes: u64,
}

#[derive(Debug)]
struct ParsedProcStat {
    pid: i32,
    ppid: i32,
    name: String,
    state: String,
    flags: u64,
    cpu_ticks: u64,
    priority: i64,
    nice: i64,
    threads: i64,
    starttime: u64,
    vmsize: i64,
    rss_pages: i64,
}

#[derive(Clone, Debug)]
struct ProcessIdentity {
    starttime: u64,
    uid: i32,
    name: String,
    is_app_process: bool,
}

pub struct ProcessModule {
    prev_cpu_samples: HashMap<i32, PrevCpuSample>,
    prev_io_samples: HashMap<i32, PrevIoSample>,
    process_identities: HashMap<i32, ProcessIdentity>,
    prev_total_cpu_ticks: Option<u64>,
    page_size_bytes: i64,
    clock_ticks_per_second: u64,
    have_smaps_rollup: bool,
}

impl ProcessModule {
    pub fn new() -> Self {
        let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
        let clock_ticks_per_second = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
        Self {
            prev_cpu_samples: HashMap::new(),
            prev_io_samples: HashMap::new(),
            process_identities: HashMap::new(),
            prev_total_cpu_ticks: None,
            page_size_bytes: if page_size > 0 {
                i64::from(page_size)
            } else {
                4096
            },
            clock_ticks_per_second: if clock_ticks_per_second > 0 {
                clock_ticks_per_second as u64
            } else {
                100
            },
            have_smaps_rollup: std::fs::metadata("/proc/self/smaps_rollup").is_ok(),
        }
    }

    pub fn list(&mut self) -> Vec<ProcessInfo> {
        self.scan(true)
    }

    pub fn top_by_cpu_percent(&mut self, limit: usize) -> Vec<ProcessInfo> {
        if limit == 0 {
            return Vec::new();
        }
        let mut top = self.scan(false);
        if top.len() > limit {
            top.select_nth_unstable_by(limit - 1, compare_process_usage);
            top.truncate(limit);
        }
        top.sort_by(compare_process_usage);
        top.truncate(limit);
        for process in &mut top {
            let starttime = self.process_starttime(process.pid);
            self.populate_process_identity(process, starttime);
        }
        top
    }

    pub fn list_json(&mut self) -> String {
        process_list_to_json(&self.list())
    }

    pub fn get_by_pid_json(&mut self, pid: i32) -> String {
        let mut info = self.parse_proc_stat(pid, false, None, 1.0, false);
        self.populate_process_detail_fields(&mut info);
        to_json(&info)
    }

    pub fn get_thread_ids(&self, pid: i32) -> Vec<i32> {
        let mut tids = Vec::new();
        for entry in sysfs::list_dir(&format!("/proc/{pid}/task")) {
            if let Ok(tid) = entry.parse::<i32>() {
                tids.push(tid);
            }
        }
        tids
    }

    pub fn get_thread_ids_json(&self, pid: i32) -> String {
        let tids = self.get_thread_ids(pid);
        to_json(&ThreadIdsResponse {
            pid,
            thread_count: tids.len(),
            threads: tids,
        })
    }

    pub fn set_oom_adj(&self, pid: i32, adj: i32) -> bool {
        sysfs::set_prop(&format!("/proc/{pid}/oom_adj"), &adj.to_string())
    }

    pub fn kill_process(&self, pid: i32, sig: i32) -> bool {
        unsafe { libc::kill(pid, sig) == 0 }
    }

    pub fn focused_activity(&self, shell: &ShellModule) -> String {
        let output = shell.exec_raw_root(&["dumpsys", "activity", "activities"], "", 1_500);
        if output.is_empty() {
            return "{}".to_string();
        }
        output
            .lines()
            .find(|line| line.contains("mResumedActivity"))
            .map(|line| format!("{line}\n"))
            .unwrap_or_default()
    }

    fn list_pids(&self) -> Vec<i32> {
        let Ok(entries) = std::fs::read_dir("/proc") else {
            return Vec::new();
        };
        entries
            .filter_map(Result::ok)
            .filter_map(|entry| entry.file_name().to_str()?.parse::<i32>().ok())
            .collect()
    }

    fn scan(&mut self, include_details: bool) -> Vec<ProcessInfo> {
        let (cpu_period, active_cpus) = self.sample_cpu_period();
        let pids = self.list_pids();
        let mut seen = HashSet::with_capacity(pids.len());
        let mut processes = Vec::with_capacity(pids.len());
        for pid in pids {
            let info = self.parse_proc_stat(pid, include_details, cpu_period, active_cpus, true);
            if info.name.is_empty() || info.pid <= 0 {
                continue;
            }
            seen.insert(info.pid);
            processes.push(info);
        }
        self.prev_cpu_samples.retain(|pid, _| seen.contains(pid));
        self.prev_io_samples.retain(|pid, _| seen.contains(pid));
        self.process_identities.retain(|pid, _| seen.contains(pid));
        processes
    }

    fn sample_cpu_period(&mut self) -> (Option<f64>, f32) {
        let active_cpus = unsafe { libc::sysconf(libc::_SC_NPROCESSORS_ONLN) }.max(1) as f32;
        let Some(total_ticks) = read_total_cpu_ticks() else {
            return (None, active_cpus);
        };
        let period = self.prev_total_cpu_ticks.and_then(|previous| {
            let delta = total_ticks.saturating_sub(previous);
            (delta > 0).then_some(delta as f64 / active_cpus as f64)
        });
        self.prev_total_cpu_ticks = Some(total_ticks);
        (period, active_cpus)
    }

    fn parse_proc_stat(
        &mut self,
        pid: i32,
        include_details: bool,
        cpu_period: Option<f64>,
        active_cpus: f32,
        update_cpu_sample: bool,
    ) -> ProcessInfo {
        let mut info = ProcessInfo {
            pid,
            ppid: -1,
            uid: -1,
            name: String::new(),
            state: String::new(),
            vmsize: -1,
            rss_kb: -1,
            threads: -1,
            oom_adj: i32::MIN,
            cpu_percent: 0.0,
            cmdline: String::new(),
            is_kernel_thread: false,
            is_app_process: false,
            command: String::new(),
            user: String::new(),
            shared_kb: -1,
            swap_kb: -1,
            cpuset: String::new(),
            cgroup: String::new(),
            allowed_cpus: String::new(),
            oom_score_adj: i32::MIN,
            pss_kb: -1,
            priority: i64::MIN,
            nice: i64::MIN,
            scheduler: String::new(),
            selinux_context: String::new(),
            elapsed_ms: -1,
            io_read_bytes: -1,
            io_write_bytes: -1,
            io_read_bps: -1.0,
            io_write_bps: -1.0,
            starttime_ticks: 0,
        };

        let Some(stat) = read_proc_file(&format!("/proc/{pid}/stat")) else {
            return info;
        };
        let Some(parsed) = parse_proc_stat_line(&stat) else {
            return info;
        };
        info.pid = parsed.pid;
        info.ppid = parsed.ppid;
        info.name = parsed.name;
        info.state = parsed.state;
        info.threads = parsed.threads;
        info.priority = parsed.priority;
        info.nice = parsed.nice;
        info.starttime_ticks = parsed.starttime;
        info.is_kernel_thread = parsed.flags & PF_KTHREAD != 0;
        info.vmsize = parsed.vmsize.max(0);
        info.rss_kb = parsed
            .rss_pages
            .max(0)
            .saturating_mul(self.page_size_bytes / 1024);

        if let (Some(previous), Some(period)) = (self.prev_cpu_samples.get(&info.pid), cpu_period) {
            if previous.starttime == parsed.starttime && period > 0.0 {
                info.cpu_percent =
                    calculate_cpu_percent(parsed.cpu_ticks, previous.ticks, period, active_cpus);
            }
        }
        if update_cpu_sample {
            self.prev_cpu_samples.insert(
                info.pid,
                PrevCpuSample {
                    ticks: parsed.cpu_ticks,
                    starttime: parsed.starttime,
                },
            );
        }

        if include_details {
            self.populate_process_identity(&mut info, parsed.starttime);
        }

        info
    }

    fn process_starttime(&self, pid: i32) -> u64 {
        self.prev_cpu_samples
            .get(&pid)
            .map(|sample| sample.starttime)
            .unwrap_or(0)
    }

    fn populate_process_identity(&mut self, process: &mut ProcessInfo, starttime: u64) {
        let cache_valid = self
            .process_identities
            .get(&process.pid)
            .is_some_and(|identity| identity.starttime == starttime);
        if !cache_valid {
            let raw_cmdline = procfs::get_cmdline(process.pid);
            let cmdline_token = primary_cmdline_token(&raw_cmdline);
            let uid = read_process_uid(process.pid);
            self.process_identities.insert(
                process.pid,
                ProcessIdentity {
                    starttime,
                    uid,
                    name: display_process_name(process, &cmdline_token),
                    is_app_process: !process.is_kernel_thread && is_android_app_uid(uid),
                },
            );
        }

        let Some(identity) = self.process_identities.get(&process.pid) else {
            return;
        };
        process.uid = identity.uid;
        process.name.clone_from(&identity.name);
        process.is_app_process = identity.is_app_process;
    }

    fn populate_process_detail_fields(&mut self, process: &mut ProcessInfo) {
        let raw_cmdline = procfs::get_cmdline(process.pid);
        let cmdline_token = primary_cmdline_token(&raw_cmdline);
        let status = read_proc_file(&format!("/proc/{}/status", process.pid)).unwrap_or_default();
        process.uid = parse_status_uid(&status).unwrap_or(-1);
        let display_name = display_process_name(process, &cmdline_token);
        process.name.clone_from(&display_name);
        process.is_app_process = !process.is_kernel_thread && is_android_app_uid(process.uid);
        self.process_identities.insert(
            process.pid,
            ProcessIdentity {
                starttime: process.starttime_ticks,
                uid: process.uid,
                name: display_name,
                is_app_process: process.is_app_process,
            },
        );

        process.cmdline = normalize_cmdline(&raw_cmdline);
        process.command = read_process_command(process.pid, &cmdline_token, &process.name);
        process.oom_adj = read_process_oom_adj(process.pid);
        process.oom_score_adj = read_process_oom_score_adj(process.pid);
        process.shared_kb = read_process_shared_kb(process.pid, self.page_size_bytes);
        process.cpuset = read_proc_file(&format!("/proc/{}/cpuset", process.pid))
            .map(|value| value.trim().to_string())
            .unwrap_or_default();
        process.cgroup = read_proc_file(&format!("/proc/{}/cgroup", process.pid))
            .map(|value| value.trim().to_string())
            .unwrap_or_default();
        process.user = format_process_user(process.uid);
        process.allowed_cpus = parse_status_allowed_cpus(&status).unwrap_or_default();
        let memory = read_process_memory_usage(process.pid, &status, self.have_smaps_rollup);
        process.pss_kb = memory.pss_kb;
        process.swap_kb = memory.swap_kb;
        process.scheduler = read_process_scheduler(process.pid);
        process.selinux_context = read_process_selinux_context(process.pid);
        process.elapsed_ms =
            read_process_elapsed_ms(process.starttime_ticks, self.clock_ticks_per_second);
        self.populate_process_io(process);
    }

    fn populate_process_io(&mut self, process: &mut ProcessInfo) {
        let Some(io) = read_process_io(process.pid) else {
            return;
        };
        let Some(now_ms) = read_boottime_ms() else {
            return;
        };
        process.io_read_bytes = saturating_u64_to_i64(io.read_bytes);
        process.io_write_bytes = saturating_u64_to_i64(io.write_bytes);

        if let Some(previous) = self.prev_io_samples.get(&process.pid) {
            if previous.starttime == process.starttime_ticks {
                let elapsed_ms = now_ms.saturating_sub(previous.sampled_at_ms);
                if (1..=MAX_IO_SAMPLE_INTERVAL_MS).contains(&elapsed_ms) {
                    process.io_read_bps = io.read_bytes.saturating_sub(previous.read_bytes) as f64
                        * 1_000.0
                        / elapsed_ms as f64;
                    process.io_write_bps =
                        io.write_bytes.saturating_sub(previous.write_bytes) as f64 * 1_000.0
                            / elapsed_ms as f64;
                }
            }
        }

        self.prev_io_samples.insert(
            process.pid,
            PrevIoSample {
                read_bytes: io.read_bytes,
                write_bytes: io.write_bytes,
                starttime: process.starttime_ticks,
                sampled_at_ms: now_ms,
            },
        );
    }
}

fn compare_process_usage(a: &ProcessInfo, b: &ProcessInfo) -> std::cmp::Ordering {
    b.cpu_percent
        .partial_cmp(&a.cpu_percent)
        .unwrap_or(std::cmp::Ordering::Equal)
        .then_with(|| b.rss_kb.cmp(&a.rss_kb))
        .then_with(|| a.pid.cmp(&b.pid))
}

fn process_list_to_json(processes: &[ProcessInfo]) -> String {
    let items: Vec<ProcessListItem<'_>> = processes
        .iter()
        .map(|process| ProcessListItem {
            pid: process.pid,
            uid: process.uid,
            name: &process.name,
            state: &process.state,
            rss_kb: process.rss_kb,
            cpu_percent: process.cpu_percent,
            is_app_process: process.is_app_process,
        })
        .collect();
    to_json(&items)
}

fn parse_proc_stat_line(stat: &str) -> Option<ParsedProcStat> {
    let open_comm = stat.find('(')?;
    let close_comm = stat.rfind(')')?;
    if close_comm <= open_comm {
        return None;
    }

    let pid = stat[..open_comm].trim().parse::<i32>().ok()?;
    let name = stat[open_comm + 1..close_comm].to_string();
    let mut fields = stat[close_comm + 1..].split_whitespace();
    let state = fields.next()?.to_string();
    let ppid = fields.next()?.parse::<i32>().ok()?;
    for _ in 0..4 {
        fields.next()?;
    }
    let flags = fields.next()?.parse::<u64>().ok()?;
    for _ in 0..4 {
        fields.next()?;
    }
    let utime = fields.next()?.parse::<u64>().ok()?;
    let stime = fields.next()?.parse::<u64>().ok()?;
    fields.next()?;
    fields.next()?;
    let priority = fields.next()?.parse::<i64>().ok()?;
    let nice = fields.next()?.parse::<i64>().ok()?;
    let threads = fields.next()?.parse::<i64>().ok()?;
    fields.next()?;
    let starttime = fields.next()?.parse::<u64>().ok()?;
    let vmsize = fields.next()?.parse::<i64>().ok()?;
    let rss_pages = fields.next()?.parse::<i64>().ok()?;
    Some(ParsedProcStat {
        pid,
        ppid,
        name,
        state,
        flags,
        cpu_ticks: utime.saturating_add(stime),
        priority,
        nice,
        threads,
        starttime,
        vmsize,
        rss_pages,
    })
}

fn read_total_cpu_ticks() -> Option<u64> {
    let stat = read_proc_file("/proc/stat")?;
    let cpu_line = stat.lines().find(|line| line.starts_with("cpu "))?;
    let mut fields = cpu_line.split_whitespace();
    fields.next()?;
    let mut count = 0;
    let mut total = 0u64;
    for value in fields.take(8) {
        total = total.saturating_add(value.parse::<u64>().ok()?);
        count += 1;
    }
    (count >= 4).then_some(total)
}

fn read_process_uid(pid: i32) -> i32 {
    read_proc_file(&format!("/proc/{pid}/status"))
        .as_deref()
        .and_then(parse_status_uid)
        .or_else(|| {
            std::fs::metadata(format!("/proc/{pid}"))
                .ok()
                .and_then(|metadata| i32::try_from(metadata.uid()).ok())
        })
        .unwrap_or(-1)
}

fn read_process_oom_adj(pid: i32) -> i32 {
    read_proc_file(&format!("/proc/{pid}/oom_adj"))
        .and_then(|value| value.trim().parse::<i32>().ok())
        .unwrap_or(i32::MIN)
}

fn read_process_oom_score_adj(pid: i32) -> i32 {
    read_proc_file(&format!("/proc/{pid}/oom_score_adj"))
        .and_then(|value| value.trim().parse::<i32>().ok())
        .unwrap_or(i32::MIN)
}

fn read_process_shared_kb(pid: i32, page_size_bytes: i64) -> i64 {
    read_proc_file(&format!("/proc/{pid}/statm"))
        .as_deref()
        .and_then(|statm| parse_statm_shared_kb(statm, page_size_bytes))
        .unwrap_or(-1)
}

fn read_process_memory_usage(
    pid: i32,
    status: &str,
    have_smaps_rollup: bool,
) -> ProcessMemoryUsage {
    let rollup_path = format!("/proc/{pid}/smaps_rollup");
    let smaps_path = format!("/proc/{pid}/smaps");
    let memory = if have_smaps_rollup {
        read_smaps_memory_usage(&rollup_path).or_else(|| read_smaps_memory_usage(&smaps_path))
    } else {
        read_smaps_memory_usage(&smaps_path)
    };

    let Some(memory) = memory else {
        return ProcessMemoryUsage {
            pss_kb: -1,
            swap_kb: parse_status_vm_swap_kb(status).unwrap_or(-1),
        };
    };
    ProcessMemoryUsage {
        pss_kb: memory.pss_kb,
        swap_kb: (memory.swap_kb >= 0)
            .then_some(memory.swap_kb)
            .or_else(|| parse_status_vm_swap_kb(status))
            .unwrap_or(-1),
    }
}

fn read_proc_file(path: &str) -> Option<String> {
    std::fs::read_to_string(path).ok()
}

fn parse_status_uid(status: &str) -> Option<i32> {
    parse_status_field(status, "Uid:")?
        .split_whitespace()
        .next()?
        .parse::<i32>()
        .ok()
}

fn parse_status_allowed_cpus(status: &str) -> Option<String> {
    let value = parse_status_field(status, "Cpus_allowed_list:")?;
    (!value.is_empty()).then(|| value.to_string())
}

fn parse_status_vm_swap_kb(status: &str) -> Option<i64> {
    parse_status_kb_field(status, "VmSwap:")
}

fn parse_status_kb_field(status: &str, prefix: &str) -> Option<i64> {
    parse_status_field(status, prefix)?
        .split_whitespace()
        .next()?
        .parse::<i64>()
        .ok()
}

fn parse_status_field<'a>(status: &'a str, prefix: &str) -> Option<&'a str> {
    status
        .lines()
        .find_map(|line| line.strip_prefix(prefix).map(str::trim))
}

fn parse_statm_shared_kb(statm: &str, page_size_bytes: i64) -> Option<i64> {
    let page_size_kb = (page_size_bytes / 1024).max(1);
    let shared_pages = statm.split_whitespace().nth(2)?.parse::<i64>().ok()?;
    Some(shared_pages.max(0).saturating_mul(page_size_kb))
}

fn read_smaps_memory_usage(path: &str) -> Option<ProcessMemoryUsage> {
    let file = File::open(path).ok()?;
    let mut pss_kb = 0i64;
    let mut swap_kb = 0i64;
    let mut saw_pss = false;
    let mut saw_swap = false;
    for line in BufReader::new(file).lines().map_while(Result::ok) {
        if let Some(value) = line.strip_prefix("Pss:") {
            pss_kb = pss_kb.saturating_add(parse_kb_value(value)?);
            saw_pss = true;
        } else if let Some(value) = line.strip_prefix("Swap:") {
            swap_kb = swap_kb.saturating_add(parse_kb_value(value)?);
            saw_swap = true;
        }
    }
    (saw_pss || saw_swap).then_some(ProcessMemoryUsage {
        pss_kb: saw_pss.then_some(pss_kb).unwrap_or(-1),
        swap_kb: saw_swap.then_some(swap_kb).unwrap_or(-1),
    })
}

#[cfg(test)]
fn parse_smaps_memory_usage(smaps: &str) -> Option<ProcessMemoryUsage> {
    let mut pss_kb = 0i64;
    let mut swap_kb = 0i64;
    let mut saw_pss = false;
    let mut saw_swap = false;
    for line in smaps.lines() {
        if let Some(value) = line.strip_prefix("Pss:") {
            pss_kb = pss_kb.saturating_add(parse_kb_value(value)?);
            saw_pss = true;
        } else if let Some(value) = line.strip_prefix("Swap:") {
            swap_kb = swap_kb.saturating_add(parse_kb_value(value)?);
            saw_swap = true;
        }
    }
    (saw_pss || saw_swap).then_some(ProcessMemoryUsage {
        pss_kb: saw_pss.then_some(pss_kb).unwrap_or(-1),
        swap_kb: saw_swap.then_some(swap_kb).unwrap_or(-1),
    })
}

fn parse_kb_value(value: &str) -> Option<i64> {
    value
        .split_whitespace()
        .next()?
        .parse::<i64>()
        .ok()
        .map(|value| value.max(0))
}

fn read_process_scheduler(pid: i32) -> String {
    let policy = unsafe { libc::sched_getscheduler(pid) };
    if policy < 0 {
        return String::new();
    }
    match policy & !SCHED_RESET_ON_FORK {
        SCHED_OTHER_POLICY => "OTHER",
        SCHED_FIFO_POLICY => "FIFO",
        SCHED_RR_POLICY => "RR",
        SCHED_BATCH_POLICY => "BATCH",
        SCHED_IDLE_POLICY => "IDLE",
        SCHED_DEADLINE_POLICY => "DEADLINE",
        _ => "UNKNOWN",
    }
    .to_string()
}

fn read_process_selinux_context(pid: i32) -> String {
    read_proc_file(&format!("/proc/{pid}/attr/current"))
        .map(|value| value.trim().to_string())
        .unwrap_or_default()
}

fn read_process_elapsed_ms(starttime_ticks: u64, clock_ticks_per_second: u64) -> i64 {
    let Some(boottime_ms) = read_boottime_ms() else {
        return -1;
    };
    calculate_process_elapsed_ms(boottime_ms, starttime_ticks, clock_ticks_per_second)
}

fn calculate_process_elapsed_ms(
    boottime_ms: u64,
    starttime_ticks: u64,
    clock_ticks_per_second: u64,
) -> i64 {
    if starttime_ticks == 0 || clock_ticks_per_second == 0 {
        return -1;
    }
    let starttime_ms = starttime_ticks.saturating_mul(1_000) / clock_ticks_per_second;
    saturating_u64_to_i64(boottime_ms.saturating_sub(starttime_ms))
}

fn read_boottime_ms() -> Option<u64> {
    let mut boottime = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    (unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut boottime) } == 0).then(|| {
        let seconds = u64::try_from(boottime.tv_sec).ok()?;
        let nanos = u64::try_from(boottime.tv_nsec).ok()?;
        Some(
            seconds
                .saturating_mul(1_000)
                .saturating_add(nanos / 1_000_000),
        )
    })?
}

fn read_process_io(pid: i32) -> Option<ProcessIoCounters> {
    read_proc_file(&format!("/proc/{pid}/io"))
        .as_deref()
        .and_then(parse_process_io)
}

fn parse_process_io(io: &str) -> Option<ProcessIoCounters> {
    let mut read_bytes = None;
    let mut write_bytes = None;
    for line in io.lines() {
        if let Some(value) = line.strip_prefix("read_bytes:") {
            read_bytes = value.trim().parse::<u64>().ok();
        } else if let Some(value) = line.strip_prefix("write_bytes:") {
            write_bytes = value.trim().parse::<u64>().ok();
        }
    }
    Some(ProcessIoCounters {
        read_bytes: read_bytes?,
        write_bytes: write_bytes?,
    })
}

fn saturating_u64_to_i64(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

fn is_android_app_uid(uid: i32) -> bool {
    if uid < 0 {
        return false;
    }
    let app_id = uid % ANDROID_USER_OFFSET;
    (ANDROID_APP_START..=ANDROID_APP_END).contains(&app_id)
        || (ANDROID_SDK_SANDBOX_START..=ANDROID_SDK_SANDBOX_END).contains(&app_id)
        || (ANDROID_ISOLATED_START..=ANDROID_ISOLATED_END).contains(&app_id)
}

fn calculate_cpu_percent(
    current_ticks: u64,
    previous_ticks: u64,
    period_per_cpu: f64,
    active_cpus: f32,
) -> f32 {
    if period_per_cpu <= 0.0 || active_cpus <= 0.0 {
        return 0.0;
    }
    let delta = current_ticks.saturating_sub(previous_ticks);
    ((delta as f64 / period_per_cpu) * 100.0).min(active_cpus as f64 * 100.0) as f32
}

#[derive(Clone, Debug, Serialize)]
pub struct ProcessSummary {
    pub pid: i32,
    pub name: String,
    pub state: String,
    pub vmsize: i64,
    pub rss_kb: i64,
    pub threads: i64,
    pub cpu_percent: f32,
}

impl From<&ProcessInfo> for ProcessSummary {
    fn from(process: &ProcessInfo) -> Self {
        Self {
            pid: process.pid,
            name: process.name.clone(),
            state: process.state.clone(),
            vmsize: process.vmsize,
            rss_kb: process.rss_kb,
            threads: process.threads,
            cpu_percent: process.cpu_percent,
        }
    }
}

pub fn summaries_to_json(processes: &[ProcessInfo]) -> serde_json::Value {
    let summaries: Vec<ProcessSummary> = processes.iter().map(ProcessSummary::from).collect();
    serde_json::to_value(summaries).unwrap_or_else(|_| serde_json::json!([]))
}

fn primary_cmdline_token(cmdline: &str) -> String {
    let token = cmdline.split('\0').next().unwrap_or_default();
    if token.is_empty() {
        cmdline.to_string()
    } else {
        token.to_string()
    }
}

fn normalize_cmdline(cmdline: &str) -> String {
    cmdline.replace('\0', " ").trim().to_string()
}

fn display_process_name(info: &ProcessInfo, cmdline_token: &str) -> String {
    if !cmdline_token.is_empty() {
        if let Some(name) = cmdline_token.rsplit('/').next() {
            if !name.is_empty() {
                return name.to_string();
            }
        }
        return cmdline_token.to_string();
    }
    info.name.clone()
}

fn read_process_command(pid: i32, cmdline_token: &str, fallback_name: &str) -> String {
    std::fs::read_link(format!("/proc/{pid}/exe"))
        .ok()
        .and_then(|path| path.into_os_string().into_string().ok())
        .filter(|value| !value.is_empty())
        .or_else(|| (!cmdline_token.is_empty()).then(|| cmdline_token.to_string()))
        .unwrap_or_else(|| fallback_name.to_string())
}

fn format_process_user(uid: i32) -> String {
    if uid >= 0 {
        uid.to_string()
    } else {
        String::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn process_info_golden_json() {
        let process = ProcessInfo {
            pid: 10,
            ppid: 1,
            uid: 1000,
            name: "system_server".to_string(),
            state: "S".to_string(),
            vmsize: 1000,
            rss_kb: 200,
            threads: 8,
            oom_adj: 0,
            cpu_percent: 12.0,
            cmdline: "system_server".to_string(),
            is_kernel_thread: false,
            is_app_process: false,
            command: "/system/bin/system_server".to_string(),
            user: "1000".to_string(),
            shared_kb: 100,
            swap_kb: 4,
            cpuset: "/foreground".to_string(),
            cgroup: "2:cpu:/foreground".to_string(),
            allowed_cpus: "0-7".to_string(),
            oom_score_adj: -900,
            pss_kb: 150,
            priority: 20,
            nice: 0,
            scheduler: "OTHER".to_string(),
            selinux_context: "u:r:system_server:s0".to_string(),
            elapsed_ms: 12_345,
            io_read_bytes: 1_024,
            io_write_bytes: 2_048,
            io_read_bps: 512.0,
            io_write_bps: 1_024.0,
            starttime_ticks: 1_000,
        };
        assert_eq!(
            to_json(&process),
            r#"{"pid":10,"ppid":1,"uid":1000,"name":"system_server","state":"S","vmsize":1000,"rss_kb":200,"threads":8,"oom_adj":0,"cpu_percent":12.0,"cmdline":"system_server","is_kernel_thread":false,"is_app_process":false,"command":"/system/bin/system_server","user":"1000","shared_kb":100,"swap_kb":4,"cpuset":"/foreground","cgroup":"2:cpu:/foreground","allowed_cpus":"0-7","oom_score_adj":-900,"pss_kb":150,"priority":20,"nice":0,"scheduler":"OTHER","selinux_context":"u:r:system_server:s0","elapsed_ms":12345,"io_read_bytes":1024,"io_write_bytes":2048,"io_read_bps":512.0,"io_write_bps":1024.0}"#,
        );
        assert_eq!(
            process_list_to_json(&[process]),
            r#"[{"pid":10,"uid":1000,"name":"system_server","state":"S","rss_kb":200,"cpu_percent":12.0,"is_app_process":false}]"#,
        );
    }

    #[test]
    fn process_summary_golden_json() {
        let process = ProcessInfo {
            pid: 10,
            ppid: 1,
            uid: 1000,
            name: "system_server".to_string(),
            state: "S".to_string(),
            vmsize: 1000,
            rss_kb: 200,
            threads: 8,
            oom_adj: 0,
            cpu_percent: 12.0,
            cmdline: "system_server".to_string(),
            is_kernel_thread: false,
            is_app_process: false,
            command: String::new(),
            user: String::new(),
            shared_kb: -1,
            swap_kb: -1,
            cpuset: String::new(),
            cgroup: String::new(),
            allowed_cpus: String::new(),
            oom_score_adj: i32::MIN,
            pss_kb: -1,
            priority: i64::MIN,
            nice: i64::MIN,
            scheduler: String::new(),
            selinux_context: String::new(),
            elapsed_ms: -1,
            io_read_bytes: -1,
            io_write_bytes: -1,
            io_read_bps: -1.0,
            io_write_bps: -1.0,
            starttime_ticks: 0,
        };
        assert_eq!(
            summaries_to_json(&[process]).to_string(),
            r#"[{"cpu_percent":12.0,"name":"system_server","pid":10,"rss_kb":200,"state":"S","threads":8,"vmsize":1000}]"#,
        );
    }

    #[test]
    fn display_process_name_prefers_full_cmdline_token() {
        let process = ProcessInfo {
            pid: 10,
            ppid: 1,
            uid: 1000,
            name: "ndroid.systemui".to_string(),
            state: "S".to_string(),
            vmsize: 1000,
            rss_kb: 200,
            threads: 8,
            oom_adj: 0,
            cpu_percent: 12.0,
            cmdline: String::new(),
            is_kernel_thread: false,
            is_app_process: false,
            command: String::new(),
            user: String::new(),
            shared_kb: -1,
            swap_kb: -1,
            cpuset: String::new(),
            cgroup: String::new(),
            allowed_cpus: String::new(),
            oom_score_adj: i32::MIN,
            pss_kb: -1,
            priority: i64::MIN,
            nice: i64::MIN,
            scheduler: String::new(),
            selinux_context: String::new(),
            elapsed_ms: -1,
            io_read_bytes: -1,
            io_write_bytes: -1,
            io_read_bps: -1.0,
            io_write_bps: -1.0,
            starttime_ticks: 0,
        };

        assert_eq!(
            display_process_name(&process, "com.android.systemui"),
            "com.android.systemui",
        );
    }

    #[test]
    fn parses_proc_stat_fields_after_comm_with_spaces() {
        let parsed = parse_proc_stat_line(
            "123 (worker name) S 1 2 3 4 5 2097152 7 8 9 10 120 30 13 14 20 0 7 0 4567 8192 42",
        )
        .expect("valid stat line");

        assert_eq!(parsed.pid, 123);
        assert_eq!(parsed.ppid, 1);
        assert_eq!(parsed.name, "worker name");
        assert_eq!(parsed.state, "S");
        assert_eq!(parsed.flags, PF_KTHREAD);
        assert_eq!(parsed.cpu_ticks, 150);
        assert_eq!(parsed.priority, 20);
        assert_eq!(parsed.nice, 0);
        assert_eq!(parsed.threads, 7);
        assert_eq!(parsed.starttime, 4567);
        assert_eq!(parsed.vmsize, 8192);
        assert_eq!(parsed.rss_pages, 42);
    }

    #[test]
    fn cpu_percent_uses_system_period_and_allows_multicore_usage() {
        assert_eq!(calculate_cpu_percent(200, 100, 50.0, 8.0), 200.0);
        assert_eq!(calculate_cpu_percent(1000, 0, 10.0, 8.0), 800.0);
    }

    #[test]
    fn classifies_android_application_uids() {
        assert!(is_android_app_uid(10_123));
        assert!(is_android_app_uid(110_123));
        assert!(is_android_app_uid(20_123));
        assert!(is_android_app_uid(99_123));
        assert!(!is_android_app_uid(0));
        assert!(!is_android_app_uid(1000));
        assert!(!is_android_app_uid(-1));
    }

    #[test]
    fn parses_real_uid_from_proc_status() {
        let status = "Name:\tapp_process\nUid:\t10123\t10123\t10123\t10123\nGid:\t10123\t10123\t10123\t10123\n";
        assert_eq!(parse_status_uid(status), Some(10_123));
    }

    #[test]
    fn parses_allowed_cpus_and_vm_swap_from_proc_status() {
        let status = "State:\tS (sleeping)\nCpus_allowed_list:\t0-3\nVmSwap:\t72 kB\n";
        assert_eq!(parse_status_allowed_cpus(status).as_deref(), Some("0-3"));
        assert_eq!(parse_status_vm_swap_kb(status), Some(72));
    }

    #[test]
    fn parses_shared_and_smaps_memory_details() {
        assert_eq!(parse_statm_shared_kb("100 20 9 0 0 0 0", 4096), Some(36));
        assert_eq!(
            parse_smaps_memory_usage("Pss: 12 kB\nPss: 3 kB\nSwap: 72 kB\nSwapPss: 2 kB\n"),
            Some(ProcessMemoryUsage {
                pss_kb: 15,
                swap_kb: 72,
            })
        );
    }

    #[test]
    fn parses_process_io_counters() {
        assert_eq!(
            parse_process_io("rchar: 12\nread_bytes: 1024\nwchar: 34\nwrite_bytes: 2048\n"),
            Some(ProcessIoCounters {
                read_bytes: 1_024,
                write_bytes: 2_048,
            })
        );
    }

    #[test]
    fn calculates_elapsed_time_from_boottime() {
        assert_eq!(calculate_process_elapsed_ms(10_000, 250, 100), 7_500);
        assert_eq!(calculate_process_elapsed_ms(10_000, 0, 100), -1);
    }

    #[test]
    fn normalize_cmdline_replaces_nul_separators() {
        assert_eq!(
            normalize_cmdline("/system/bin/app\0--flag\0"),
            "/system/bin/app --flag"
        );
    }
}
