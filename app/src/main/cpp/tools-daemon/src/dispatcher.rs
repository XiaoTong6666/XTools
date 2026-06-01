use crate::commands::*;
use crate::config::DaemonConfig;
use crate::ipc::IpcHandler;
use crate::logging;
use crate::modules::battery::BatteryModule;
use crate::modules::cpu::{CpuModule, CpuStats};
use crate::modules::device::{gpu_json, thermals_json, DeviceInfo, DeviceModule, GpuInfo};
use crate::modules::memory::MemoryModule;
use crate::modules::process::{summaries_to_json, ProcessInfo, ProcessModule};
use crate::shell::ShellModule;
use crate::sysfs;
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc, Mutex,
};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum WorkingMode {
    Root,
    Adb,
    Basic,
}

impl WorkingMode {
    fn from_str(value: &str) -> Self {
        match value {
            "root" => Self::Root,
            "adb" => Self::Adb,
            _ => Self::Basic,
        }
    }

    fn as_int(self) -> i32 {
        match self {
            Self::Root => 0,
            Self::Adb => 1,
            Self::Basic => 2,
        }
    }
}

#[derive(Clone, Debug)]
struct ChargeStatsSample {
    timestamp_ms: i64,
    capacity: i32,
    current_ma: i32,
    temperature_deci_c: i32,
    power_mw: f32,
}

#[derive(Serialize)]
struct HomeCacheResponse {
    heavy_ready: bool,
    heavy_refresh_scheduled: bool,
}

#[derive(Serialize)]
struct HomeDeviceResponse {
    model: String,
    soc: String,
    kernel: String,
    cpu_cores: i32,
    total_ram_mb: i64,
    focused_activity: String,
    gpu: Value,
    thermals: Value,
}

#[derive(Serialize)]
struct HomeSnapshotResponse {
    cpu: Value,
    memory: Value,
    battery: Value,
    cache: HomeCacheResponse,
    device: HomeDeviceResponse,
    processes: Value,
}

#[derive(Serialize)]
struct CpuControlSnapshotResponse {
    cpu: Value,
    cluster_info: Value,
    available_freqs: Value,
    available_governors: Value,
    current_freqs: Value,
    current_min_freqs: Value,
    current_max_freqs: Value,
    current_governors: Value,
    gpu: Value,
    core_online: Vec<bool>,
}

#[derive(Serialize)]
struct ChargeStatsPeaks {
    current_ma: i32,
    temperature_deci_c: i32,
    power_mw: f32,
    voltage_mv: i32,
    charge_current_limit_ua: i32,
}

#[derive(Serialize)]
struct ChargeStatsSampleResponse {
    timestamp_ms: i64,
    capacity: i32,
    current_ma: i32,
    temperature_deci_c: i32,
    power_mw: f32,
}

#[derive(Serialize)]
struct ChargeStatsResponse {
    session_active: bool,
    status: String,
    charge_type: String,
    sample_count: i32,
    session_elapsed_ms: i64,
    peaks: ChargeStatsPeaks,
    samples: Vec<ChargeStatsSampleResponse>,
}

#[derive(Clone, Serialize)]
struct MemoryJobResponse {
    job_id: u64,
    operation: String,
    state: String,
    error: String,
}

pub struct Dispatcher {
    config: DaemonConfig,
    mode: WorkingMode,
    shell: ShellModule,
    cpu: CpuModule,
    memory: MemoryModule,
    battery: BatteryModule,
    process: ProcessModule,
    device: DeviceModule,
    shutdown_requested: bool,
    memory_jobs: Arc<Mutex<HashMap<u64, MemoryJobResponse>>>,
    next_memory_job_id: AtomicU64,

    home_heavy_cache_valid: bool,
    home_heavy_cache_at: Option<Instant>,
    cached_home_device_info: DeviceInfo,
    cached_home_focused_activity: String,
    cached_home_processes: Vec<ProcessInfo>,
    home_snapshot_cache_valid: bool,
    home_snapshot_cache_at: Option<Instant>,
    cached_home_snapshot_json: String,
    home_heavy_cache_refresh_pending: bool,

    cpu_control_static_cache_valid: bool,
    cpu_control_static_cache_at: Option<Instant>,
    cached_cpu_control_static_json: String,

    charge_stats_session_active: bool,
    charge_stats_session_started_at: Option<Instant>,
    charge_stats_peak_current_ma: i32,
    charge_stats_peak_temp_deci_c: i32,
    charge_stats_peak_power_mw: f32,
    charge_stats_peak_voltage_mv: i32,
    charge_stats_peak_charge_current_limit_ua: i32,
    charge_stats_sample_count: i32,
    charge_stats_last_status: String,
    charge_stats_last_charge_type: String,
    charge_stats_recent_samples: Vec<ChargeStatsSample>,

    last_invalidate_tick: Instant,
    last_heavy_refresh_tick: Instant,
    last_charge_sample_tick: Instant,
}

impl Dispatcher {
    pub fn new(config: DaemonConfig) -> Self {
        let mode = WorkingMode::from_str(&config.mode);
        let now = Instant::now();
        logging::info(&format!(
            "Dispatcher initialize config: mode={} socket=@{} allowed_uid={} token_configured={} version={} build_time={} protocol={} features={}",
            config.mode,
            config.socket_name,
            config.allowed_uid,
            !config.session_token.is_empty(),
            config.daemon_version,
            config.build_time,
            config.protocol_version,
            config.feature_flags.len(),
        ));
        Self {
            config,
            mode,
            shell: ShellModule::default(),
            cpu: CpuModule::new(),
            memory: MemoryModule::new(),
            battery: BatteryModule::new(),
            process: ProcessModule::new(),
            device: DeviceModule::new(),
            shutdown_requested: false,
            memory_jobs: Arc::new(Mutex::new(HashMap::new())),
            next_memory_job_id: AtomicU64::new(1),

            home_heavy_cache_valid: false,
            home_heavy_cache_at: None,
            cached_home_device_info: empty_device_info(),
            cached_home_focused_activity: String::new(),
            cached_home_processes: Vec::new(),
            home_snapshot_cache_valid: false,
            home_snapshot_cache_at: None,
            cached_home_snapshot_json: String::new(),
            home_heavy_cache_refresh_pending: true,

            cpu_control_static_cache_valid: false,
            cpu_control_static_cache_at: None,
            cached_cpu_control_static_json: String::new(),

            charge_stats_session_active: false,
            charge_stats_session_started_at: None,
            charge_stats_peak_current_ma: -1,
            charge_stats_peak_temp_deci_c: -1,
            charge_stats_peak_power_mw: -1.0,
            charge_stats_peak_voltage_mv: -1,
            charge_stats_peak_charge_current_limit_ua: -1,
            charge_stats_sample_count: 0,
            charge_stats_last_status: String::new(),
            charge_stats_last_charge_type: String::new(),
            charge_stats_recent_samples: Vec::new(),

            last_invalidate_tick: now,
            last_heavy_refresh_tick: now,
            last_charge_sample_tick: now,
        }
    }

    fn execute_local(&mut self, command_type: &str, args: &str) -> String {
        match command_type {
            "exec-shell" => self.cmd_exec_shell(args, false),
            "exec-shell-root" => self.cmd_exec_shell(args, true),
            "get-kernel-prop" => self.cmd_get_kernel_prop(args),
            "set-kernel-prop" => self.cmd_set_kernel_prop(args),
            "text-write" => sysfs::text_write(args),
            "set-working-mode" => self.cmd_set_working_mode(args),
            "get-kernel-props" => sysfs::get_props(args),
            "set-kernel-props" => sysfs::set_props(args),
            "path-list" => sysfs::path_list(args),
            "compact-memory" => result_json(self.memory.compact_memory(), "write_failed"),
            "set-selinux-enforce" => self.cmd_set_selinux_enforce(args),
            "restore-hidden-app" => self.cmd_restore_hidden_app(args),
            "create-swapfile" => self.cmd_create_swapfile(args),
            "swap-create-start" => self.cmd_start_swap_create(args),
            "swap-set-priority" => self.cmd_swap_set_priority(args),
            "swap-priority-start" => self.cmd_start_swap_priority(args),
            "swap-disable-start" => self.cmd_start_swap_disable(args),
            "zram-resize-start" => self.cmd_start_zram_resize(args),
            "zram-disable-start" => self.cmd_start_zram_disable(),
            "memory-job-status" => self.cmd_memory_job_status(args),
            "drop-caches-level" => self.cmd_drop_caches(args),
            "backup-partition" => self.cmd_backup_partition(args),
            "flash-partition" => self.cmd_flash_partition(args),
            "shell-delayed-add" => self.cmd_shell_delayed_add(args),
            "shell-delayed-remove" => self.cmd_shell_delayed_remove(args),
            "device-id" => self.cmd_device_id(),
            "exit" => {
                logging::info("Dispatcher: shutdown requested via exit command");
                self.shutdown_requested = true;
                to_json(&ResultResponse { result: "shutdown" })
            }
            "stop" => to_json(&ResultResponse { result: "stopped" }),
            "clr" => to_json(&ResultResponse { result: "cleared" }),
            "dumpsys" => self.cmd_dumpsys(args),
            "ping" => self.cmd_ping(),
            "focused-activity" => self.process.focused_activity(&self.shell),
            "home-snapshot" => self.build_home_snapshot_json(),
            "cpu-control-snapshot" => self.build_cpu_control_snapshot_json(),
            "cpu-cycles" => self.cpu.get_time_in_state(),
            "set-governor" => self.cmd_set_governor(args),
            "set-cpu-freq" => self.cmd_set_cpu_freq(args),
            "core-ctl" => self.cmd_core_ctl(args),
            "cpu-affinity" => self.cmd_cpu_affinity(args),
            "set-cpu-affinity" => self.cmd_set_cpu_affinity(args),
            "swap-snapshot" => self.memory.get_swap_snapshot_json(),
            "zram-info" => self.memory.get_zram_info_json(),
            "resize-zram" => self.cmd_resize_zram(args),
            "zram-disable" => result_json(self.memory.disable_zram(&self.shell), "failed"),
            "swap-off" => self.cmd_swap_disable(args),
            "swap-disable" => self.cmd_swap_disable(args),
            "set-swappiness" => self.cmd_set_swappiness(args),
            "set-vm-parameters" => self.cmd_set_vm_parameters(args),
            "set-extra-free-kbytes" => self.cmd_set_extra_free_kbytes(args),
            "drop-caches" => result_json(self.memory.drop_caches(3), "failed"),
            "battery-info" => self.battery.get_info_json(),
            "battery-stats" => self.build_charge_stats_json(false),
            "battery-stats-reset" => self.build_charge_stats_json(true),
            "process-list" => self.process.list_json(),
            "process-detail" => self.cmd_process_detail(args),
            "device-info" => self.device.get_info_json(),
            "thermal-info" => thermals_json(&self.device.get_thermal_zones()).to_string(),
            "process-threads" => self.cmd_process_threads(args),
            "process-kill" => self.cmd_process_kill(args),
            "set-oom-adj" => self.cmd_set_oom_adj(args),
            "set-charge-current" => self.cmd_set_charge_current(args),
            "battery-charge-enable" => self.cmd_battery_charge_enable(args),
            "set-watermark-scale-factor" => self.cmd_set_watermark_scale_factor(args),
            "set-watermark-boost" => self.cmd_set_watermark_boost(args),
            "set-dirty-ratio" => self.cmd_set_dirty_ratio(args),
            "cpu-threads" => self.cmd_cpu_threads(args),
            _ => to_json(&OwnedErrorResponse {
                error: format!("unknown command: {command_type}"),
            }),
        }
    }

    fn cmd_exec_shell(&self, args: &str, force_root: bool) -> String {
        let (cmd, timeout) = match from_json::<ExecShellArgs>(args) {
            Ok(parsed) if !parsed.cmd.is_empty() => (parsed.cmd, parsed.timeout),
            _ => (args.to_string(), 5000),
        };
        if force_root || self.mode == WorkingMode::Root {
            self.shell.exec_shell_root(&cmd, timeout)
        } else {
            self.shell.exec_shell(&cmd, timeout)
        }
    }

    fn cmd_get_kernel_prop(&self, args: &str) -> String {
        let path = from_json::<KernelPropArgs>(args)
            .ok()
            .filter(|parsed| !parsed.path.is_empty())
            .map(|parsed| parsed.path)
            .unwrap_or_else(|| args.to_string());
        sysfs::get_prop(&path).unwrap_or_default()
    }

    fn cmd_set_kernel_prop(&self, args: &str) -> String {
        if let Ok(parsed) = from_json::<SetKernelPropArgs>(args) {
            if !parsed.path.is_empty() {
                let prop_value = value_to_string(&parsed.value);
                return result_json(sysfs::set_prop(&parsed.path, &prop_value), "write_failed");
            }
        }
        let Some((path, value)) = args.split_once('=') else {
            return error_json("format: {path, value} or path=value");
        };
        result_json(sysfs::set_prop(path, value), "write_failed")
    }

    fn cmd_set_working_mode(&mut self, args: &str) -> String {
        let mode = from_json::<WorkingModeArgs>(args)
            .ok()
            .map(|parsed| parsed.mode)
            .unwrap_or_else(|| args.to_string());
        self.mode = WorkingMode::from_str(&mode);
        logging::info(&format!("Working mode set to {}", mode));
        to_json(&WorkingModeResponse {
            mode: self.mode.as_int().to_string(),
            result: "ok",
        })
    }

    fn cmd_set_selinux_enforce(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<SelinuxArgs>(args) else {
            return error_json("invalid args");
        };
        let result = self.shell.exec_raw_root(
            &["setenforce", if parsed.enforce { "1" } else { "0" }],
            "",
            5000,
        );
        if result.contains("error") {
            error_json("command_failed")
        } else {
            ok_json()
        }
    }

    fn cmd_restore_hidden_app(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<RestoreHiddenAppArgs>(args) else {
            return error_json("invalid args");
        };
        if !is_safe_identifier(&parsed.package) || parsed.user_id < 0 {
            return error_json("invalid args");
        }
        let user_id = parsed.user_id.to_string();
        let commands: Vec<Vec<String>> = vec![
            vec![
                "pm".to_string(),
                "unhide".to_string(),
                parsed.package.clone(),
            ],
            vec![
                "pm".to_string(),
                "enable".to_string(),
                parsed.package.clone(),
            ],
            vec![
                "pm".to_string(),
                "unsuspend".to_string(),
                parsed.package.clone(),
            ],
            vec![
                "pm".to_string(),
                "install-existing".to_string(),
                "--user".to_string(),
                user_id,
                parsed.package.clone(),
            ],
        ];
        let mut output = String::new();
        for argv in commands {
            output.push_str(&self.shell.exec_raw_root_owned(&argv, "", 10_000));
        }
        command_result(output)
    }

    fn cmd_create_swapfile(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<CreateSwapfileArgs>(args) else {
            return error_json("invalid args");
        };
        if let Some(response) = self.swapfile_busy_response() {
            return response;
        }
        result_json(
            self.memory.create_swapfile(
                &self.shell,
                parsed.size_mb,
                parsed.priority,
                parsed.use_loop,
            ),
            "failed",
        )
    }

    fn cmd_start_swap_create(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<CreateSwapfileArgs>(args) else {
            return error_json("invalid args");
        };
        if let Some(response) = self.swapfile_busy_response() {
            return response;
        }
        self.start_memory_job("swap-create", move |memory, shell| {
            memory.create_swapfile(shell, parsed.size_mb, parsed.priority, parsed.use_loop)
        })
    }

    fn cmd_swap_disable(&self, args: &str) -> String {
        let parsed = if args.trim().is_empty() {
            SwapDisableArgs { remove_file: false }
        } else {
            let Ok(parsed) = from_json::<SwapDisableArgs>(args) else {
                return error_json("invalid args");
            };
            parsed
        };
        if let Some(response) = self.swapfile_busy_response() {
            return response;
        }
        result_json(
            self.memory.disable_swap(&self.shell, parsed.remove_file),
            "failed",
        )
    }

    fn cmd_start_swap_disable(&self, args: &str) -> String {
        let parsed = if args.trim().is_empty() {
            SwapDisableArgs { remove_file: false }
        } else {
            let Ok(parsed) = from_json::<SwapDisableArgs>(args) else {
                return error_json("invalid args");
            };
            parsed
        };
        if let Some(response) = self.swapfile_busy_response() {
            return response;
        }
        self.start_memory_job("swap-disable", move |memory, shell| {
            memory.disable_swap(shell, parsed.remove_file)
        })
    }

    fn cmd_swap_set_priority(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<SwapPriorityArgs>(args) else {
            return error_json("invalid args");
        };
        if let Some(response) = self.swapfile_busy_response() {
            return response;
        }
        result_json(
            self.memory
                .set_managed_swap_priority(&self.shell, parsed.priority),
            "failed",
        )
    }

    fn cmd_start_swap_priority(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<SwapPriorityArgs>(args) else {
            return error_json("invalid args");
        };
        if let Some(response) = self.swapfile_busy_response() {
            return response;
        }
        self.start_memory_job("swap-priority", move |memory, shell| {
            memory.set_managed_swap_priority(shell, parsed.priority)
        })
    }

    fn cmd_start_zram_resize(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<ResizeZramArgs>(args) else {
            return error_json("invalid args");
        };
        self.start_memory_job("zram-resize", move |memory, shell| {
            memory.resize_zram(shell, parsed.size_mb, &parsed.algorithm)
        })
    }

    fn cmd_start_zram_disable(&self) -> String {
        self.start_memory_job("zram-disable", move |memory, shell| {
            memory.disable_zram(shell)
        })
    }

    fn cmd_memory_job_status(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<MemoryJobStatusArgs>(args) else {
            return error_json("invalid args");
        };
        let Ok(jobs) = self.memory_jobs.lock() else {
            return error_json("memory_job_unavailable");
        };
        jobs.get(&parsed.job_id)
            .map(to_json)
            .unwrap_or_else(|| error_json("memory_job_not_found"))
    }

    fn swapfile_busy_response(&self) -> Option<String> {
        self.memory
            .swapfile_operation_in_progress()
            .then(|| error_json("swapfile_busy"))
    }

    fn start_memory_job<F>(&self, operation: &str, task: F) -> String
    where
        F: FnOnce(&MemoryModule, &ShellModule) -> bool + Send + 'static,
    {
        let job_id = self.next_memory_job_id.fetch_add(1, Ordering::Relaxed);
        let operation = operation.to_string();
        let thread_operation = operation.clone();
        let jobs = Arc::clone(&self.memory_jobs);
        {
            let Ok(mut state) = jobs.lock() else {
                return error_json("memory_job_unavailable");
            };
            if state.values().any(|job| job.state == "running") {
                return error_json("memory_job_running");
            }
            state.insert(
                job_id,
                MemoryJobResponse {
                    job_id,
                    operation: operation.clone(),
                    state: "running".to_string(),
                    error: String::new(),
                },
            );
        }
        thread::spawn(move || {
            let memory = MemoryModule::new();
            let shell = ShellModule::new();
            let completed = task(&memory, &shell);
            logging::info(&format!(
                "memory-job: id={} operation={} completed={}",
                job_id, thread_operation, completed
            ));
            if let Ok(mut state) = jobs.lock() {
                if let Some(job) = state.get_mut(&job_id) {
                    job.state = if completed { "completed" } else { "failed" }.to_string();
                    if !completed {
                        job.error = "operation_failed".to_string();
                    }
                }
            }
        });
        logging::info(&format!(
            "memory-job: id={} operation={} started",
            job_id, operation
        ));
        to_json(&MemoryJobResponse {
            job_id,
            operation,
            state: "running".to_string(),
            error: String::new(),
        })
    }

    fn cmd_drop_caches(&self, args: &str) -> String {
        let parsed = if args.trim().is_empty() {
            DropCachesArgs { level: 3 }
        } else {
            let Ok(parsed) = from_json::<DropCachesArgs>(args) else {
                return error_json("invalid args");
            };
            parsed
        };
        result_json(self.memory.drop_caches(parsed.level), "failed")
    }

    fn cmd_backup_partition(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<BackupPartitionArgs>(args) else {
            return error_json("invalid args");
        };
        if !is_safe_identifier(&parsed.partition) || parsed.output.is_empty() {
            return error_json("invalid args");
        }
        let argv = vec![
            "dd".to_string(),
            format!("if=/dev/block/by-name/{}", parsed.partition),
            format!("of={}", parsed.output),
        ];
        command_result(self.shell.exec_raw_root_owned(&argv, "", 30_000))
    }

    fn cmd_flash_partition(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<FlashPartitionArgs>(args) else {
            return error_json("invalid args");
        };
        if !is_safe_identifier(&parsed.partition) || parsed.input.is_empty() {
            return error_json("invalid args");
        }
        let argv = vec![
            "dd".to_string(),
            format!("if={}", parsed.input),
            format!("of=/dev/block/by-name/{}", parsed.partition),
        ];
        command_result(self.shell.exec_raw_root_owned(&argv, "", 30_000))
    }

    fn cmd_shell_delayed_add(&mut self, args: &str) -> String {
        if let Ok(parsed) = from_json::<ShellDelayedAddArgs>(args) {
            self.shell.delayed_add(&parsed.cmd, parsed.delay);
        } else {
            self.shell.delayed_add(args, 0);
        }
        ok_json()
    }

    fn cmd_shell_delayed_remove(&mut self, args: &str) -> String {
        if let Ok(parsed) = from_json::<ShellDelayedRemoveArgs>(args) {
            self.shell.delayed_remove(&parsed.id);
        } else {
            self.shell.delayed_remove(args);
        }
        ok_json()
    }

    fn cmd_device_id(&self) -> String {
        to_json(&DeviceIdResponse {
            model: sysfs::get_prop("/sys/devices/soc0/machine")
                .unwrap_or_else(|| "unknown".to_string()),
            cpu_cores: sysfs::get_prop("/sys/devices/system/cpu/kernel_max")
                .unwrap_or_else(|| "unknown".to_string()),
            kernel: sysfs::get_prop("/proc/version").unwrap_or_else(|| "unknown".to_string()),
        })
    }

    fn cmd_dumpsys(&self, args: &str) -> String {
        let service = from_json::<DumpsysArgs>(args)
            .ok()
            .filter(|parsed| !parsed.service.is_empty())
            .map(|parsed| parsed.service)
            .unwrap_or_else(|| args.to_string());
        self.shell.dumpsys(&service)
    }

    fn cmd_ping(&self) -> String {
        to_json(&PingResponse {
            pong: true,
            version: &self.config.daemon_version,
            build_time: &self.config.build_time,
            protocol_version: self.config.protocol_version,
            features: &self.config.feature_flags,
            socket_name: &self.config.socket_name,
            child_process: true,
        })
    }

    fn cmd_set_governor(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<CoreGovernorArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(
            self.cpu.set_governor(parsed.core, &parsed.governor),
            "failed",
        )
    }

    fn cmd_set_cpu_freq(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<CpuFrequencyArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(
            self.cpu.set_frequency(parsed.core, parsed.min, parsed.max),
            "failed",
        )
    }

    fn cmd_core_ctl(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<CoreCtlArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(self.cpu.set_online(parsed.core, parsed.online), "failed")
    }

    fn cmd_cpu_affinity(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<PidArgs>(args) else {
            return error_json("invalid args");
        };
        self.cpu.get_affinity(parsed.pid)
    }

    fn cmd_set_cpu_affinity(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<CpuAffinitySetArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(self.cpu.set_affinity(parsed.pid, &parsed.cpus), "failed")
    }

    fn cmd_resize_zram(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<ResizeZramArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(
            self.memory
                .resize_zram(&self.shell, parsed.size_mb, &parsed.algorithm),
            "failed",
        )
    }

    fn cmd_set_swappiness(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<IntValueArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(self.memory.set_swappiness(parsed.value), "failed")
    }

    fn cmd_set_vm_parameters(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<VmParametersArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(
            self.memory.apply_vm_parameters(
                parsed.swappiness,
                parsed.extra_free_kbytes,
                parsed.watermark_scale_factor,
            ),
            "failed",
        )
    }

    fn cmd_set_extra_free_kbytes(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<KbytesArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(self.memory.set_extra_free_kbytes(parsed.kb), "failed")
    }

    fn cmd_process_detail(&mut self, args: &str) -> String {
        let Ok(parsed) = from_json::<PidArgs>(args) else {
            return error_json("invalid args");
        };
        self.process.get_by_pid_json(parsed.pid)
    }

    fn cmd_process_threads(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<PidArgs>(args) else {
            return error_json("invalid args");
        };
        self.process.get_thread_ids_json(parsed.pid)
    }

    fn cmd_process_kill(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<ProcessKillArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(self.process.kill_process(parsed.pid, parsed.sig), "failed")
    }

    fn cmd_set_oom_adj(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<OomAdjArgs>(args) else {
            return error_json("invalid args");
        };
        self.process.set_oom_adj(parsed.pid, parsed.adj);
        ok_json()
    }

    fn cmd_set_charge_current(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<ChargeCurrentArgs>(args) else {
            return error_json("invalid args");
        };
        self.battery.set_charge_current_max(parsed.ua);
        ok_json()
    }

    fn cmd_battery_charge_enable(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<ChargeEnableArgs>(args) else {
            return error_json("invalid args");
        };
        self.battery.set_charge_enable(parsed.enable);
        ok_json()
    }

    fn cmd_set_watermark_boost(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<FactorArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(self.memory.set_watermark_boost(parsed.factor), "failed")
    }

    fn cmd_set_watermark_scale_factor(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<FactorArgs>(args) else {
            return error_json("invalid args");
        };
        result_json(
            self.memory.set_watermark_scale_factor(parsed.factor),
            "failed",
        )
    }

    fn cmd_set_dirty_ratio(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<DirtyRatioArgs>(args) else {
            return error_json("invalid args");
        };
        let bg_ratio = parsed.bg_ratio.unwrap_or(parsed.ratio / 2);
        self.memory.set_dirty_ratio(parsed.ratio);
        self.memory.set_dirty_background_ratio(bg_ratio);
        ok_json()
    }

    fn cmd_cpu_threads(&self, args: &str) -> String {
        let Ok(parsed) = from_json::<PidArgs>(args) else {
            return error_json("invalid args");
        };
        let tids = self.cpu.get_thread_ids(parsed.pid);
        to_json(&ThreadIdsResponse {
            pid: parsed.pid,
            thread_count: tids.len(),
            threads: tids,
        })
    }

    fn refresh_home_heavy_cache(&mut self) {
        let started_at = Instant::now();
        logging::info("snapshot.home.cache_refresh: begin");
        self.cached_home_device_info = self.device.get_info();
        self.cached_home_focused_activity = self.process.focused_activity(&self.shell);
        self.home_heavy_cache_at = Some(Instant::now());
        self.home_heavy_cache_valid = true;
        self.home_snapshot_cache_valid = false;
        logging::info(&format!(
            "snapshot.home.cache_refresh: elapsed_ms={}",
            started_at.elapsed().as_millis(),
        ));
    }

    fn schedule_home_heavy_cache_refresh(&mut self) {
        self.home_heavy_cache_refresh_pending = true;
    }

    fn build_home_snapshot_json(&mut self) -> String {
        let now = Instant::now();
        if self.home_snapshot_cache_valid
            && self
                .home_snapshot_cache_at
                .map(|at| now.duration_since(at) < Duration::from_millis(650))
                .unwrap_or(false)
        {
            logging::info(&format!(
                "snapshot.home: cached=true bytes={}",
                self.cached_home_snapshot_json.len()
            ));
            return self.cached_home_snapshot_json.clone();
        }

        let started_at = Instant::now();
        let cpu_stats = self.cpu.get_stats();
        let mem_info = self.memory.get_info();
        let battery_info = self.battery.get_info();
        self.cached_home_processes = self.process.top_by_cpu_percent(3);
        let heavy_cache_ttl = Duration::from_millis(8000);
        let mut scheduled_heavy_refresh = false;
        if !self.home_heavy_cache_valid
            || self
                .home_heavy_cache_at
                .map(|at| now.duration_since(at) >= heavy_cache_ttl)
                .unwrap_or(true)
        {
            self.schedule_home_heavy_cache_refresh();
            scheduled_heavy_refresh = true;
        }

        let device = &self.cached_home_device_info;
        let visible_processes = &self.cached_home_processes;
        let response = HomeSnapshotResponse {
            cpu: CpuModule::stats_to_json(&cpu_stats),
            memory: MemoryModule::info_to_json(&mem_info),
            battery: BatteryModule::info_to_json(&battery_info),
            cache: HomeCacheResponse {
                heavy_ready: self.home_heavy_cache_valid,
                heavy_refresh_scheduled: scheduled_heavy_refresh,
            },
            device: HomeDeviceResponse {
                model: device.model.clone(),
                soc: device.soc.clone(),
                kernel: device.kernel.clone(),
                cpu_cores: device.cpu_cores,
                total_ram_mb: device.total_ram_mb,
                focused_activity: self.cached_home_focused_activity.clone(),
                gpu: gpu_json(&device.gpu),
                thermals: thermals_json(&device.thermals),
            },
            processes: summaries_to_json(visible_processes),
        };
        let result = to_json(&response);
        self.cached_home_snapshot_json = result.clone();
        self.home_snapshot_cache_at = Some(Instant::now());
        self.home_snapshot_cache_valid = true;
        logging::info(&format!(
            "snapshot.home: elapsed_ms={} scheduled_heavy_refresh={} heavy_cache_valid={} process_count={} bytes={}",
            started_at.elapsed().as_millis(),
            scheduled_heavy_refresh,
            self.home_heavy_cache_valid,
            visible_processes.len(),
            result.len(),
        ));
        result
    }

    fn refresh_cpu_control_static_cache(&mut self, cpu_stats: &CpuStats) {
        let started_at = Instant::now();
        let mut cluster_info = Vec::new();
        let mut available_freqs = serde_json::Map::new();
        let mut available_governors = serde_json::Map::new();
        let mut current_freqs = serde_json::Map::new();
        let mut current_min_freqs = serde_json::Map::new();
        let mut current_max_freqs = serde_json::Map::new();
        let mut current_governors = serde_json::Map::new();

        let mut index = 0;
        let mut cluster_index = 0;
        while index < cpu_stats.cores.len() {
            let first = &cpu_stats.cores[index];
            let cluster_max = first.max_freq;
            let mut cluster = Vec::new();
            let mut j_index = index;
            while j_index < cpu_stats.cores.len() {
                let current = &cpu_stats.cores[j_index];
                if j_index != index
                    && (current.core != cpu_stats.cores[j_index - 1].core + 1
                        || current.max_freq != cluster_max)
                {
                    break;
                }
                cluster.push(Value::String(current.core.to_string()));
                j_index += 1;
            }
            cluster_info.push(Value::Array(cluster));

            let freq_path = format!(
                "/sys/devices/system/cpu/cpu{}/cpufreq/scaling_available_frequencies",
                first.core
            );
            let gov_path = format!(
                "/sys/devices/system/cpu/cpu{}/cpufreq/scaling_available_governors",
                first.core
            );
            available_freqs.insert(
                cluster_index.to_string(),
                split_whitespace_json(sysfs::get_prop(&freq_path).unwrap_or_default()),
            );
            available_governors.insert(
                cluster_index.to_string(),
                split_whitespace_json(sysfs::get_prop(&gov_path).unwrap_or_default()),
            );
            current_freqs.insert(
                cluster_index.to_string(),
                Value::String(freq_label(first.cur_freq)),
            );
            current_min_freqs.insert(
                cluster_index.to_string(),
                Value::String(freq_label(first.min_freq)),
            );
            current_max_freqs.insert(
                cluster_index.to_string(),
                Value::String(freq_label(first.max_freq)),
            );
            current_governors.insert(
                cluster_index.to_string(),
                Value::String(if first.governor.is_empty() {
                    "—".to_string()
                } else {
                    first.governor.clone()
                }),
            );

            index = j_index;
            cluster_index += 1;
        }

        let gpu = self.device.detect_gpu();
        self.cached_cpu_control_static_json = json!({
            "cluster_info": cluster_info,
            "available_freqs": available_freqs,
            "available_governors": available_governors,
            "current_freqs": available_freqs_object(current_freqs),
            "current_min_freqs": available_freqs_object(current_min_freqs),
            "current_max_freqs": available_freqs_object(current_max_freqs),
            "current_governors": available_freqs_object(current_governors),
            "gpu": gpu_json(&gpu),
        })
        .to_string();
        self.cpu_control_static_cache_at = Some(Instant::now());
        self.cpu_control_static_cache_valid = true;
        logging::info(&format!(
            "snapshot.cpu_control.cache_refresh: elapsed_ms={} clusters={} gpu_freq_count={} bytes={}",
            started_at.elapsed().as_millis(),
            cluster_index,
            gpu.available_freqs.len(),
            self.cached_cpu_control_static_json.len(),
        ));
    }

    fn build_cpu_control_snapshot_json(&mut self) -> String {
        let started_at = Instant::now();
        let cpu_stats = self.cpu.get_stats();
        let now = Instant::now();
        if !self.cpu_control_static_cache_valid
            || self
                .cpu_control_static_cache_at
                .map(|at| now.duration_since(at) >= Duration::from_secs(30))
                .unwrap_or(true)
        {
            self.refresh_cpu_control_static_cache(&cpu_stats);
        }

        let static_json =
            parse_json(&self.cached_cpu_control_static_json).unwrap_or_else(|| json!({}));
        let core_online: Vec<_> = cpu_stats.cores.iter().map(|core| core.online).collect();
        let result = to_json(&CpuControlSnapshotResponse {
            cpu: CpuModule::stats_to_json(&cpu_stats),
            cluster_info: static_json
                .get("cluster_info")
                .cloned()
                .unwrap_or_else(|| json!([])),
            available_freqs: static_json
                .get("available_freqs")
                .cloned()
                .unwrap_or_else(|| json!({})),
            available_governors: static_json
                .get("available_governors")
                .cloned()
                .unwrap_or_else(|| json!({})),
            current_freqs: static_json
                .get("current_freqs")
                .cloned()
                .unwrap_or_else(|| json!({})),
            current_min_freqs: static_json
                .get("current_min_freqs")
                .cloned()
                .unwrap_or_else(|| json!({})),
            current_max_freqs: static_json
                .get("current_max_freqs")
                .cloned()
                .unwrap_or_else(|| json!({})),
            current_governors: static_json
                .get("current_governors")
                .cloned()
                .unwrap_or_else(|| json!({})),
            gpu: static_json.get("gpu").cloned().unwrap_or_else(|| json!({})),
            core_online,
        });
        logging::info(&format!(
            "snapshot.cpu_control: elapsed_ms={} refreshed_static_cache={} cores={} bytes={}",
            started_at.elapsed().as_millis(),
            self.cpu_control_static_cache_at
                .map(|at| started_at <= at)
                .unwrap_or(false),
            cpu_stats.cores.len(),
            result.len(),
        ));
        result
    }

    fn sample_charge_stats(&mut self) {
        let info = self.battery.get_info();
        let charging = info.status == "Charging" || info.status == "Full";
        self.charge_stats_last_status = info.status.clone();
        self.charge_stats_last_charge_type = info.charge_type.clone();

        if charging && !self.charge_stats_session_active {
            self.charge_stats_session_active = true;
            self.charge_stats_session_started_at = Some(Instant::now());
            self.charge_stats_peak_current_ma = -1;
            self.charge_stats_peak_temp_deci_c = -1;
            self.charge_stats_peak_power_mw = -1.0;
            self.charge_stats_peak_voltage_mv = -1;
            self.charge_stats_peak_charge_current_limit_ua = -1;
            self.charge_stats_sample_count = 0;
            self.charge_stats_recent_samples.clear();
        }

        if !charging {
            self.charge_stats_session_active = false;
            return;
        }

        self.charge_stats_peak_current_ma = self.charge_stats_peak_current_ma.max(info.current);
        self.charge_stats_peak_temp_deci_c =
            self.charge_stats_peak_temp_deci_c.max(info.temperature);
        self.charge_stats_peak_voltage_mv = self.charge_stats_peak_voltage_mv.max(info.voltage);
        self.charge_stats_peak_charge_current_limit_ua = self
            .charge_stats_peak_charge_current_limit_ua
            .max(info.charge_current_max);
        self.charge_stats_peak_power_mw = self.charge_stats_peak_power_mw.max(info.power);
        self.charge_stats_sample_count += 1;

        let elapsed_ms = self
            .charge_stats_session_started_at
            .map(|started| started.elapsed().as_millis() as i64)
            .unwrap_or(0);
        self.charge_stats_recent_samples.push(ChargeStatsSample {
            timestamp_ms: elapsed_ms,
            capacity: info.capacity,
            current_ma: info.current,
            temperature_deci_c: info.temperature,
            power_mw: info.power,
        });
        while self.charge_stats_recent_samples.len() > 120 {
            self.charge_stats_recent_samples.remove(0);
        }
    }

    fn build_charge_stats_json(&mut self, reset_after_read: bool) -> String {
        let samples: Vec<_> = self
            .charge_stats_recent_samples
            .iter()
            .map(|sample| ChargeStatsSampleResponse {
                timestamp_ms: sample.timestamp_ms,
                capacity: sample.capacity,
                current_ma: sample.current_ma,
                temperature_deci_c: sample.temperature_deci_c,
                power_mw: sample.power_mw,
            })
            .collect();
        let result = to_json(&ChargeStatsResponse {
            session_active: self.charge_stats_session_active,
            status: self.charge_stats_last_status.clone(),
            charge_type: self.charge_stats_last_charge_type.clone(),
            sample_count: self.charge_stats_sample_count,
            session_elapsed_ms: self
                .charge_stats_session_started_at
                .map(|started| started.elapsed().as_millis() as i64)
                .unwrap_or(0),
            peaks: ChargeStatsPeaks {
                current_ma: self.charge_stats_peak_current_ma,
                temperature_deci_c: self.charge_stats_peak_temp_deci_c,
                power_mw: self.charge_stats_peak_power_mw,
                voltage_mv: self.charge_stats_peak_voltage_mv,
                charge_current_limit_ua: self.charge_stats_peak_charge_current_limit_ua,
            },
            samples,
        });

        if reset_after_read {
            logging::info("battery-stats-reset: resetting session state after read");
            self.charge_stats_session_active = false;
            self.charge_stats_session_started_at = None;
            self.charge_stats_peak_current_ma = -1;
            self.charge_stats_peak_temp_deci_c = -1;
            self.charge_stats_peak_power_mw = -1.0;
            self.charge_stats_peak_voltage_mv = -1;
            self.charge_stats_peak_charge_current_limit_ua = -1;
            self.charge_stats_sample_count = 0;
            self.charge_stats_last_status.clear();
            self.charge_stats_last_charge_type.clear();
            self.charge_stats_recent_samples.clear();
        }
        result
    }
}

impl IpcHandler for Dispatcher {
    fn handle_payload(&mut self, payload: &str) -> String {
        let Some((command_type, args)) = payload.split_once(':') else {
            return error_json("bad_request");
        };
        self.execute_local(command_type, args)
    }

    fn tick(&mut self) {
        let now = Instant::now();
        if now.duration_since(self.last_invalidate_tick) >= Duration::from_millis(4500) {
            self.home_snapshot_cache_valid = false;
            self.cpu_control_static_cache_valid = false;
            self.last_invalidate_tick = now;
        }
        if now.duration_since(self.last_heavy_refresh_tick) >= Duration::from_millis(250) {
            if self.home_heavy_cache_refresh_pending {
                self.home_heavy_cache_refresh_pending = false;
                self.refresh_home_heavy_cache();
            }
            self.last_heavy_refresh_tick = now;
        }
        if now.duration_since(self.last_charge_sample_tick) >= Duration::from_millis(5000) {
            self.sample_charge_stats();
            self.last_charge_sample_tick = now;
        }
    }

    fn should_shutdown(&self) -> bool {
        self.shutdown_requested
    }
}

fn parse_json(raw: &str) -> Option<Value> {
    serde_json::from_str(raw).ok()
}

fn command_result(output: String) -> String {
    if output.contains("error") {
        error_json("command_failed")
    } else {
        ok_json()
    }
}

fn is_safe_identifier(value: &str) -> bool {
    !value.is_empty()
        && value
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-'))
}

fn split_whitespace_json(raw: String) -> Value {
    Value::Array(
        raw.split_whitespace()
            .map(|item| Value::String(item.to_string()))
            .collect(),
    )
}

fn freq_label(value: i64) -> String {
    if value > 0 {
        value.to_string()
    } else {
        "—".to_string()
    }
}

fn available_freqs_object(map: serde_json::Map<String, Value>) -> Value {
    Value::Object(map)
}

fn empty_device_info() -> DeviceInfo {
    DeviceInfo {
        model: String::new(),
        soc: String::new(),
        kernel: String::new(),
        cpu_cores: -1,
        total_ram_mb: -1,
        gpu: GpuInfo {
            vendor: String::new(),
            model: String::new(),
            cur_freq: -1,
            min_freq: -1,
            max_freq: -1,
            load: -1,
            available_freqs: Vec::new(),
        },
        thermals: Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ipc::IpcHandler;

    fn test_dispatcher() -> Dispatcher {
        Dispatcher::new(DaemonConfig {
            mode: "root".to_string(),
            spawn_guard: true,
            socket_name: "tools_daemon_test".to_string(),
            session_token: "token".to_string(),
            allowed_uid: 1000,
            daemon_process_name: "tools-daemon".to_string(),
            daemon_version: "1.0".to_string(),
            build_time: "1".to_string(),
            protocol_version: 1,
            binary_revision: 3,
            feature_flags: vec![
                "abstract_unix_socket".to_string(),
                "uid_token_auth".to_string(),
            ],
        })
    }

    #[test]
    fn ui_payload_ping_contract() {
        let mut dispatcher = test_dispatcher();
        assert_eq!(
            dispatcher.handle_payload("ping:{}"),
            r#"{"pong":true,"version":"1.0","build_time":"1","protocol_version":1,"features":["abstract_unix_socket","uid_token_auth"],"socket_name":"tools_daemon_test","child_process":true}"#,
        );
    }

    #[test]
    fn ui_payload_bad_request_contract() {
        let mut dispatcher = test_dispatcher();
        assert_eq!(
            dispatcher.handle_payload("ping"),
            r#"{"error":"bad_request"}"#
        );
    }
}
