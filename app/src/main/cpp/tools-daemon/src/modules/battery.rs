use crate::sysfs;
use serde::Serialize;

use crate::commands::to_json;

#[derive(Clone, Debug, Serialize)]
pub struct BatteryInfo {
    pub capacity: i32,
    pub full_capacity_mah: f64,
    pub temperature: i32,
    #[serde(rename = "current_now")]
    pub current: i32,
    #[serde(rename = "voltage_now")]
    pub voltage: i32,
    pub charge_current_max: i32,
    pub power: f32,
    pub status: String,
    pub health: String,
    pub charge_type: String,
}

pub struct BatteryModule;

impl BatteryModule {
    pub fn new() -> Self {
        Self
    }

    pub fn get_info(&self) -> BatteryInfo {
        let temp_raw = parse_long(&batt_path("temp"));
        let cur_raw = parse_long(&batt_path("current_now"));
        let volt_raw = parse_long(&batt_path("voltage_now"));
        let current = (cur_raw / 1000) as i32;
        let voltage = (volt_raw / 1000) as i32;
        let capacity = parse_long(&batt_path("capacity")) as i32;
        let mut power = -1.0;
        if current != 0 && voltage > 0 {
            let current_a = current as f32 / 1000.0;
            power = current_a * voltage as f32 / 1000.0;
        }

        BatteryInfo {
            capacity,
            full_capacity_mah: read_full_capacity_mah(capacity),
            temperature: temp_raw as i32,
            current,
            voltage,
            charge_current_max: parse_long(&batt_path("constant_charge_current_max")) as i32,
            power,
            status: sysfs::get_prop(&batt_path("status")).unwrap_or_else(|| "Unknown".to_string()),
            health: sysfs::get_prop(&batt_path("health")).unwrap_or_else(|| "Unknown".to_string()),
            charge_type: sysfs::get_prop(&batt_path("charge_type"))
                .unwrap_or_else(|| "Unknown".to_string()),
        }
    }

    pub fn get_info_json(&self) -> String {
        let info = self.get_info();
        to_json(&info)
    }

    pub fn info_to_json(info: &BatteryInfo) -> serde_json::Value {
        serde_json::to_value(info).unwrap_or_else(|_| serde_json::json!({}))
    }

    pub fn set_charge_enable(&self, enable: bool) -> bool {
        sysfs::set_prop(
            &batt_path("charging_enabled"),
            if enable { "1" } else { "0" },
        )
    }

    pub fn set_charge_current_max(&self, ua: i64) -> bool {
        sysfs::set_prop(&batt_path("constant_charge_current_max"), &ua.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn battery_info_golden_json() {
        let info = BatteryInfo {
            capacity: 80,
            full_capacity_mah: 5000.0,
            temperature: 320,
            current: 1200,
            voltage: 4200,
            charge_current_max: 3000000,
            power: 5.04,
            status: "Charging".to_string(),
            health: "Good".to_string(),
            charge_type: "Fast".to_string(),
        };
        assert_eq!(
            to_json(&info),
            r#"{"capacity":80,"full_capacity_mah":5000.0,"temperature":320,"current_now":1200,"voltage_now":4200,"charge_current_max":3000000,"power":5.04,"status":"Charging","health":"Good","charge_type":"Fast"}"#,
        );
    }
}

fn batt_path(file: &str) -> String {
    format!("/sys/class/power_supply/battery/{file}")
}

fn parse_long(path: &str) -> i64 {
    sysfs::get_prop(path)
        .and_then(|value| value.trim().parse::<i64>().ok())
        .unwrap_or(-1)
}

fn read_full_capacity_mah(level_percent: i32) -> f64 {
    let direct_candidates = [
        "fg_fullcapnom",
        "batt_full_capacity",
        "charge_full_design",
        "charge_full",
    ];

    for file in direct_candidates {
        let raw = parse_long(&batt_path(file));
        if raw <= 0 {
            continue;
        }

        let value = if raw >= 100_000 {
            raw as f64 / 1000.0
        } else {
            raw as f64
        };
        if value > 0.0 {
            return value;
        }
    }

    let charge_counter = parse_long(&batt_path("charge_counter"));
    if charge_counter > 0 && (1..=100).contains(&level_percent) {
        return (charge_counter as f64 / 1000.0) / (level_percent as f64 / 100.0);
    }

    0.0
}
