#include "engine.h"
#include <nlohmann/json.hpp>
#include <spdlog/sinks/android_sink.h>
#include <spdlog/spdlog.h>

using json = nlohmann::json;

namespace tools {

static std::string errorJson(const char *error) {
  return json{{"error", error}}.dump();
}

Core &Core::instance() {
  static Core core;
  return core;
}

Core::Core() {
  auto sink = std::make_shared<spdlog::sinks::android_sink_mt>("tools-jni");
  auto logger = std::make_shared<spdlog::logger>("tools", sink);
  spdlog::set_default_logger(logger);
  spdlog::set_level(spdlog::level::info);
}

bool Core::initialize(const std::string &configJson) {
  if (initialized_)
    return true;
  spdlog::info("Core initializing...");

  bool spawnGuard = true;
  std::string sessionToken;
  int allowedUid = -1;

  try {
    auto cfg = json::parse(configJson);
    setWorkingMode(cfg.value("mode", "root"));
    spawnGuard = cfg.value("spawn_guard", true);
    daemonSocketName_ = cfg.value("socket_name", daemonSocketName_);
    sessionToken = cfg.value("session_token", std::string());
    allowedUid = cfg.value("allowed_uid", -1);
    daemonProcessName_ = cfg.value("daemon_process_name", daemonProcessName_);
    daemonVersion_ = cfg.value("daemon_version", daemonVersion_);
    daemonBuildTime_ = cfg.value("build_time", daemonBuildTime_);
    daemonProtocolVersion_ =
        cfg.value("protocol_version", daemonProtocolVersion_);
    daemonFeatureFlags_ =
        cfg.value("feature_flags", std::vector<std::string>{});
  } catch (...) {
    setWorkingMode("root");
  }
  guard_.configure(daemonSocketName_, sessionToken, allowedUid,
                   daemonProcessName_);
  spdlog::info("Core initialize config: spawnGuard={} guardAliveBefore={} "
               "childProcess={} socket=@{} allowedUid={} tokenConfigured={} "
               "version={} buildTime={} protocol={} features={}",
               spawnGuard, guard_.isAlive(), guard_.isChildProcess(),
               guard_.socketName(), allowedUid, !sessionToken.empty(),
               daemonVersion_, daemonBuildTime_, daemonProtocolVersion_,
               daemonFeatureFlags_.size());
  if (spawnGuard) {
    spdlog::warn(
        "Core initialize: spawn_guard=true ignored by client-only "
        "DaemonGuard; external daemon launch is handled by RootDaemonManager");
  }

  initialized_ = true;
  spdlog::info("Core initialized, mode={} guardAliveAfter={} childProcess={}",
               (int)mode_, guard_.isAlive(), guard_.isChildProcess());
  return true;
}

void Core::setWorkingMode(const std::string &mode) {
  if (mode == "root")
    mode_ = WorkingMode::Root;
  else if (mode == "adb")
    mode_ = WorkingMode::Adb;
  else
    mode_ = WorkingMode::Basic;
  spdlog::info("Working mode set to {}", mode);
}

std::string Core::execute(const std::string &type,
                          const std::string &jsonArgs) {
  return shouldUseDaemon() ? daemonExecute(type, jsonArgs)
                           : errorJson("daemon_unavailable");
}

std::string Core::daemonExecute(const std::string &type,
                                const std::string &jsonArgs) {
  spdlog::debug("daemonExecute: type={} socket=@{}", type, guard_.socketName());
  return guard_.sendMessage(type + ":" + jsonArgs);
}

bool Core::shouldUseDaemon() const {
  return mode_ == WorkingMode::Root && guard_.isAlive() &&
         !guard_.isChildProcess();
}

std::string Core::getBatteryInfo() {
  return shouldUseDaemon() ? daemonExecute("battery-info", "{}")
                           : errorJson("daemon_unavailable");
}

} // namespace tools
