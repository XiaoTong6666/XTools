use crate::logging;
use crate::shell::ShellModule;
use crate::sysfs;
use serde::Serialize;
use std::fs::{self, OpenOptions};
use std::io;
use std::os::unix::fs::MetadataExt;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::AsRawFd;
use std::time::Instant;

use crate::commands::{error_json, to_json};

const MANAGED_SWAPFILE_PATH: &str = "/data/swapfile";
const MANAGED_SWAPFILE_KERNEL_PATH: &str = "/swapfile";
const WATERMARK_SCALE_FACTOR_PATH: &str = "/proc/sys/vm/watermark_scale_factor";
const LEGACY_LMK_MINFREE_PATH: &str = "/sys/module/lowmemorykiller/parameters/minfree";
const BYTES_PER_MIB: i64 = 1024 * 1024;
const FAST_SWAP_COMMAND_TIMEOUT_MS: u64 = 5_000;
#[cfg(target_os = "android")]
const F2FS_IOC_SET_PIN_FILE: libc::c_int = 0x4004_f50d_u32 as libc::c_int;
#[cfg(not(target_os = "android"))]
const F2FS_IOC_SET_PIN_FILE: libc::c_ulong = 0x4004_f50d;

#[derive(Clone, Debug, Serialize)]
pub struct SwapEntry {
    pub path: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub size_kb: i64,
    pub used_kb: i64,
    pub priority: i32,
    pub is_zram: bool,
    pub is_loop: bool,
    pub is_managed: bool,
}

#[derive(Clone, Debug, Serialize)]
pub struct MemoryInfo {
    #[serde(rename = "mem_total_kb")]
    pub mem_total: i64,
    #[serde(rename = "mem_free_kb")]
    pub mem_free: i64,
    #[serde(rename = "mem_avail_kb")]
    pub mem_avail: i64,
    #[serde(rename = "swap_total_kb")]
    pub swap_total: i64,
    #[serde(rename = "swap_free_kb")]
    pub swap_free: i64,
    #[serde(rename = "cached_kb")]
    pub cached: i64,
    #[serde(rename = "buffers_kb")]
    pub buffers: i64,
    #[serde(rename = "dirty_kb")]
    pub dirty: i64,
    #[serde(rename = "writeback_kb")]
    pub writeback: i64,
}

#[derive(Clone, Debug, Serialize)]
pub struct ZramInfo {
    pub device: String,
    pub disksize: i64,
    pub logical_size_kb: i64,
    pub logical_used_kb: i64,
    pub compr_data_size: i64,
    pub orig_data_size: i64,
    pub mem_used_total: i64,
    pub mem_limit: i64,
    pub mem_used_max: i64,
    pub same_pages: i64,
    pub pages_compacted: i64,
    pub huge_pages: i64,
    pub stats_source: String,
    pub comp_algorithm: String,
    pub current_algorithm: String,
    pub available_algorithms: Vec<String>,
    pub backing_dev: String,
    pub bd_count: i64,
    pub bd_reads: i64,
    pub bd_writes: i64,
    pub writeback_supported: bool,
}

#[derive(Clone, Debug, Serialize)]
pub struct VmStatInfo {
    pub pswpin: i64,
    pub pswpout: i64,
    pub pgscan_kswapd: i64,
    pub pgsteal_kswapd: i64,
    pub pgscan_direct: i64,
    pub pgsteal_direct: i64,
    pub oom_kill: i64,
    pub pgmajfault: i64,
}

#[derive(Clone, Debug, Serialize)]
pub struct LegacyLmkInfo {
    pub supported: bool,
    pub minfree: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct MemoryCapabilities {
    pub loop_swap_supported: bool,
    pub oplus_swappiness_supported: bool,
    pub scene_controller_detected: bool,
}

#[derive(Clone, Debug, Serialize)]
pub struct SwapSnapshot {
    pub memory: MemoryInfo,
    pub zram: ZramInfo,
    pub zram_devices: Vec<ZramInfo>,
    pub active_swaps: Vec<SwapEntry>,
    pub swappiness: i32,
    pub extra_free_kbytes: i64,
    pub extra_free_supported: bool,
    pub watermark_scale_factor: i32,
    pub watermark_boost_factor: i32,
    pub dirty_ratio: i32,
    pub dirty_background_ratio: i32,
    pub vmstat: VmStatInfo,
    pub legacy_lmk: LegacyLmkInfo,
    pub capabilities: MemoryCapabilities,
    pub raw_proc_swaps: String,
}

pub struct MemoryModule;

impl MemoryModule {
    pub fn new() -> Self {
        Self
    }

    pub fn get_info(&self) -> MemoryInfo {
        MemoryInfo {
            mem_total: parse_meminfo_field("MemTotal"),
            mem_free: parse_meminfo_field("MemFree"),
            mem_avail: parse_meminfo_field("MemAvailable"),
            swap_total: parse_meminfo_field("SwapTotal"),
            swap_free: parse_meminfo_field("SwapFree"),
            cached: parse_meminfo_field("Cached"),
            buffers: parse_meminfo_field("Buffers"),
            dirty: parse_meminfo_field("Dirty"),
            writeback: parse_meminfo_field("Writeback"),
        }
    }

    pub fn get_zram_info(&self) -> ZramInfo {
        self.get_zram_info_for_device("zram0", 0, 0)
    }

    fn get_zram_info_for_device(
        &self,
        device: &str,
        logical_size_kb: i64,
        logical_used_kb: i64,
    ) -> ZramInfo {
        let base_path = format!("/sys/block/{device}");
        let algorithms_raw =
            sysfs::get_prop(&format!("{base_path}/comp_algorithm")).unwrap_or_default();
        let mm_stat = sysfs::get_prop(&format!("{base_path}/mm_stat"));
        let stats = mm_stat
            .as_deref()
            .and_then(parse_zram_mm_stat)
            .unwrap_or_else(|| {
                let orig_data_size = parse_prop_long(&format!("{base_path}/orig_data_size"));
                let compr_data_size = parse_prop_long(&format!("{base_path}/compr_data_size"));
                let mem_used_total = parse_prop_long(&format!("{base_path}/mem_used_total"));
                ZramMemoryStats {
                    orig_data_size,
                    compr_data_size,
                    mem_used_total,
                    mem_limit: -1,
                    mem_used_max: -1,
                    same_pages: -1,
                    pages_compacted: -1,
                    huge_pages: -1,
                    source: if orig_data_size >= 0 || compr_data_size >= 0 || mem_used_total >= 0 {
                        "legacy".to_string()
                    } else {
                        "unavailable".to_string()
                    },
                }
            });
        let bd_stats = sysfs::get_prop(&format!("{base_path}/bd_stat"))
            .as_deref()
            .and_then(parse_zram_bd_stat)
            .unwrap_or((-1, -1, -1));
        ZramInfo {
            device: device.to_string(),
            disksize: parse_prop_long(&format!("{base_path}/disksize")),
            logical_size_kb,
            logical_used_kb,
            compr_data_size: stats.compr_data_size,
            orig_data_size: stats.orig_data_size,
            mem_used_total: stats.mem_used_total,
            mem_limit: stats.mem_limit,
            mem_used_max: stats.mem_used_max,
            same_pages: stats.same_pages,
            pages_compacted: stats.pages_compacted,
            huge_pages: stats.huge_pages,
            stats_source: stats.source,
            comp_algorithm: algorithms_raw.clone(),
            current_algorithm: parse_current_zram_algorithm(&algorithms_raw),
            available_algorithms: parse_zram_algorithms(&algorithms_raw),
            backing_dev: sysfs::get_prop(&format!("{base_path}/backing_dev")).unwrap_or_default(),
            bd_count: bd_stats.0,
            bd_reads: bd_stats.1,
            bd_writes: bd_stats.2,
            writeback_supported: sysfs::file_exists(&format!("{base_path}/backing_dev")),
        }
    }

    pub fn get_zram_info_json(&self) -> String {
        to_json(&self.get_zram_info())
    }

    fn read_swaps(&self) -> Result<Vec<SwapEntry>, ()> {
        let raw = fs::read_to_string("/proc/swaps").map_err(|_| ())?;
        let mut entries = parse_swaps(&raw)?;
        self.annotate_managed_swaps(&mut entries);
        Ok(entries)
    }

    pub fn get_swap_snapshot_json(&self) -> String {
        let raw_proc_swaps = match fs::read_to_string("/proc/swaps") {
            Ok(raw) => raw,
            Err(_) => return error_json("read_swaps_failed"),
        };
        let mut active_swaps = match parse_swaps(&raw_proc_swaps) {
            Ok(entries) => entries,
            Err(_) => return error_json("read_swaps_failed"),
        };
        self.annotate_managed_swaps(&mut active_swaps);
        let zram_devices = active_swaps
            .iter()
            .filter_map(|entry| {
                zram_device_name(&entry.path).map(|device| {
                    self.get_zram_info_for_device(device, entry.size_kb, entry.used_kb)
                })
            })
            .collect::<Vec<_>>();
        let zram = aggregate_zram_info(&zram_devices).unwrap_or_else(|| self.get_zram_info());
        to_json(&SwapSnapshot {
            memory: self.get_info(),
            zram,
            zram_devices,
            active_swaps,
            swappiness: parse_prop_int("/proc/sys/vm/swappiness"),
            extra_free_kbytes: parse_prop_long("/proc/sys/vm/extra_free_kbytes"),
            extra_free_supported: sysfs::file_exists("/proc/sys/vm/extra_free_kbytes"),
            watermark_scale_factor: parse_prop_int(WATERMARK_SCALE_FACTOR_PATH),
            watermark_boost_factor: parse_prop_int("/proc/sys/vm/watermark_boost_factor"),
            dirty_ratio: parse_prop_int("/proc/sys/vm/dirty_ratio"),
            dirty_background_ratio: parse_prop_int("/proc/sys/vm/dirty_background_ratio"),
            vmstat: read_vmstat(),
            legacy_lmk: LegacyLmkInfo {
                supported: sysfs::file_exists(LEGACY_LMK_MINFREE_PATH),
                minfree: sysfs::get_prop(LEGACY_LMK_MINFREE_PATH)
                    .map(|value| value.trim().to_string())
                    .unwrap_or_default(),
            },
            capabilities: MemoryCapabilities {
                loop_swap_supported: sysfs::file_exists("/dev/block/loop-control"),
                oplus_swappiness_supported: sysfs::file_exists(
                    "/sys/module/oplus_bsp_zram_opt/parameters/hybridswapd_swappiness",
                ) || sysfs::file_exists(
                    "/proc/oplus_mem/swappiness_para",
                ),
                scene_controller_detected: sysfs::file_exists(
                    "/data/adb/modules/scene_swap_controller",
                ),
            },
            raw_proc_swaps,
        })
    }

    pub fn info_to_json(info: &MemoryInfo) -> serde_json::Value {
        serde_json::to_value(info).unwrap_or_else(|_| serde_json::json!({}))
    }

    fn annotate_managed_swaps(&self, entries: &mut [SwapEntry]) {
        for entry in entries {
            entry.is_managed = is_managed_swapfile_path(&entry.path)
                || (entry.is_loop && self.is_managed_loop_device(&entry.path));
        }
    }

    pub fn swapfile_operation_in_progress(&self) -> bool {
        let Ok(entries) = fs::read_dir("/proc") else {
            return false;
        };
        entries.filter_map(Result::ok).any(|entry| {
            let Some(pid) = entry
                .file_name()
                .to_str()
                .and_then(|name| name.parse::<i32>().ok())
            else {
                return false;
            };
            let Ok(cmdline) = fs::read_to_string(format!("/proc/{pid}/cmdline")) else {
                return false;
            };
            let command = cmdline.replace('\0', " ");
            command.contains(MANAGED_SWAPFILE_PATH)
                && ["swapon", "swapoff", "mkswap", "fallocate"]
                    .iter()
                    .any(|name| command.contains(name))
        })
    }

    fn is_managed_loop_device(&self, path: &str) -> bool {
        let Some(device) = loop_device_name(path) else {
            return false;
        };
        sysfs::get_prop(&format!("/sys/block/{device}/loop/backing_file"))
            .map(|backing_file| is_managed_swapfile_path(backing_file.trim()))
            .unwrap_or(false)
    }

    fn swap_off_zram(&self, shell: &ShellModule) -> bool {
        let _ = shell.exec_raw(&["swapoff", "/dev/block/zram0"], "", 5_000);
        match self.read_swaps() {
            Ok(entries) => !entries.iter().any(|entry| entry.is_zram),
            Err(_) => false,
        }
    }

    fn swap_on_zram(&self, shell: &ShellModule) -> bool {
        if !sysfs::file_exists("/dev/block/zram0")
            && sysfs::get_prop("/sys/class/zram-control/hot_add").is_none()
        {
            return false;
        }
        let _ = shell.exec_raw(&["mkswap", "/dev/block/zram0"], "", 5_000);
        let _ = shell.exec_raw(&["swapon", "-p", "0", "/dev/block/zram0"], "", 5_000);
        match self.read_swaps() {
            Ok(entries) => entries.iter().any(|entry| entry.is_zram),
            Err(_) => false,
        }
    }

    pub fn create_swapfile(
        &self,
        shell: &ShellModule,
        size_mb: i64,
        priority: i32,
        use_loop: bool,
    ) -> bool {
        if size_mb <= 0 || !is_supported_swap_priority(priority) {
            return false;
        }
        if self.swapfile_operation_in_progress() {
            logging::warn("swap-create: another swapfile operation is in progress");
            return false;
        }
        logging::info(&format!(
            "swap-create: size_mb={} priority={} use_loop={}",
            size_mb, priority, use_loop
        ));
        if use_loop && !sysfs::file_exists("/dev/block/loop-control") {
            return false;
        }
        let was_active = match self.read_swaps() {
            Ok(entries) => entries.iter().any(|entry| entry.is_managed),
            Err(_) => return false,
        };
        if was_active {
            if !self.disable_swap(shell, false) {
                return false;
            }
        }
        if swapfile_is_fully_allocated(MANAGED_SWAPFILE_PATH, size_mb) {
            logging::info(&format!("swap-create: reusing size_mb={size_mb} swapfile"));
        } else {
            match fs::remove_file(MANAGED_SWAPFILE_PATH) {
                Ok(_) => {}
                Err(err) if err.kind() == io::ErrorKind::NotFound => {}
                Err(_) => return false,
            }
            if let Err(error) = allocate_swapfile(shell, MANAGED_SWAPFILE_PATH, size_mb) {
                logging::warn(&format!("swap-create: allocation failed: {error}"));
                return false;
            }
        }
        let mkswap_output = shell.exec_raw_root(
            &["mkswap", MANAGED_SWAPFILE_PATH],
            "",
            FAST_SWAP_COMMAND_TIMEOUT_MS,
        );
        let swap_target = if use_loop {
            let Some(loop_device) = self.attach_managed_loop(shell) else {
                return false;
            };
            loop_device
        } else {
            MANAGED_SWAPFILE_PATH.to_string()
        };
        let swapon_output =
            enable_swap(shell, &swap_target, priority, FAST_SWAP_COMMAND_TIMEOUT_MS);
        let enabled = match self.read_swaps() {
            Ok(entries) => entries
                .iter()
                .any(|entry| entry.is_managed && entry.priority == priority),
            Err(_) => false,
        };
        if !enabled && use_loop {
            let _ = shell.exec_raw_root(&["losetup", "-d", &swap_target], "", 30_000);
        }
        if !enabled {
            logging::warn(&format!(
                "swap-create: activation failed target={} mkswap={} swapon={}",
                swap_target,
                mkswap_output.trim(),
                swapon_output.trim(),
            ));
        }
        enabled
    }

    pub fn disable_swap(&self, shell: &ShellModule, remove_file: bool) -> bool {
        if self.swapfile_operation_in_progress() {
            logging::warn("swap-disable: another swapfile operation is in progress");
            return false;
        }
        let was_active = match self.read_swaps() {
            Ok(entries) => entries.into_iter().find(|entry| entry.is_managed),
            Err(_) => return false,
        };
        if let Some(active) = was_active {
            let swap_target = managed_swap_command_target(&active.path);
            logging::info(&format!(
                "swap-disable: reported_path={} target={} remove_file={}",
                active.path, swap_target, remove_file
            ));
            let swapoff_timeout_ms = if active.used_kb == 0 {
                FAST_SWAP_COMMAND_TIMEOUT_MS
            } else {
                30_000
            };
            let swapoff_started = Instant::now();
            let swapoff_output =
                shell.exec_raw_root(&["swapoff", swap_target], "", swapoff_timeout_ms);
            logging::info(&format!(
                "swap-disable: reported_path={} target={} timeout_ms={} elapsed_ms={} output={}",
                active.path,
                swap_target,
                swapoff_timeout_ms,
                swapoff_started.elapsed().as_millis(),
                swapoff_output.trim(),
            ));
            let disabled = match self.read_swaps() {
                Ok(entries) => !entries.iter().any(|entry| entry.is_managed),
                Err(_) => false,
            };
            if !disabled {
                logging::warn(&format!(
                    "swap-disable: target still active output={}",
                    swapoff_output.trim(),
                ));
                return false;
            }
            if active.is_loop {
                let _ = shell.exec_raw_root(&["losetup", "-d", &active.path], "", 30_000);
                if self.is_managed_loop_device(&active.path) {
                    return false;
                }
            }
        }
        if remove_file {
            match fs::remove_file(MANAGED_SWAPFILE_PATH) {
                Ok(_) => {}
                Err(err) if err.kind() == io::ErrorKind::NotFound => {}
                Err(_) => return false,
            }
        }
        true
    }

    pub fn set_managed_swap_priority(&self, shell: &ShellModule, priority: i32) -> bool {
        if !is_supported_swap_priority(priority) {
            return false;
        }
        if self.swapfile_operation_in_progress() {
            logging::warn("swap-priority: another swapfile operation is in progress");
            return false;
        }
        let active = match self.read_swaps() {
            Ok(entries) => entries.into_iter().find(|entry| entry.is_managed),
            Err(_) => return false,
        };
        let Some(active) = active else {
            return false;
        };
        if active.priority == priority {
            return true;
        }

        let swap_target = managed_swap_command_target(&active.path);
        let swapoff_timeout_ms = if active.used_kb == 0 {
            FAST_SWAP_COMMAND_TIMEOUT_MS
        } else {
            30_000
        };
        let _ = shell.exec_raw_root(&["swapoff", swap_target], "", swapoff_timeout_ms);
        let disabled = match self.read_swaps() {
            Ok(entries) => !entries.iter().any(|entry| entry.is_managed),
            Err(_) => false,
        };
        if !disabled {
            return false;
        }
        let _ = enable_swap(shell, swap_target, priority, FAST_SWAP_COMMAND_TIMEOUT_MS);
        match self.read_swaps() {
            Ok(entries) => entries
                .iter()
                .any(|entry| entry.is_managed && entry.priority == priority),
            Err(_) => false,
        }
    }

    pub fn disable_zram(&self, shell: &ShellModule) -> bool {
        let zram_paths = match self.read_swaps() {
            Ok(entries) => entries
                .into_iter()
                .filter(|entry| entry.is_zram)
                .map(|entry| entry.path)
                .collect::<Vec<_>>(),
            Err(_) => return false,
        };
        for path in &zram_paths {
            let _ = shell.exec_raw_root(&["swapoff", path], "", 30_000);
        }
        match self.read_swaps() {
            Ok(entries) => !entries.iter().any(|entry| entry.is_zram),
            Err(_) => false,
        }
    }

    fn attach_managed_loop(&self, shell: &ShellModule) -> Option<String> {
        let output = shell.exec_raw_root(
            &["losetup", "--find", "--show", MANAGED_SWAPFILE_PATH],
            "",
            30_000,
        );
        let device = output
            .lines()
            .map(str::trim)
            .find(|line| loop_device_name(line).is_some())?
            .to_string();
        self.is_managed_loop_device(&device).then_some(device)
    }

    pub fn resize_zram(&self, shell: &ShellModule, size_mb: i64, algorithm: &str) -> bool {
        let Some(size_bytes) = size_mb.checked_mul(BYTES_PER_MIB) else {
            return false;
        };
        if size_mb <= 0 {
            return false;
        }

        let previous_zram = self.get_zram_info();
        let zram_was_active = match self.read_swaps() {
            Ok(entries) => entries.iter().any(|entry| entry.is_zram),
            Err(_) => return false,
        };
        let target_algorithm = if algorithm.is_empty() {
            previous_zram.current_algorithm.as_str()
        } else {
            algorithm
        };
        let can_restore_algorithm = previous_zram
            .available_algorithms
            .iter()
            .any(|item| item == &previous_zram.current_algorithm);
        if target_algorithm.is_empty()
            || (zram_was_active && (previous_zram.disksize <= 0 || !can_restore_algorithm))
        {
            return false;
        }
        if previous_zram.disksize == size_bytes
            && target_algorithm == previous_zram.current_algorithm
            && zram_was_active
        {
            return true;
        }
        if !previous_zram.available_algorithms.is_empty()
            && !previous_zram
                .available_algorithms
                .iter()
                .any(|item| item == target_algorithm)
        {
            return false;
        }

        let previous_swappiness = parse_prop_int("/proc/sys/vm/swappiness");
        if previous_swappiness < 0 || !sysfs::set_prop("/proc/sys/vm/swappiness", "0") {
            return false;
        }
        let _ = shell.exec_raw(&["sync"], "", 5_000);
        let _ = sysfs::set_prop("/proc/sys/vm/drop_caches", "3");
        if !self.swap_off_zram(shell) {
            let _ = restore_swappiness(previous_swappiness);
            return false;
        }
        if !sysfs::set_prop("/sys/block/zram0/reset", "1") {
            if zram_was_active {
                let _ = self.swap_on_zram(shell);
            }
            let _ = restore_swappiness(previous_swappiness);
            return false;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));

        if !self.configure_zram(shell, size_mb, target_algorithm) {
            let _ = self.restore_zram(shell, &previous_zram, zram_was_active);
            let _ = restore_swappiness(previous_swappiness);
            return false;
        }

        restore_swappiness(previous_swappiness)
    }

    fn configure_zram(&self, shell: &ShellModule, size_mb: i64, algorithm: &str) -> bool {
        if !algorithm.is_empty() && !sysfs::set_prop("/sys/block/zram0/comp_algorithm", algorithm) {
            return false;
        }
        if !sysfs::set_prop("/sys/block/zram0/max_comp_streams", "4") {
            return false;
        }
        if !sysfs::set_prop("/sys/block/zram0/disksize", &format!("{size_mb}M")) {
            return false;
        }
        self.swap_on_zram(shell)
    }

    fn restore_zram(&self, shell: &ShellModule, previous: &ZramInfo, was_active: bool) -> bool {
        if previous.disksize <= 0 {
            return true;
        }
        if previous
            .available_algorithms
            .iter()
            .any(|item| item == &previous.current_algorithm)
            && !sysfs::set_prop(
                "/sys/block/zram0/comp_algorithm",
                &previous.current_algorithm,
            )
        {
            return false;
        }
        if !sysfs::set_prop("/sys/block/zram0/max_comp_streams", "4") {
            return false;
        }
        if !sysfs::set_prop("/sys/block/zram0/disksize", &previous.disksize.to_string()) {
            return false;
        }
        !was_active || self.swap_on_zram(shell)
    }

    pub fn set_swappiness(&self, value: i32) -> bool {
        (0..=200).contains(&value) && sysfs::set_prop("/proc/sys/vm/swappiness", &value.to_string())
    }

    pub fn apply_vm_parameters(
        &self,
        swappiness: i32,
        extra_free_kbytes: Option<i64>,
        watermark_scale_factor: Option<i32>,
    ) -> bool {
        if !(0..=200).contains(&swappiness)
            || extra_free_kbytes.is_some_and(|value| value < 0)
            || watermark_scale_factor.is_some_and(|value| !(1..=1000).contains(&value))
        {
            return false;
        }

        let mut writes = vec![(
            "/proc/sys/vm/swappiness",
            swappiness.to_string(),
            sysfs::get_prop("/proc/sys/vm/swappiness"),
        )];
        if let Some(value) = extra_free_kbytes {
            writes.push((
                "/proc/sys/vm/extra_free_kbytes",
                value.to_string(),
                sysfs::get_prop("/proc/sys/vm/extra_free_kbytes"),
            ));
        }
        if let Some(value) = watermark_scale_factor {
            writes.push((
                WATERMARK_SCALE_FACTOR_PATH,
                value.to_string(),
                sysfs::get_prop(WATERMARK_SCALE_FACTOR_PATH),
            ));
        }
        if writes.iter().any(|(_, _, previous)| previous.is_none()) {
            return false;
        }

        let mut applied: Vec<(&str, String)> = Vec::new();
        for (path, value, previous) in writes {
            if !sysfs::set_prop(path, &value) {
                for (rollback_path, rollback_value) in applied.into_iter().rev() {
                    let _ = sysfs::set_prop(rollback_path, &rollback_value);
                }
                return false;
            }
            applied.push((path, previous.expect("validated above").trim().to_string()));
        }
        true
    }

    pub fn set_extra_free_kbytes(&self, kb: i64) -> bool {
        kb >= 0 && sysfs::set_prop("/proc/sys/vm/extra_free_kbytes", &kb.to_string())
    }

    pub fn set_watermark_scale_factor(&self, factor: i32) -> bool {
        if sysfs::file_exists(WATERMARK_SCALE_FACTOR_PATH) {
            sysfs::set_prop(WATERMARK_SCALE_FACTOR_PATH, &factor.to_string())
        } else {
            true
        }
    }

    pub fn set_watermark_boost(&self, factor: i32) -> bool {
        let path = "/proc/sys/vm/watermark_boost_factor";
        if sysfs::file_exists(path) {
            sysfs::set_prop(path, &factor.to_string())
        } else {
            true
        }
    }

    pub fn set_dirty_ratio(&self, ratio: i32) -> bool {
        (0..=100).contains(&ratio)
            && sysfs::set_prop("/proc/sys/vm/dirty_ratio", &ratio.to_string())
    }

    pub fn set_dirty_background_ratio(&self, ratio: i32) -> bool {
        (0..=100).contains(&ratio)
            && sysfs::set_prop("/proc/sys/vm/dirty_background_ratio", &ratio.to_string())
    }

    pub fn drop_caches(&self, level: i32) -> bool {
        (1..=3).contains(&level)
            && sysfs::write_file("/proc/sys/vm/drop_caches", &level.to_string()).is_ok()
    }

    pub fn compact_memory(&self) -> bool {
        sysfs::write_file("/proc/sys/vm/compact_memory", "1").is_ok()
    }
}

fn allocate_swapfile(shell: &ShellModule, path: &str, size_mb: i64) -> io::Result<()> {
    let file = OpenOptions::new().write(true).create_new(true).open(path)?;
    pin_f2fs_swapfile(&file)?;
    drop(file);
    let size_text = format!("{size_mb}M");
    // A synchronous dd of several GiB monopolizes the daemon's serialized command loop.
    let _ = shell.exec_raw_root(&["fallocate", "-l", &size_text, path], "", 5_000);
    let metadata = fs::metadata(path)?;
    let expected_len = (size_mb as u64) * 1024 * 1024;
    let allocated_len = metadata.blocks().saturating_mul(512);
    if metadata.len() != expected_len || allocated_len < expected_len {
        let _ = fs::remove_file(path);
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!(
                "swapfile allocation failed: len={} allocated={} expected={}",
                metadata.len(),
                allocated_len,
                expected_len,
            ),
        ));
    }
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

fn pin_f2fs_swapfile(file: &fs::File) -> io::Result<()> {
    let pin = 1_u32;
    let result = unsafe { libc::ioctl(file.as_raw_fd(), F2FS_IOC_SET_PIN_FILE, &pin) };
    if result == 0 {
        return Ok(());
    }
    let error = io::Error::last_os_error();
    match error.raw_os_error() {
        Some(libc::ENOTTY) | Some(libc::EOPNOTSUPP) => Ok(()),
        _ => Err(error),
    }
}

fn swapfile_is_fully_allocated(path: &str, size_mb: i64) -> bool {
    let Ok(metadata) = fs::metadata(path) else {
        return false;
    };
    let expected_len = (size_mb as u64) * 1024 * 1024;
    metadata.is_file()
        && metadata.len() == expected_len
        && metadata.blocks().saturating_mul(512) >= expected_len
}

fn enable_swap(shell: &ShellModule, path: &str, priority: i32, timeout_ms: u64) -> String {
    if priority < 0 {
        // Android's Toybox rejects negative -p values; omitting the flag uses Linux's -2 default.
        shell.exec_raw_root(&["swapon", path], "", timeout_ms)
    } else {
        let priority_text = priority.to_string();
        shell.exec_raw_root(&["swapon", "-p", &priority_text, path], "", timeout_ms)
    }
}

fn parse_prop_int(path: &str) -> i32 {
    sysfs::get_prop(path)
        .and_then(|value| value.trim().parse::<i32>().ok())
        .unwrap_or(-1)
}

fn parse_current_zram_algorithm(raw: &str) -> String {
    raw.split_whitespace()
        .find(|token| token.starts_with('[') && token.ends_with(']'))
        .map(|token| token.trim_matches(['[', ']']).to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| raw.trim().to_string())
}

fn parse_zram_algorithms(raw: &str) -> Vec<String> {
    raw.split_whitespace()
        .map(|token| token.trim_matches(['[', ']']).to_string())
        .filter(|token| !token.is_empty())
        .collect()
}

#[derive(Clone, Debug)]
struct ZramMemoryStats {
    orig_data_size: i64,
    compr_data_size: i64,
    mem_used_total: i64,
    mem_limit: i64,
    mem_used_max: i64,
    same_pages: i64,
    pages_compacted: i64,
    huge_pages: i64,
    source: String,
}

fn parse_zram_mm_stat(raw: &str) -> Option<ZramMemoryStats> {
    let values = raw
        .split_whitespace()
        .map(str::parse::<i64>)
        .collect::<Result<Vec<_>, _>>()
        .ok()?;
    if values.len() < 3 {
        return None;
    }
    Some(ZramMemoryStats {
        orig_data_size: values[0],
        compr_data_size: values[1],
        mem_used_total: values[2],
        mem_limit: values.get(3).copied().unwrap_or(-1),
        mem_used_max: values.get(4).copied().unwrap_or(-1),
        same_pages: values.get(5).copied().unwrap_or(-1),
        pages_compacted: values.get(6).copied().unwrap_or(-1),
        huge_pages: values.get(7).copied().unwrap_or(-1),
        source: "mm_stat".to_string(),
    })
}

fn parse_zram_bd_stat(raw: &str) -> Option<(i64, i64, i64)> {
    let values = raw
        .split_whitespace()
        .map(str::parse::<i64>)
        .collect::<Result<Vec<_>, _>>()
        .ok()?;
    Some((
        values.first().copied()?,
        values.get(1).copied()?,
        values.get(2).copied()?,
    ))
}

fn zram_device_name(path: &str) -> Option<&str> {
    let name = path.rsplit('/').next()?;
    is_zram_device_path(path).then_some(name)
}

fn aggregate_zram_info(devices: &[ZramInfo]) -> Option<ZramInfo> {
    let primary = devices.first()?.clone();
    if devices.len() == 1 {
        return Some(primary);
    }
    Some(ZramInfo {
        device: "multiple".to_string(),
        disksize: sum_available(devices.iter().map(|item| item.disksize)),
        logical_size_kb: devices.iter().map(|item| item.logical_size_kb).sum(),
        logical_used_kb: devices.iter().map(|item| item.logical_used_kb).sum(),
        compr_data_size: sum_available(devices.iter().map(|item| item.compr_data_size)),
        orig_data_size: sum_available(devices.iter().map(|item| item.orig_data_size)),
        mem_used_total: sum_available(devices.iter().map(|item| item.mem_used_total)),
        mem_limit: -1,
        mem_used_max: sum_available(devices.iter().map(|item| item.mem_used_max)),
        same_pages: sum_available(devices.iter().map(|item| item.same_pages)),
        pages_compacted: sum_available(devices.iter().map(|item| item.pages_compacted)),
        huge_pages: sum_available(devices.iter().map(|item| item.huge_pages)),
        stats_source: "aggregated".to_string(),
        comp_algorithm: String::new(),
        current_algorithm: "multiple".to_string(),
        available_algorithms: Vec::new(),
        backing_dev: String::new(),
        bd_count: sum_available(devices.iter().map(|item| item.bd_count)),
        bd_reads: sum_available(devices.iter().map(|item| item.bd_reads)),
        bd_writes: sum_available(devices.iter().map(|item| item.bd_writes)),
        writeback_supported: devices.iter().any(|item| item.writeback_supported),
    })
}

fn sum_available(values: impl Iterator<Item = i64>) -> i64 {
    let values = values.filter(|value| *value >= 0).collect::<Vec<_>>();
    if values.is_empty() {
        -1
    } else {
        values.into_iter().sum()
    }
}

fn read_vmstat() -> VmStatInfo {
    let content = sysfs::get_prop("/proc/vmstat").unwrap_or_default();
    VmStatInfo {
        pswpin: parse_vmstat_field(&content, "pswpin"),
        pswpout: parse_vmstat_field(&content, "pswpout"),
        pgscan_kswapd: parse_vmstat_field(&content, "pgscan_kswapd"),
        pgsteal_kswapd: parse_vmstat_field(&content, "pgsteal_kswapd"),
        pgscan_direct: parse_vmstat_field(&content, "pgscan_direct"),
        pgsteal_direct: parse_vmstat_field(&content, "pgsteal_direct"),
        oom_kill: parse_vmstat_field(&content, "oom_kill"),
        pgmajfault: parse_vmstat_field(&content, "pgmajfault"),
    }
}

fn parse_vmstat_field(content: &str, key: &str) -> i64 {
    for line in content.lines() {
        let mut fields = line.split_whitespace();
        if fields.next() == Some(key) {
            return fields
                .next()
                .and_then(|value| value.parse::<i64>().ok())
                .unwrap_or(-1);
        }
    }
    -1
}

fn is_supported_swap_priority(priority: i32) -> bool {
    matches!(priority, -2 | 0 | 5)
}

fn restore_swappiness(value: i32) -> bool {
    sysfs::set_prop("/proc/sys/vm/swappiness", &value.to_string())
}

fn parse_swaps(raw: &str) -> Result<Vec<SwapEntry>, ()> {
    let mut lines = raw.lines();
    let Some(header) = lines.next() else {
        return Err(());
    };
    if header.split_whitespace().collect::<Vec<_>>()
        != ["Filename", "Type", "Size", "Used", "Priority"]
    {
        return Err(());
    }

    let mut entries = Vec::new();
    for line in lines.filter(|line| !line.trim().is_empty()) {
        let mut parts = line.split_whitespace();
        let path = parts.next().ok_or(())?.to_string();
        let kind = parts.next().ok_or(())?.to_string();
        let size_kb = parts.next().ok_or(())?.parse::<i64>().map_err(|_| ())?;
        let used_kb = parts.next().ok_or(())?.parse::<i64>().map_err(|_| ())?;
        let priority = parts.next().ok_or(())?.parse::<i32>().map_err(|_| ())?;
        if parts.next().is_some() {
            return Err(());
        }
        entries.push(SwapEntry {
            is_zram: is_zram_device_path(&path),
            is_loop: loop_device_name(&path).is_some(),
            is_managed: false,
            path,
            kind,
            size_kb,
            used_kb,
            priority,
        });
    }
    Ok(entries)
}

fn is_zram_device_path(path: &str) -> bool {
    let Some(index) = path
        .rsplit('/')
        .next()
        .and_then(|name| name.strip_prefix("zram"))
    else {
        return false;
    };
    !index.is_empty() && index.chars().all(|character| character.is_ascii_digit())
}

fn loop_device_name(path: &str) -> Option<&str> {
    let name = path.rsplit('/').next()?;
    let index = name.strip_prefix("loop")?;
    (!index.is_empty() && index.chars().all(|character| character.is_ascii_digit())).then_some(name)
}

fn is_managed_swapfile_path(path: &str) -> bool {
    matches!(path, MANAGED_SWAPFILE_PATH | MANAGED_SWAPFILE_KERNEL_PATH)
}

fn managed_swap_command_target(path: &str) -> &str {
    if is_managed_swapfile_path(path) {
        MANAGED_SWAPFILE_PATH
    } else {
        path
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_info_golden_json() {
        let info = MemoryInfo {
            mem_total: 1000,
            mem_free: 100,
            mem_avail: 700,
            swap_total: 200,
            swap_free: 150,
            cached: 300,
            buffers: 40,
            dirty: 5,
            writeback: 0,
        };
        assert_eq!(
            to_json(&info),
            r#"{"mem_total_kb":1000,"mem_free_kb":100,"mem_avail_kb":700,"swap_total_kb":200,"swap_free_kb":150,"cached_kb":300,"buffers_kb":40,"dirty_kb":5,"writeback_kb":0}"#,
        );
    }

    #[test]
    fn zram_info_golden_json() {
        let info = ZramInfo {
            device: "zram0".to_string(),
            disksize: 1,
            logical_size_kb: 2,
            logical_used_kb: 3,
            compr_data_size: 4,
            orig_data_size: 5,
            mem_used_total: 6,
            mem_limit: 7,
            mem_used_max: 8,
            same_pages: 9,
            pages_compacted: 10,
            huge_pages: 11,
            stats_source: "mm_stat".to_string(),
            comp_algorithm: "[lz4] zstd".to_string(),
            current_algorithm: "lz4".to_string(),
            available_algorithms: vec!["lz4".to_string(), "zstd".to_string()],
            backing_dev: String::new(),
            bd_count: 12,
            bd_reads: 13,
            bd_writes: 14,
            writeback_supported: false,
        };
        assert_eq!(
            to_json(&info),
            r#"{"device":"zram0","disksize":1,"logical_size_kb":2,"logical_used_kb":3,"compr_data_size":4,"orig_data_size":5,"mem_used_total":6,"mem_limit":7,"mem_used_max":8,"same_pages":9,"pages_compacted":10,"huge_pages":11,"stats_source":"mm_stat","comp_algorithm":"[lz4] zstd","current_algorithm":"lz4","available_algorithms":["lz4","zstd"],"backing_dev":"","bd_count":12,"bd_reads":13,"bd_writes":14,"writeback_supported":false}"#,
        );
    }

    #[test]
    fn parses_standard_mm_stat_prefix() {
        let stats = parse_zram_mm_stat("1048576 524288 655360 0 786432 12 34 56").unwrap();

        assert_eq!(stats.orig_data_size, 1_048_576);
        assert_eq!(stats.compr_data_size, 524_288);
        assert_eq!(stats.mem_used_total, 655_360);
        assert_eq!(stats.mem_used_max, 786_432);
        assert_eq!(stats.source, "mm_stat");
    }

    #[test]
    fn parses_swap_entries_without_misclassifying_file_names() {
        let entries = parse_swaps(
            "Filename\t\t\tType\t\tSize\tUsed\tPriority\n/dev/block/zram0\tpartition\t1048572\t0\t32767\n/data/zram-cache.swap\tfile\t524284\t0\t-2\n",
        )
        .unwrap();

        assert!(entries[0].is_zram);
        assert!(!entries[1].is_zram);
    }

    #[test]
    fn rejects_malformed_swap_entries() {
        assert!(
            parse_swaps("Filename Type Size Used Priority\n/data/swapfile file bad 0 -2\n")
                .is_err()
        );
    }

    #[test]
    fn recognizes_only_numbered_loop_devices() {
        assert_eq!(loop_device_name("/dev/block/loop12"), Some("loop12"));
        assert_eq!(loop_device_name("/dev/block/loop-control"), None);
        assert_eq!(loop_device_name("/data/loopback.swap"), None);
    }

    #[test]
    fn recognizes_kernel_swapfile_alias() {
        assert!(is_managed_swapfile_path("/data/swapfile"));
        assert!(is_managed_swapfile_path("/swapfile"));
        assert!(!is_managed_swapfile_path("/data/other-swapfile"));
    }

    #[test]
    fn resolves_kernel_alias_to_accessible_swapfile() {
        assert_eq!(managed_swap_command_target("/swapfile"), "/data/swapfile");
        assert_eq!(
            managed_swap_command_target("/dev/block/loop7"),
            "/dev/block/loop7"
        );
    }

    #[test]
    fn parses_vmstat_fields() {
        let vmstat = "pswpin 12\npswpout 34\noom_kill 5\n";

        assert_eq!(parse_vmstat_field(vmstat, "pswpin"), 12);
        assert_eq!(parse_vmstat_field(vmstat, "pswpout"), 34);
        assert_eq!(parse_vmstat_field(vmstat, "missing"), -1);
    }
}

fn parse_meminfo_field(key: &str) -> i64 {
    let Some(content) = sysfs::get_prop("/proc/meminfo") else {
        return -1;
    };
    for line in content.lines() {
        if line.starts_with(&format!("{key}:")) {
            return line[key.len() + 1..]
                .split_whitespace()
                .next()
                .and_then(|value| value.parse::<i64>().ok())
                .unwrap_or(-1);
        }
    }
    -1
}

fn parse_prop_long(path: &str) -> i64 {
    sysfs::get_prop(path)
        .and_then(|value| value.trim().parse::<i64>().ok())
        .unwrap_or(-1)
}
