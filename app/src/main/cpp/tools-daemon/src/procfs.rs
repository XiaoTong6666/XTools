use crate::sysfs::read_file;

pub fn get_cmdline(pid: i32) -> String {
    read_file(&format!("/proc/{pid}/cmdline")).unwrap_or_default()
}
