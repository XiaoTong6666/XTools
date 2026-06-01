#include "daemon_guard.h"
#include <cstring>
#include <nlohmann/json.hpp>
#include <poll.h>
#include <spdlog/spdlog.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

namespace tools {

using json = nlohmann::json;

static std::string summarizePayloadType(const std::string &payload) {
  auto colon = payload.find(':');
  if (colon == std::string::npos)
    return payload;
  return payload.substr(0, colon);
}

static bool writeFully(int fd, const void *buffer, size_t length) {
  const char *ptr = static_cast<const char *>(buffer);
  size_t total = 0;
  while (total < length) {
    ssize_t n = write(fd, ptr + total, length - total);
    if (n < 0) {
      if (errno == EINTR)
        continue;
      if (errno == EAGAIN || errno == EWOULDBLOCK) {
        struct pollfd pfd{};
        pfd.fd = fd;
        pfd.events = POLLOUT;
        int pollResult = poll(&pfd, 1, 2000);
        if (pollResult > 0)
          continue;
      }
      return false;
    }
    if (n == 0)
      return false;
    total += static_cast<size_t>(n);
  }
  return true;
}

static bool readFully(int fd, void *buffer, size_t length) {
  char *ptr = static_cast<char *>(buffer);
  size_t total = 0;
  while (total < length) {
    ssize_t n = read(fd, ptr + total, length - total);
    if (n == 0)
      return false;
    if (n < 0) {
      if (errno == EINTR)
        continue;
      if (errno == EAGAIN || errno == EWOULDBLOCK) {
        struct pollfd pfd{};
        pfd.fd = fd;
        pfd.events = POLLIN;
        int pollResult = poll(&pfd, 1, 200);
        if (pollResult > 0)
          continue;
      }
      return false;
    }
    total += static_cast<size_t>(n);
  }
  return true;
}

static void setSocketTimeouts(int fd, int timeoutMs) {
  timeval tv{};
  tv.tv_sec = timeoutMs / 1000;
  tv.tv_usec = (timeoutMs % 1000) * 1000;
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

bool DaemonGuard::isAlive() const {
  std::lock_guard<std::mutex> lock(clientMutex_);
  auto now = std::chrono::steady_clock::now();
  if (lastAliveAt_.time_since_epoch().count() != 0) {
    auto ageMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                     now - lastAliveAt_)
                     .count();
    if (ageMs <= 5000)
      return true;
  }
  if (probeSocketAlive()) {
    lastAliveAt_ = now;
    return true;
  }
  lastAliveAt_ = {};
  return false;
}

bool DaemonGuard::isChildProcess() const { return false; }

std::string DaemonGuard::sendMessage(const std::string &msg) {
  std::lock_guard<std::mutex> lock(clientMutex_);
  auto startedAt = std::chrono::steady_clock::now();
  auto payloadType = summarizePayloadType(msg);
  std::string wire = json{{"token", sessionToken_}, {"payload", msg}}.dump();
  for (int attempt = 0; attempt < 2; ++attempt) {
    int clientFd = connectSocket();
    if (clientFd < 0) {
      lastAliveAt_ = {};
      return R"({"error":"connect_failed"})";
    }
    setSocketTimeouts(clientFd, 10000);
    uint32_t msgLen = wire.size();
    if (!writeFully(clientFd, &msgLen, 4) ||
        !writeFully(clientFd, wire.data(), msgLen)) {
      spdlog::warn("DaemonGuard: write failed to @{}: {}", socketName_,
                   strerror(errno));
      close(clientFd);
      continue;
    }
    uint32_t respLen = 0;
    if (!readFully(clientFd, &respLen, 4) || respLen > 4 * 1024 * 1024) {
      spdlog::warn("DaemonGuard: failed to read response header from @{}",
                   socketName_);
      close(clientFd);
      continue;
    }
    std::vector<char> buf(respLen + 1);
    if (!readFully(clientFd, buf.data(), respLen)) {
      spdlog::warn("DaemonGuard: failed to read response body from @{}",
                   socketName_);
      close(clientFd);
      continue;
    }
    close(clientFd);
    buf[respLen] = '\0';
    lastAliveAt_ = std::chrono::steady_clock::now();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::steady_clock::now() - startedAt)
                         .count();
    spdlog::debug("DaemonGuard: sendMessage type={} req_bytes={} resp_bytes={} "
                  "elapsed_ms={} attempt={}",
                  payloadType, msg.size(), respLen, elapsedMs, attempt + 1);
    return std::string(buf.data(), respLen);
  }
  lastAliveAt_ = {};
  return R"({"error":"read_failed"})";
}

void DaemonGuard::configure(const std::string &socketName,
                            const std::string &sessionToken, int allowedUid,
                            const std::string &processName) {
  socketName_ = socketName;
  sessionToken_ = sessionToken;
  allowedUid_ = allowedUid;
  lastAliveAt_ = {};
  (void)processName;
}

int DaemonGuard::connectSocket() const {
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd < 0)
    return -1;
  struct sockaddr_un addr{};
  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0';
  strncpy(addr.sun_path + 1, socketName_.c_str(), sizeof(addr.sun_path) - 2);
  socklen_t len =
      offsetof(struct sockaddr_un, sun_path) + 1 + socketName_.size();
  if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
    spdlog::warn("DaemonGuard: connect to @{} failed: {}", socketName_,
                 strerror(errno));
    close(fd);
    return -1;
  }
  return fd;
}

bool DaemonGuard::probeSocketAlive() const {
  int clientFd = connectSocket();
  if (clientFd < 0) {
    return false;
  }
  close(clientFd);
  return true;
}

} // namespace tools
