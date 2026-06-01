use serde::Serialize;

use crate::commands::to_json;
use crate::sysfs;

// Reserved for future DDR telemetry after vendor-specific sysfs paths are validated.
// This module is intentionally not exposed through daemon commands or the UI yet.
#[allow(dead_code)]
#[derive(Clone, Debug, Serialize)]
pub struct DdrInfo {
    pub cur_freq: i64,
    pub min_freq: i64,
    pub max_freq: i64,
    pub vendor: String,
    pub valid: bool,
}

#[allow(dead_code)]
pub struct DdrModule;

#[allow(dead_code)]
impl DdrModule {
    pub fn new() -> Self {
        Self
    }

    pub fn get_info(&self) -> DdrInfo {
        let mut info = DdrInfo {
            cur_freq: -1,
            min_freq: -1,
            max_freq: -1,
            vendor: String::new(),
            valid: false,
        };

        let mut cur_paths = Vec::new();
        detect_mediatek(&mut cur_paths);
        detect_qualcomm(&mut cur_paths);
        detect_generic(&mut cur_paths);

        for path in &cur_paths {
            if let Some(value) = try_read_freq(path) {
                if value > 0 {
                    info.cur_freq = value;
                    break;
                }
            }
        }

        let mut min_paths = Vec::new();
        for path in &cur_paths {
            if path.contains("cur_freq") {
                min_paths.push(path.replacen("cur_freq", "min_freq", 1));
            }
        }
        min_paths.push("/sys/class/devfreq/soc:qcom,cpubw/min_freq".to_string());
        for path in &min_paths {
            if let Some(value) = try_read_freq(path) {
                if value > 0 {
                    info.min_freq = value;
                    break;
                }
            }
        }

        let mut max_paths = Vec::new();
        for path in &cur_paths {
            if path.contains("cur_freq") {
                max_paths.push(path.replacen("cur_freq", "max_freq", 1));
            }
        }
        max_paths.push("/sys/class/devfreq/soc:qcom,cpubw/max_freq".to_string());
        for path in &max_paths {
            if let Some(value) = try_read_freq(path) {
                if value > 0 {
                    info.max_freq = value;
                    break;
                }
            }
        }

        info.valid = info.cur_freq > 0;
        info
    }

    pub fn get_info_json(&self) -> String {
        to_json(&self.get_info())
    }
}

fn try_read_freq(path: &str) -> Option<i64> {
    let value = sysfs::get_prop(path)?;
    if value.trim().is_empty() {
        return None;
    }
    value.trim().parse::<i64>().ok()
}

fn detect_mediatek(paths: &mut Vec<String>) {
    let dvfs_base = "/sys/devices/platform/10012000.dvfsrc/helio-dvfsrc";
    if sysfs::file_exists(dvfs_base) {
        paths.push(format!("{dvfs_base}/dvfsrc_cur_freq"));
        paths.push(format!("{dvfs_base}/dvfsrc_freq"));
        paths.push("/sys/kernel/debug/ged/hal/cur_ddr_freq".to_string());
    }
}

fn detect_qualcomm(paths: &mut Vec<String>) {
    for path in [
        "/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-lat/cur_freq",
        "/sys/class/devfreq/soc:qcom,cpubw/cur_freq",
        "/sys/kernel/bimc_scaling/bimc_freq",
        "/sys/kernel/bimc_scaling/bimc_cur_freq",
    ] {
        if sysfs::file_exists(path) {
            paths.push(path.to_string());
        }
    }
}

fn detect_generic(paths: &mut Vec<String>) {
    for entry in sysfs::list_dir("/sys/class/devfreq") {
        let base = format!("/sys/class/devfreq/{entry}");
        let Some(name) = sysfs::get_prop(&format!("{base}/name")) else {
            continue;
        };
        let lower = name.trim().to_lowercase();
        if lower.contains("ddr")
            || lower.contains("dram")
            || lower.contains("bimc")
            || lower.contains("mem")
        {
            paths.push(format!("{base}/cur_freq"));
            paths.push(format!("{base}/min_freq"));
            paths.push(format!("{base}/max_freq"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ddr_info_golden_json() {
        let info = DdrInfo {
            cur_freq: 2133000,
            min_freq: 400000,
            max_freq: 3200000,
            vendor: String::new(),
            valid: true,
        };
        assert_eq!(
            to_json(&info),
            r#"{"cur_freq":2133000,"min_freq":400000,"max_freq":3200000,"vendor":"","valid":true}"#,
        );
    }
}
