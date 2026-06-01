#pragma once
#include "daemon_guard.h"
#include <string>
#include <vector>

namespace tools {

enum class WorkingMode { Root, Adb, Basic };

class Core {
public:
  static Core &instance();

  bool initialize(const std::string &configJson);
  std::string execute(const std::string &type, const std::string &jsonArgs);
  std::string getBatteryInfo();

  DaemonGuard &daemon() { return guard_; }

private:
  Core();
  Core(const Core &) = delete;
  Core &operator=(const Core &) = delete;

  void setWorkingMode(const std::string &mode);
  std::string daemonExecute(const std::string &type,
                            const std::string &jsonArgs);
  bool shouldUseDaemon() const;

  DaemonGuard guard_;
  WorkingMode mode_ = WorkingMode::Root;
  bool initialized_ = false;
  std::string daemonSocketName_ = "tools_daemon";
  std::string daemonProcessName_ = "tools-daemon";
  std::string daemonVersion_ = "unknown";
  std::string daemonBuildTime_ = "unknown";
  int daemonProtocolVersion_ = 0;
  std::vector<std::string> daemonFeatureFlags_;
};

} // namespace tools
