package io.github.xiaotong6666.core.daemon

import android.os.Build
import android.os.Process
import io.github.xiaotong6666.tools.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

data class DaemonConfig(
    val abi: String,
    val socketName: String,
    val sessionToken: String,
    val allowedUid: Int,
    val daemonVersion: String,
    val buildTime: String,
    val protocolVersion: Int,
    val binaryRevision: Int,
    val binaryName: String = "tools-daemon",
    val processName: String = "tools-daemon",
    val featureFlags: List<String> = listOf(
        "abstract_unix_socket",
        "uid_token_auth",
        "home_snapshot",
        "cpu_control_snapshot",
    ),
) {
    val assetPath: String
        get() = "$abi/$binaryName"

    fun baseDir(context: android.content.Context): File = File(context.filesDir, "daemon/$abi")

    fun executableFile(context: android.content.Context): File = File(baseDir(context), binaryName)

    fun configFile(context: android.content.Context): File = File(baseDir(context), "daemon-config.json")

    fun infoFile(context: android.content.Context): File = File(baseDir(context), "daemon-info.json")

    fun toInitJson(spawnGuard: Boolean): String = JSONObject()
        .put("mode", "root")
        .put("spawn_guard", spawnGuard)
        .put("socket_name", socketName)
        .put("session_token", sessionToken)
        .put("allowed_uid", allowedUid)
        .put("daemon_process_name", processName)
        .put("daemon_version", daemonVersion)
        .put("build_time", buildTime)
        .put("protocol_version", protocolVersion)
        .put("binary_revision", binaryRevision)
        .put("feature_flags", JSONArray(featureFlags))
        .toString()

    fun toInfoJson(): String = JSONObject()
        .put("abi", abi)
        .put("binary_name", binaryName)
        .put("socket_name", socketName)
        .put("daemon_version", daemonVersion)
        .put("build_time", buildTime)
        .put("protocol_version", protocolVersion)
        .put("binary_revision", binaryRevision)
        .toString()

    fun matchesInstalledInfo(context: android.content.Context): Boolean {
        return runCatching {
            val file = infoFile(context)
            if (!file.isFile) return@runCatching false
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.optString("abi") == abi &&
                json.optString("binary_name") == binaryName &&
                json.optString("daemon_version") == daemonVersion &&
                json.optString("build_time") == buildTime &&
                json.optInt("protocol_version", -1) == protocolVersion &&
                json.optInt("binary_revision", -1) == binaryRevision
        }.getOrDefault(false)
    }

    companion object {
        private const val SOCKET_NAME_PREFIX = "tools_daemon_"
        private const val PROTOCOL_VERSION = 1
        private const val BINARY_REVISION = 13

        fun create(): DaemonConfig = DaemonConfig(
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            socketName = SOCKET_NAME_PREFIX + randomHex(16),
            sessionToken = randomHex(32),
            allowedUid = Process.myUid(),
            daemonVersion = BuildConfig.VERSION_NAME,
            buildTime = BuildConfig.VERSION_CODE.toString(),
            protocolVersion = PROTOCOL_VERSION,
            binaryRevision = BINARY_REVISION,
        )

        private fun randomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
