use serde::Deserialize;

#[derive(Clone, Debug, Deserialize)]
pub struct DaemonConfig {
    #[serde(default = "default_mode")]
    pub mode: String,
    #[serde(default)]
    #[allow(dead_code)]
    pub spawn_guard: bool,
    #[serde(default = "default_socket_name")]
    pub socket_name: String,
    #[serde(default)]
    pub session_token: String,
    #[serde(default = "default_allowed_uid")]
    pub allowed_uid: i32,
    #[serde(default = "default_process_name")]
    pub daemon_process_name: String,
    #[serde(default = "default_unknown")]
    pub daemon_version: String,
    #[serde(default = "default_unknown")]
    pub build_time: String,
    #[serde(default)]
    pub protocol_version: i32,
    #[serde(default)]
    #[allow(dead_code)]
    pub binary_revision: i32,
    #[serde(default)]
    pub feature_flags: Vec<String>,
}

impl DaemonConfig {
    pub fn from_json(raw: &str) -> serde_json::Result<Self> {
        serde_json::from_str(raw)
    }
}

impl Default for DaemonConfig {
    fn default() -> Self {
        Self {
            mode: default_mode(),
            spawn_guard: true,
            socket_name: default_socket_name(),
            session_token: String::new(),
            allowed_uid: default_allowed_uid(),
            daemon_process_name: default_process_name(),
            daemon_version: default_unknown(),
            build_time: default_unknown(),
            protocol_version: 0,
            binary_revision: 0,
            feature_flags: Vec::new(),
        }
    }
}

fn default_mode() -> String {
    "root".to_string()
}

fn default_socket_name() -> String {
    "tools_daemon".to_string()
}

fn default_allowed_uid() -> i32 {
    -1
}

fn default_process_name() -> String {
    "tools-daemon".to_string()
}

fn default_unknown() -> String {
    "unknown".to_string()
}
