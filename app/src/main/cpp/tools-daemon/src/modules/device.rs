use crate::sysfs;
use serde::Serialize;
use serde_json::json;

use crate::commands::to_json;

#[derive(Clone, Debug, Serialize)]
pub struct GpuInfo {
    pub vendor: String,
    pub model: String,
    pub cur_freq: i64,
    pub min_freq: i64,
    pub max_freq: i64,
    pub load: i32,
    pub available_freqs: Vec<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ThermalZone {
    #[serde(rename = "type")]
    pub zone_type: String,
    pub temp: i32,
}

#[derive(Clone, Debug, Serialize)]
pub struct DeviceInfo {
    pub model: String,
    pub soc: String,
    pub kernel: String,
    pub cpu_cores: i32,
    pub total_ram_mb: i64,
    pub gpu: GpuInfo,
    pub thermals: Vec<ThermalZone>,
}

pub struct DeviceModule;

impl DeviceModule {
    pub fn new() -> Self {
        Self
    }

    pub fn get_info(&self) -> DeviceInfo {
        let mut soc = sysfs::get_prop("/sys/devices/soc0/soc_id").unwrap_or_default();
        if soc.is_empty() {
            soc = sysfs::get_prop("/sys/devices/soc0/family")
                .unwrap_or_else(|| "unknown".to_string());
        }
        DeviceInfo {
            model: sysfs::get_prop("/sys/devices/soc0/machine")
                .unwrap_or_else(|| "unknown".to_string()),
            soc,
            kernel: sysfs::get_prop("/proc/version").unwrap_or_else(|| "unknown".to_string()),
            cpu_cores: sysfs::get_prop("/sys/devices/system/cpu/kernel_max")
                .and_then(|value| value.trim().parse::<i32>().ok())
                .map(|value| value + 1)
                .unwrap_or(-1),
            total_ram_mb: get_total_ram_kb().map(|kb| kb / 1024).unwrap_or(-1),
            gpu: self.detect_gpu(),
            thermals: self.get_thermal_zones(),
        }
    }

    pub fn get_info_json(&self) -> String {
        let info = self.get_info();
        to_json(&info)
    }

    pub fn detect_gpu(&self) -> GpuInfo {
        if sysfs::file_exists("/sys/class/kgsl/kgsl-3d0/gpuclk") {
            let mut gpu = GpuInfo::new("Qualcomm", "Adreno");
            gpu.cur_freq = read_first_long("/sys/class/kgsl/kgsl-3d0/gpuclk");
            gpu.min_freq = read_first_long("/sys/class/kgsl/kgsl-3d0/devfreq/min_freq");
            gpu.max_freq = read_first_long("/sys/class/kgsl/kgsl-3d0/max_gpuclk");
            if gpu.max_freq < 0 {
                gpu.max_freq = read_first_long("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq");
            }
            gpu.load = read_first_int_any(&[
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
            ]);
            gpu.available_freqs = read_freqs_any(&[
                "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",
                "/sys/class/kgsl/kgsl-3d0/freq_table_mhz",
            ]);
            return gpu;
        }

        if sysfs::file_exists("/sys/kernel/ged/hal/current_freqency") {
            let mut gpu = GpuInfo::new("ARM", "Mali (MTK)");
            gpu.cur_freq = read_first_long("/sys/kernel/ged/hal/current_freqency");
            gpu.load = read_first_int("/sys/kernel/ged/hal/gpu_utilization");
            return gpu;
        }

        for name in sysfs::list_dir("/sys/class/devfreq") {
            if name.contains("gpu") || name.contains("mali") {
                let base = format!("/sys/class/devfreq/{name}");
                let mut gpu = GpuInfo::new("ARM", "Mali");
                gpu.cur_freq = read_first_long(&format!("{base}/cur_freq"));
                gpu.min_freq = read_first_long(&format!("{base}/min_freq"));
                gpu.max_freq = read_first_long(&format!("{base}/max_freq"));
                gpu.load =
                    read_first_int_any(&[&format!("{base}/load"), &format!("{base}/gpu_load")]);
                gpu.available_freqs = read_freqs_any(&[&format!("{base}/available_frequencies")]);
                return gpu;
            }
        }

        if sysfs::file_exists("/sys/kernel/gpu/gpu_freq") {
            let mut gpu = GpuInfo::new("Imagination", "PowerVR");
            gpu.cur_freq = read_first_long("/sys/kernel/gpu/gpu_freq");
            return gpu;
        }

        GpuInfo::new("unknown", "unknown")
    }

    pub fn get_thermal_zones(&self) -> Vec<ThermalZone> {
        let mut zones = Vec::new();
        for name in sysfs::list_dir("/sys/class/thermal") {
            if !name.starts_with("thermal_zone") {
                continue;
            }
            let base = format!("/sys/class/thermal/{name}");
            let Some(zone_type) = sysfs::get_prop(&format!("{base}/type")) else {
                continue;
            };
            let Some(temp) = sysfs::get_prop(&format!("{base}/temp")) else {
                continue;
            };
            zones.push(ThermalZone {
                zone_type,
                temp: temp.trim().parse::<i32>().unwrap_or(-1),
            });
        }
        zones
    }
}

impl GpuInfo {
    fn new(vendor: &str, model: &str) -> Self {
        Self {
            vendor: vendor.to_string(),
            model: model.to_string(),
            cur_freq: -1,
            min_freq: -1,
            max_freq: -1,
            load: -1,
            available_freqs: Vec::new(),
        }
    }
}

pub fn gpu_json(gpu: &GpuInfo) -> serde_json::Value {
    serde_json::to_value(gpu).unwrap_or_else(|_| json!({}))
}

pub fn thermals_json(thermals: &[ThermalZone]) -> serde_json::Value {
    serde_json::to_value(thermals).unwrap_or_else(|_| json!([]))
}

fn get_total_ram_kb() -> Option<i64> {
    let content = sysfs::get_prop("/proc/meminfo")?;
    for line in content.lines() {
        if line.starts_with("MemTotal:") {
            return line
                .split_whitespace()
                .nth(1)
                .and_then(|value| value.parse::<i64>().ok());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gpu_info_golden_json() {
        let gpu = GpuInfo {
            vendor: "Qualcomm".to_string(),
            model: "Adreno".to_string(),
            cur_freq: 1,
            min_freq: 2,
            max_freq: 3,
            load: 4,
            available_freqs: vec!["1".to_string(), "2".to_string()],
        };
        assert_eq!(
            to_json(&gpu),
            r#"{"vendor":"Qualcomm","model":"Adreno","cur_freq":1,"min_freq":2,"max_freq":3,"load":4,"available_freqs":["1","2"]}"#,
        );
    }

    #[test]
    fn device_info_golden_json() {
        let info = DeviceInfo {
            model: "device".to_string(),
            soc: "soc".to_string(),
            kernel: "kernel".to_string(),
            cpu_cores: 8,
            total_ram_mb: 4096,
            gpu: GpuInfo {
                vendor: "unknown".to_string(),
                model: "unknown".to_string(),
                cur_freq: -1,
                min_freq: -1,
                max_freq: -1,
                load: -1,
                available_freqs: vec![],
            },
            thermals: vec![ThermalZone {
                zone_type: "cpu".to_string(),
                temp: 42000,
            }],
        };
        assert_eq!(
            to_json(&info),
            r#"{"model":"device","soc":"soc","kernel":"kernel","cpu_cores":8,"total_ram_mb":4096,"gpu":{"vendor":"unknown","model":"unknown","cur_freq":-1,"min_freq":-1,"max_freq":-1,"load":-1,"available_freqs":[]},"thermals":[{"type":"cpu","temp":42000}]}"#,
        );
    }
}

fn read_first_long(path: &str) -> i64 {
    sysfs::get_prop(path)
        .map(|raw| parse_first_long(&raw))
        .unwrap_or(-1)
}

fn read_first_int(path: &str) -> i32 {
    let value = read_first_long(path);
    if value < 0 {
        -1
    } else {
        value as i32
    }
}

fn read_first_int_any(paths: &[&str]) -> i32 {
    for path in paths {
        let value = read_first_int(path);
        if value >= 0 {
            return value;
        }
    }
    -1
}

fn read_freqs_any(paths: &[&str]) -> Vec<String> {
    for path in paths {
        if let Some(raw) = sysfs::get_prop(path) {
            return raw.split_whitespace().map(ToString::to_string).collect();
        }
    }
    Vec::new()
}

fn parse_first_long(raw: &str) -> i64 {
    for token in raw.split_whitespace() {
        let mut digits = String::new();
        for ch in token.chars() {
            if ch.is_ascii_digit() || (ch == '-' && digits.is_empty()) {
                digits.push(ch);
            } else if !digits.is_empty() {
                break;
            }
        }
        if !digits.is_empty() && digits != "-" {
            if let Ok(value) = digits.parse::<i64>() {
                return value;
            }
        }
    }
    -1
}
