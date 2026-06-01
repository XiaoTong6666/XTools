use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use spdlog::{prelude::*, sink::FileSink, Level, LevelFilter, Logger};

#[cfg(target_os = "android")]
use spdlog::sink::{AndroidLogTag, AndroidSink};

pub fn init(log_path: Option<&Path>) {
    let mut builder = Logger::builder();
    let mut deferred_warnings = Vec::new();

    builder
        .name("tools-daemon")
        .level_filter(LevelFilter::MoreSevereEqual(Level::Info))
        .flush_level_filter(LevelFilter::MoreSevereEqual(Level::Warn));

    for sink in default_sinks(log_path, &mut deferred_warnings) {
        builder.sink(sink);
    }

    let Ok(logger) = builder.build_arc() else {
        return;
    };
    logger.set_flush_period(Some(Duration::from_secs(3)));
    spdlog::set_default_logger(logger);

    for warning in deferred_warnings {
        warn!("{}", warning);
    }
}

pub fn info(message: &str) {
    spdlog::info!("{}", message);
}

pub fn debug(message: &str) {
    spdlog::debug!("{}", message);
}

pub fn warn(message: &str) {
    spdlog::warn!("{}", message);
}

pub fn error(message: &str) {
    spdlog::error!("{}", message);
}

fn default_sinks(
    log_path: Option<&Path>,
    deferred_warnings: &mut Vec<String>,
) -> Vec<Arc<dyn spdlog::sink::Sink>> {
    let mut sinks: Vec<Arc<dyn spdlog::sink::Sink>> = Vec::new();

    #[cfg(target_os = "android")]
    {
        if let Ok(sink) = AndroidSink::builder()
            .tag(AndroidLogTag::Custom("tools-daemon".to_string()))
            .build_arc()
        {
            sinks.push(sink);
        }
    }

    if let Some(path) = log_path {
        match FileSink::builder().path(path).build_arc() {
            Ok(sink) => sinks.push(sink),
            Err(err) => {
                deferred_warnings.push(format!("failed to initialize daemon.log sink: {err}"))
            }
        }
    }

    sinks
}
