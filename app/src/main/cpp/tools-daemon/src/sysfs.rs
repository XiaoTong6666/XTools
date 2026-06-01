use crate::commands::{from_json, result_json, value_to_string, PathListArgs, TextWriteArgs};
use serde::Serialize;
use serde_json::{json, Value};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::thread;
use std::time::Duration;

const MAX_RETRIES: usize = 3;

#[derive(Serialize)]
struct PathListItem {
    name: String,
    path: String,
    value: String,
    is_dir: bool,
}

#[derive(Serialize)]
struct PathListResponse {
    path: String,
    items: Vec<PathListItem>,
}

pub fn read_file(path: &str) -> Option<String> {
    let bytes = fs::read(path).ok()?;
    let mut result = String::from_utf8_lossy(&bytes).into_owned();
    if result.ends_with('\n') {
        result.pop();
    }
    Some(result)
}

pub fn write_file(path: &str, content: &str) -> io::Result<()> {
    let mut file = OpenOptions::new().write(true).truncate(true).open(path)?;
    file.write_all(content.as_bytes())?;
    file.flush()
}

pub fn file_exists(path: &str) -> bool {
    fs::metadata(path).is_ok()
}

pub fn list_dir(path: &str) -> Vec<String> {
    let Ok(entries) = fs::read_dir(path) else {
        return Vec::new();
    };
    entries
        .filter_map(Result::ok)
        .filter_map(|entry| entry.file_name().into_string().ok())
        .filter(|name| !name.starts_with('.'))
        .collect()
}

pub fn path_list(raw_args: &str) -> String {
    let args = match from_json::<PathListArgs>(raw_args) {
        Ok(parsed) => parsed,
        Err(_) if !raw_args.trim().is_empty() => PathListArgs {
            path: raw_args.trim().to_string(),
            suffix: String::new(),
            include_values: true,
        },
        Err(err) => return json!({ "error": err }).to_string(),
    };

    if args.path.is_empty() {
        return json!({ "error": "invalid args" }).to_string();
    }

    let Ok(entries) = fs::read_dir(&args.path) else {
        return json!(PathListResponse {
            path: args.path,
            items: Vec::new(),
        })
        .to_string();
    };

    let mut items: Vec<PathListItem> = entries
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok()?;
            if name.starts_with('.') || name == "uevent" {
                return None;
            }
            if !args.suffix.is_empty() && !name.ends_with(&args.suffix) {
                return None;
            }
            let path = entry.path();
            let metadata = entry.metadata().ok();
            let is_dir = metadata.as_ref().is_some_and(|item| item.is_dir());
            let value = if args.include_values && !is_dir {
                read_file(&path.to_string_lossy())
                    .map(|raw| trim(&raw))
                    .unwrap_or_default()
            } else {
                String::new()
            };
            Some(PathListItem {
                name,
                path: path.to_string_lossy().into_owned(),
                value,
                is_dir,
            })
        })
        .collect();

    items.sort_by(|left, right| left.name.cmp(&right.name));

    json!(PathListResponse {
        path: args.path,
        items,
    })
    .to_string()
}

pub fn trim(value: &str) -> String {
    value
        .trim_matches(|c| matches!(c, ' ' | '\n' | '\r' | '\t'))
        .to_string()
}

pub fn get_prop(path: &str) -> Option<String> {
    read_file(path)
}

pub fn set_prop(path: &str, value: &str) -> bool {
    if write_file(path, value).is_err() {
        return false;
    }
    verify_write(path, value)
}

pub fn text_write(raw_args: &str) -> String {
    let Ok(parsed) = from_json::<TextWriteArgs>(raw_args) else {
        return json!({ "error": "invalid args" }).to_string();
    };
    if parsed.path.is_empty() {
        return json!({ "error": "invalid args" }).to_string();
    }
    result_json(
        set_prop(&parsed.path, &value_to_string(&parsed.text)),
        "write_failed",
    )
}

pub fn get_props(paths_json: &str) -> String {
    let mut result = serde_json::Map::new();
    match serde_json::from_str::<Value>(paths_json) {
        Ok(Value::Array(paths)) => {
            for path in paths.iter().filter_map(Value::as_str) {
                let value = get_prop(path)
                    .map(|raw| trim(&raw))
                    .unwrap_or_else(|| "ERROR:read_failed".to_string());
                result.insert(path.to_string(), Value::String(value));
            }
            Value::Object(result).to_string()
        }
        Ok(_) => json!({}).to_string(),
        Err(err) => json!({ "error": format!("invalid_json: {err}") }).to_string(),
    }
}

pub fn set_props(props_json: &str) -> String {
    let mut result = serde_json::Map::new();
    match serde_json::from_str::<Value>(props_json) {
        Ok(Value::Array(items)) => {
            for item in items {
                let Some(path) = item.get("path").and_then(Value::as_str) else {
                    continue;
                };
                let Some(value) = item.get("value").and_then(Value::as_str) else {
                    continue;
                };
                let status = if set_prop(path, value) {
                    "ok"
                } else {
                    "verify_failed"
                };
                result.insert(path.to_string(), Value::String(status.to_string()));
            }
            Value::Object(result).to_string()
        }
        Ok(Value::Object(item)) => {
            let path = item.get("path").and_then(Value::as_str).unwrap_or_default();
            let value = item
                .get("value")
                .and_then(Value::as_str)
                .unwrap_or_default();
            if path.is_empty() {
                json!({}).to_string()
            } else {
                let status = if set_prop(path, value) {
                    "ok"
                } else {
                    "verify_failed"
                };
                json!({ "result": status }).to_string()
            }
        }
        Ok(_) => json!({}).to_string(),
        Err(err) => json!({ "error": format!("invalid_json: {err}") }).to_string(),
    }
}

fn verify_write(path: &str, expected: &str) -> bool {
    for retry in 0..MAX_RETRIES {
        if retry > 0 {
            thread::sleep(Duration::from_millis(10_u64 << retry));
        }
        let Some(actual) = read_file(path) else {
            continue;
        };
        let actual = trim(&actual);
        let expected = trim(expected);
        if actual == expected {
            return true;
        }
        if actual.len() >= expected.len() && actual.starts_with(&expected) {
            let suffix = trim(&actual[expected.len()..]);
            if suffix.is_empty()
                || suffix.chars().all(|c| {
                    matches!(
                        c,
                        ' ' | '\n' | 'k' | 'M' | 'G' | 'H' | 'z' | '%' | 'm' | 's'
                    )
                })
            {
                return true;
            }
        }
        if let (Ok(actual_num), Ok(expected_num)) = (actual.parse::<f64>(), expected.parse::<f64>())
        {
            if expected_num != 0.0 {
                let ratio = actual_num / expected_num;
                if (0.99..=1.01).contains(&ratio) {
                    return true;
                }
            }
        }
    }
    false
}
