use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::Value;

pub fn from_json<T: DeserializeOwned>(raw: &str) -> Result<T, String> {
    serde_json::from_str(raw).map_err(|_| "invalid args".to_string())
}

pub fn to_json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| error_json("serialization_failed"))
}

pub fn ok_json() -> String {
    to_json(&ResultResponse { result: "ok" })
}

pub fn error_json(error: &str) -> String {
    to_json(&ErrorResponse { error })
}

pub fn result_json(ok: bool, error: &str) -> String {
    if ok {
        ok_json()
    } else {
        error_json(error)
    }
}

#[derive(Serialize)]
pub struct ErrorResponse<'a> {
    pub error: &'a str,
}

#[derive(Serialize)]
pub struct OwnedErrorResponse {
    pub error: String,
}

#[derive(Serialize)]
pub struct ResultResponse<'a> {
    pub result: &'a str,
}

#[derive(Deserialize)]
pub struct ExecShellArgs {
    #[serde(default)]
    pub cmd: String,
    #[serde(default = "default_timeout_ms")]
    pub timeout: u64,
}

#[derive(Deserialize)]
pub struct KernelPropArgs {
    #[serde(default)]
    pub path: String,
}

#[derive(Deserialize)]
pub struct SetKernelPropArgs {
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub value: Value,
}

#[derive(Deserialize)]
pub struct PathListArgs {
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub suffix: String,
    #[serde(default = "default_true")]
    pub include_values: bool,
}

#[derive(Deserialize)]
pub struct TextWriteArgs {
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub text: Value,
}

#[derive(Deserialize)]
pub struct WorkingModeArgs {
    #[serde(default = "default_root_mode")]
    pub mode: String,
}

#[derive(Serialize)]
pub struct WorkingModeResponse<'a> {
    pub mode: String,
    pub result: &'a str,
}

#[derive(Deserialize)]
pub struct SelinuxArgs {
    #[serde(default = "default_true")]
    pub enforce: bool,
}

#[derive(Deserialize)]
pub struct RestoreHiddenAppArgs {
    #[serde(default)]
    pub package: String,
    #[serde(default)]
    pub user_id: i64,
}

#[derive(Deserialize)]
pub struct CreateSwapfileArgs {
    #[serde(default = "default_swapfile_size_mb")]
    pub size_mb: i64,
    #[serde(default = "default_swap_priority")]
    pub priority: i32,
    #[serde(default)]
    pub use_loop: bool,
}

#[derive(Deserialize)]
pub struct SwapDisableArgs {
    #[serde(default = "default_false")]
    pub remove_file: bool,
}

#[derive(Deserialize)]
pub struct SwapPriorityArgs {
    pub priority: i32,
}

#[derive(Deserialize)]
pub struct MemoryJobStatusArgs {
    pub job_id: u64,
}

#[derive(Deserialize)]
pub struct DropCachesArgs {
    #[serde(default = "default_drop_caches_level")]
    pub level: i32,
}

#[derive(Deserialize)]
pub struct BackupPartitionArgs {
    #[serde(default)]
    pub partition: String,
    #[serde(default)]
    pub output: String,
}

#[derive(Deserialize)]
pub struct FlashPartitionArgs {
    #[serde(default)]
    pub partition: String,
    #[serde(default)]
    pub input: String,
}

#[derive(Deserialize)]
pub struct ShellDelayedAddArgs {
    #[serde(default)]
    pub cmd: String,
    #[serde(default)]
    pub delay: u64,
}

#[derive(Deserialize)]
pub struct ShellDelayedRemoveArgs {
    #[serde(default)]
    pub id: String,
}

#[derive(Deserialize)]
pub struct DumpsysArgs {
    #[serde(default)]
    pub service: String,
}

#[derive(Serialize)]
pub struct DeviceIdResponse {
    pub model: String,
    pub cpu_cores: String,
    pub kernel: String,
}

#[derive(Serialize)]
pub struct PingResponse<'a> {
    pub pong: bool,
    pub version: &'a str,
    pub build_time: &'a str,
    pub protocol_version: i32,
    pub features: &'a [String],
    pub socket_name: &'a str,
    pub child_process: bool,
}

#[derive(Deserialize)]
pub struct CoreGovernorArgs {
    pub core: i32,
    pub governor: String,
}

#[derive(Deserialize)]
pub struct CpuFrequencyArgs {
    #[serde(default)]
    pub core: i32,
    #[serde(default)]
    pub min: i64,
    #[serde(default)]
    pub max: i64,
}

#[derive(Deserialize)]
pub struct CoreCtlArgs {
    pub core: i32,
    pub online: bool,
}

#[derive(Deserialize)]
pub struct PidArgs {
    pub pid: i32,
}

#[derive(Deserialize)]
pub struct CpuAffinitySetArgs {
    pub pid: i32,
    pub cpus: Vec<i32>,
}

#[derive(Deserialize)]
pub struct ResizeZramArgs {
    pub size_mb: i64,
    #[serde(default)]
    pub algorithm: String,
}

#[derive(Deserialize)]
pub struct IntValueArgs {
    pub value: i32,
}

#[derive(Deserialize)]
pub struct KbytesArgs {
    pub kb: i64,
}

#[derive(Deserialize)]
pub struct VmParametersArgs {
    pub swappiness: i32,
    #[serde(default)]
    pub extra_free_kbytes: Option<i64>,
    #[serde(default)]
    pub watermark_scale_factor: Option<i32>,
}

#[derive(Deserialize)]
pub struct ProcessKillArgs {
    pub pid: i32,
    #[serde(default = "default_sigkill")]
    pub sig: i32,
}

#[derive(Deserialize)]
pub struct OomAdjArgs {
    pub pid: i32,
    pub adj: i32,
}

#[derive(Deserialize)]
pub struct ChargeCurrentArgs {
    pub ua: i64,
}

#[derive(Deserialize)]
pub struct ChargeEnableArgs {
    pub enable: bool,
}

#[derive(Deserialize)]
pub struct FactorArgs {
    pub factor: i32,
}

#[derive(Deserialize)]
pub struct DirtyRatioArgs {
    pub ratio: i32,
    pub bg_ratio: Option<i32>,
}

#[derive(Serialize)]
pub struct ThreadIdsResponse {
    pub pid: i32,
    pub thread_count: usize,
    pub threads: Vec<i32>,
}

fn default_timeout_ms() -> u64 {
    5000
}

fn default_root_mode() -> String {
    "root".to_string()
}

fn default_true() -> bool {
    true
}

fn default_false() -> bool {
    false
}

fn default_swapfile_size_mb() -> i64 {
    2048
}

fn default_swap_priority() -> i32 {
    -2
}

fn default_drop_caches_level() -> i32 {
    3
}

fn default_sigkill() -> i32 {
    9
}

pub fn value_to_string(value: &Value) -> String {
    match value {
        Value::String(value) => value.clone(),
        Value::Null => String::new(),
        value => value.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn result_response_golden_json() {
        assert_eq!(ok_json(), r#"{"result":"ok"}"#);
        assert_eq!(error_json("invalid args"), r#"{"error":"invalid args"}"#);
    }

    #[test]
    fn ping_response_golden_json() {
        let features = vec![
            "abstract_unix_socket".to_string(),
            "uid_token_auth".to_string(),
        ];
        let response = PingResponse {
            pong: true,
            version: "1.0",
            build_time: "1",
            protocol_version: 1,
            features: &features,
            socket_name: "tools_daemon_test",
            child_process: true,
        };
        assert_eq!(
            to_json(&response),
            r#"{"pong":true,"version":"1.0","build_time":"1","protocol_version":1,"features":["abstract_unix_socket","uid_token_auth"],"socket_name":"tools_daemon_test","child_process":true}"#,
        );
    }

    #[test]
    fn thread_ids_response_golden_json() {
        let response = ThreadIdsResponse {
            pid: 42,
            thread_count: 2,
            threads: vec![42, 43],
        };
        assert_eq!(
            to_json(&response),
            r#"{"pid":42,"thread_count":2,"threads":[42,43]}"#,
        );
    }
}
