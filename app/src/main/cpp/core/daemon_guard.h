#pragma once
#include <chrono>
#include <mutex>
#include <string>
#include <sys/types.h>
#include <sys/un.h>

namespace tools {

class DaemonGuard {
public:
  bool isAlive() const;
  bool isChildProcess() const;
  std::string sendMessage(const std::string &msg);
  void configure(const std::string &socketName, const std::string &sessionToken,
                 int allowedUid, const std::string &processName);
  const std::string &socketName() const { return socketName_; }

private:
  int connectSocket() const;
  bool probeSocketAlive() const;

  std::string socketName_ = "tools_daemon";
  std::string sessionToken_;
  int allowedUid_ = -1;
  mutable std::mutex clientMutex_;
  mutable std::chrono::steady_clock::time_point lastAliveAt_{};
};

} // namespace tools
