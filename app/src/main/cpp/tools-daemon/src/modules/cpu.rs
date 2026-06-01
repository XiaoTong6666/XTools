use crate::sysfs;
use serde::Serialize;
use serde_json::json;
use std::collections::HashSet;

#[derive(Clone, Debug, Serialize)]
pub struct CpuCoreInfo {
    pub core: i32,
    pub online: bool,
    pub cur_freq: i64,
    pub max_freq: i64,
    pub min_freq: i64,
    pub governor: String,
    pub load: f32,
}

#[derive(Clone, Debug, Serialize)]
pub struct CpuStats {
    pub cores: Vec<CpuCoreInfo>,
    pub loadavg: String,
    pub cpu_usage: f32,
}

pub struct CpuModule {
    prev_total: i64,
    prev_idle: i64,
    prev_core_totals: Vec<i64>,
    prev_core_idle: Vec<i64>,
}

impl CpuModule {
    pub fn new() -> Self {
        Self {
            prev_total: 0,
            prev_idle: 0,
            prev_core_totals: Vec::new(),
            prev_core_idle: Vec::new(),
        }
    }

    pub fn get_stats(&mut self) -> CpuStats {
        let cpu_usage = self.calc_cpu_usage();
        let cores = self.get_core_count();
        let mut core_infos = Vec::new();
        for core in 0..cores {
            let online = sysfs::get_prop(&format!("/sys/devices/system/cpu/cpu{core}/online"))
                .map(|value| value != "0")
                .unwrap_or(true);
            let mut info = CpuCoreInfo {
                core,
                online,
                cur_freq: -1,
                max_freq: -1,
                min_freq: -1,
                governor: String::new(),
                load: -1.0,
            };
            if online {
                info.cur_freq = parse_prop_long(&self.core_path(core, "scaling_cur_freq"));
                info.max_freq = parse_prop_long(&self.core_path(core, "scaling_max_freq"));
                info.min_freq = parse_prop_long(&self.core_path(core, "scaling_min_freq"));
                info.governor = sysfs::get_prop(&self.core_path(core, "scaling_governor"))
                    .unwrap_or_else(|| "unknown".to_string());
                info.load = self.calc_core_load(core);
            }
            core_infos.push(info);
        }

        CpuStats {
            cores: core_infos,
            loadavg: sysfs::get_prop("/proc/loadavg").unwrap_or_else(|| "unknown".to_string()),
            cpu_usage,
        }
    }

    pub fn stats_to_json(stats: &CpuStats) -> serde_json::Value {
        serde_json::to_value(stats).unwrap_or_else(|_| serde_json::json!({}))
    }

    pub fn set_governor(&self, core: i32, governor: &str) -> bool {
        sysfs::set_prop(&self.core_path(core, "scaling_governor"), governor)
    }

    pub fn set_frequency(&self, core: i32, min_freq: i64, max_freq: i64) -> bool {
        let mut ok = true;
        if min_freq > 0 {
            ok &= sysfs::set_prop(
                &self.core_path(core, "scaling_min_freq"),
                &min_freq.to_string(),
            );
        }
        if max_freq > 0 {
            ok &= sysfs::set_prop(
                &self.core_path(core, "scaling_max_freq"),
                &max_freq.to_string(),
            );
        }
        ok
    }

    pub fn set_online(&self, core: i32, online: bool) -> bool {
        sysfs::set_prop(
            &format!("/sys/devices/system/cpu/cpu{core}/online"),
            if online { "1" } else { "0" },
        )
    }

    pub fn set_affinity(&self, pid: i32, cpus: &[i32]) -> bool {
        if cpus.is_empty() {
            return false;
        }
        let mask = cpu_list_mask(cpus);
        if sysfs::file_exists("/dev/cpuset/tasks") {
            if sysfs::file_exists("/dev/cpuset/top-app/tasks") {
                sysfs::set_prop("/dev/cpuset/top-app/cpus", &mask);
                sysfs::set_prop("/dev/cpuset/top-app/tasks", &pid.to_string());
                return true;
            }
            sysfs::set_prop("/dev/cpuset/cpus", &mask);
            sysfs::set_prop("/dev/cpuset/tasks", &pid.to_string());
            return true;
        }
        if sysfs::file_exists("/sys/fs/cgroup/cgroup.controllers") {
            sysfs::set_prop("/sys/fs/cgroup/cpuset.cpus", &mask);
            sysfs::set_prop("/sys/fs/cgroup/cgroup.procs", &pid.to_string());
            return true;
        }
        false
    }

    pub fn get_affinity(&self, pid: i32) -> String {
        let Some(status) = sysfs::get_prop(&format!("/proc/{pid}/status")) else {
            return "unknown".to_string();
        };
        for line in status.lines() {
            if line.starts_with("Cpus_allowed:") || line.starts_with("Cpus_allowed_list:") {
                return line
                    .split_once(':')
                    .map(|(_, value)| value.to_string())
                    .unwrap_or_default();
            }
        }
        "unknown".to_string()
    }

    pub fn get_time_in_state(&self) -> String {
        let mut result = Vec::new();
        for core in 0..self.get_core_count() {
            if let Some(value) = sysfs::get_prop(&self.core_path(core, "stats/time_in_state")) {
                result.push(json!({ "core": core, "time_in_state": value }));
            }
        }
        serde_json::Value::Array(result).to_string()
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

    fn get_core_count(&self) -> i32 {
        if let Some(present) = sysfs::get_prop("/sys/devices/system/cpu/present") {
            let count = parse_cpu_range(&present);
            if count > 0 {
                return count;
            }
        }

        let mut cores = HashSet::new();
        for name in sysfs::list_dir("/sys/devices/system/cpu") {
            if let Some(rest) = name.strip_prefix("cpu") {
                if let Ok(core) = rest.parse::<i32>() {
                    cores.insert(core);
                }
            }
        }
        if !cores.is_empty() {
            return cores.len() as i32;
        }

        sysfs::get_prop("/sys/devices/system/cpu/kernel_max")
            .and_then(|value| value.trim().parse::<i32>().ok())
            .map(|value| value + 1)
            .unwrap_or(0)
    }

    fn core_path(&self, core: i32, file: &str) -> String {
        format!("/sys/devices/system/cpu/cpu{core}/cpufreq/{file}")
    }

    fn calc_cpu_usage(&mut self) -> f32 {
        let Some(stat) = sysfs::get_prop("/proc/stat") else {
            return -1.0;
        };
        let Some(line) = stat.lines().next() else {
            return -1.0;
        };
        let mut fields = line.split_whitespace();
        if fields.next() != Some("cpu") {
            return -1.0;
        }
        let values: Vec<i64> = fields
            .take(8)
            .filter_map(|value| value.parse().ok())
            .collect();
        if values.len() < 8 {
            return -1.0;
        }
        let total: i64 = values.iter().sum();
        let idle = values[3] + values[4];
        let mut usage = -1.0;
        if self.prev_total > 0 && total > self.prev_total {
            let total_delta = total - self.prev_total;
            let idle_delta = idle - self.prev_idle;
            usage = (total_delta - idle_delta) as f32 * 100.0 / total_delta as f32;
        }
        self.prev_total = total;
        self.prev_idle = idle;
        usage
    }

    fn calc_core_load(&mut self, core: i32) -> f32 {
        let Some(stat) = sysfs::get_prop("/proc/stat") else {
            return -1.0;
        };
        let tag = format!("cpu{core}");
        for line in stat.lines() {
            if !line.starts_with(&format!("{tag} ")) {
                continue;
            }
            let values: Vec<i64> = line
                .split_whitespace()
                .skip(1)
                .take(8)
                .filter_map(|value| value.parse().ok())
                .collect();
            if values.len() < 8 {
                return -1.0;
            }
            let total: i64 = values.iter().sum();
            let idle = values[3] + values[4];
            let index = core as usize;
            if self.prev_core_totals.len() <= index {
                self.prev_core_totals.resize(index + 1, 0);
                self.prev_core_idle.resize(index + 1, 0);
            }
            let mut usage = -1.0;
            if self.prev_core_totals[index] > 0 && total > self.prev_core_totals[index] {
                let total_delta = total - self.prev_core_totals[index];
                let idle_delta = idle - self.prev_core_idle[index];
                usage = (total_delta - idle_delta) as f32 * 100.0 / total_delta as f32;
            }
            self.prev_core_totals[index] = total;
            self.prev_core_idle[index] = idle;
            return usage;
        }
        -1.0
    }
}

#[cfg(test)]
mod tests {
    use crate::commands::to_json;

    use super::*;

    #[test]
    fn cpu_stats_golden_json() {
        let stats = CpuStats {
            cores: vec![CpuCoreInfo {
                core: 0,
                online: true,
                cur_freq: 1200000,
                max_freq: 2400000,
                min_freq: 300000,
                governor: "schedutil".to_string(),
                load: 12.5,
            }],
            loadavg: "1.00 0.50 0.25 1/100 10".to_string(),
            cpu_usage: 42.0,
        };
        assert_eq!(
            to_json(&stats),
            r#"{"cores":[{"core":0,"online":true,"cur_freq":1200000,"max_freq":2400000,"min_freq":300000,"governor":"schedutil","load":12.5}],"loadavg":"1.00 0.50 0.25 1/100 10","cpu_usage":42.0}"#,
        );
    }
}

fn parse_prop_long(path: &str) -> i64 {
    sysfs::get_prop(path)
        .and_then(|value| value.trim().parse::<i64>().ok())
        .unwrap_or(-1)
}

fn parse_cpu_range(text: &str) -> i32 {
    let mut count = 0;
    for part in text.trim().split(',') {
        if let Some((start, end)) = part.split_once('-') {
            if let (Ok(start), Ok(end)) = (start.parse::<i32>(), end.parse::<i32>()) {
                if end >= start {
                    count += end - start + 1;
                }
            }
        } else if !part.trim().is_empty() {
            count += 1;
        }
    }
    count
}

fn cpu_list_mask(cpus: &[i32]) -> String {
    let mut sorted = cpus.to_vec();
    sorted.sort_unstable();
    sorted.dedup();
    let mut ranges = Vec::new();
    let mut start = sorted[0];
    let mut end = start;
    for cpu in sorted.into_iter().skip(1) {
        if cpu == end + 1 {
            end = cpu;
        } else {
            ranges.push(if start == end {
                start.to_string()
            } else {
                format!("{start}-{end}")
            });
            start = cpu;
            end = cpu;
        }
    }
    ranges.push(if start == end {
        start.to_string()
    } else {
        format!("{start}-{end}")
    });
    ranges.join(",")
}
