package io.github.xiaotong6666.core.daemon

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object RootDaemonManager {
    private const val TAG = "RootDaemonMgr"
    private const val WATCHDOG_INTERVAL_MS = 5000L
    private const val START_RETRY_BACKOFF_MS = 5000L

    @Volatile
    private var started = false

    @Volatile
    private var lastStatus = "idle"

    @Volatile
    private var daemonConfig: DaemonConfig? = null

    @Volatile
    private var watchdogStarted = false

    @Volatile
    private var nextStartRetryAtMs = 0L

    @Volatile
    private var unsupportedAssetPath: String? = null

    fun lastStatus(): String = lastStatus

    fun currentSocketName(): String = daemonConfig?.socketName ?: ""

    fun buildClientInitConfig(context: Context): String = getOrCreateConfig(context).toInitJson(spawnGuard = false)

    private fun getOrCreateConfig(context: Context): DaemonConfig {
        daemonConfig?.let { return it }
        val config = loadPersistedConfig(context) ?: DaemonConfig.create()
        daemonConfig = config
        return config
    }

    private fun loadPersistedConfig(context: Context): DaemonConfig? {
        return runCatching {
            val current = DaemonConfig.create()
            val infoFile = current.infoFile(context)
            val configFile = current.configFile(context)
            if (!infoFile.isFile || !configFile.isFile) return@runCatching null

            val info = JSONObject(infoFile.readText(Charsets.UTF_8))
            if (info.optString("abi") != current.abi) return@runCatching null
            if (info.optString("binary_name") != current.binaryName) return@runCatching null
            if (info.optString("daemon_version") != current.daemonVersion) return@runCatching null
            if (info.optString("build_time") != current.buildTime) return@runCatching null
            if (info.optInt("protocol_version", -1) != current.protocolVersion) return@runCatching null
            if (info.optInt("binary_revision", -1) != current.binaryRevision) return@runCatching null

            val config = JSONObject(configFile.readText(Charsets.UTF_8))
            val featureFlags = buildList {
                val array = config.optJSONArray("feature_flags") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }

            DaemonConfig(
                abi = current.abi,
                socketName = config.optString("socket_name", current.socketName),
                sessionToken = config.optString("session_token", current.sessionToken),
                allowedUid = config.optInt("allowed_uid", current.allowedUid),
                daemonVersion = current.daemonVersion,
                buildTime = current.buildTime,
                protocolVersion = current.protocolVersion,
                binaryRevision = current.binaryRevision,
                binaryName = info.optString("binary_name", current.binaryName),
                processName = config.optString("daemon_process_name", current.processName),
                featureFlags = featureFlags.ifEmpty { current.featureFlags },
            )
        }.onFailure {
            Log.w(TAG, "loadPersistedConfig: ${it.message}")
        }.getOrNull()
    }

    private fun replaceFileAtomically(target: File, writer: (File) -> Unit): File {
        val parent = target.parentFile ?: error("Missing parent for ${target.absolutePath}")
        parent.mkdirs()
        val temp = File(parent, "${target.name}.tmp")
        if (temp.exists()) {
            temp.delete()
        }
        writer(temp)
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return target
    }

    private fun extractDaemonBinary(context: Context, config: DaemonConfig, force: Boolean): String? = runCatching {
        val target = config.executableFile(context)
        replaceFileAtomically(target) { temp ->
            context.assets.open(config.assetPath).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            }
            temp.setReadable(true, true)
            temp.setWritable(true, true)
            temp.setExecutable(true, true)
        }
        replaceFileAtomically(config.infoFile(context)) { temp ->
            temp.writeText(config.toInfoJson(), Charsets.UTF_8)
            temp.setReadable(true, true)
            temp.setWritable(true, true)
            temp.setExecutable(false, false)
        }
        target.absolutePath
    }.onFailure {
        Log.e(TAG, "ensureStarted: asset extraction failed: ${it.message}")
    }.getOrNull()

    private fun writeDaemonConfig(context: Context, config: DaemonConfig): String? = runCatching {
        val file = replaceFileAtomically(config.configFile(context)) { temp ->
            temp.writeText(config.toInitJson(spawnGuard = true), Charsets.UTF_8)
            temp.setReadable(true, true)
            temp.setWritable(true, true)
            temp.setExecutable(false, false)
        }
        file.absolutePath
    }.onFailure {
        Log.e(TAG, "ensureStarted: config write failed: ${it.message}")
    }.getOrNull()

    private data class ProbeResult(
        val socketCheck: String,
        val processCheck: String,
    )

    private fun hasDaemonAsset(context: Context, config: DaemonConfig): Boolean = runCatching {
        context.assets.open(config.assetPath).use { }
        true
    }.getOrDefault(false)

    private fun cleanupDaemonProcesses(context: Context, config: DaemonConfig) {
        val packagePathPattern = "/data/user/0/${context.packageName}/files/daemon/.*/${config.binaryName}"
        val cleanupResult = runLocalRootCommand(
            "pkill -KILL -f '$packagePathPattern' >/dev/null 2>&1 || true\n" +
                "pkill -KILL -x ${config.processName} >/dev/null 2>&1 || true",
        )
        Log.i(TAG, "cleanupDaemonProcesses: result='$cleanupResult'")
    }

    private fun runLocalRootCommand(command: String): String = runCatching {
        val process = Runtime.getRuntime().exec("su")
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(command)
            writer.write("\nexit\n")
            writer.flush()
        }
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        process.waitFor()
        stdout.ifEmpty { stderr }
    }.onFailure {
        Log.e(TAG, "runLocalRootCommand failed: ${it.message}")
    }.getOrDefault("error")

    private fun probeDaemon(config: DaemonConfig): ProbeResult {
        val socketCheck = runLocalRootCommand(
            "grep -q '@${config.socketName}' /proc/net/unix && echo 1 || echo 0",
        ).trim()
        val processCheck = runLocalRootCommand(
            "ps -A -o PID,NAME | grep 'tools[-_]daemon' || true",
        ).trim()
        return ProbeResult(socketCheck = socketCheck, processCheck = processCheck)
    }

    private fun startWatchdogIfNeeded(context: Context) {
        if (watchdogStarted) return
        synchronized(this) {
            if (watchdogStarted) return
            watchdogStarted = true
        }

        val appContext = context.applicationContext
        Thread(
            {
                while (true) {
                    try {
                        Thread.sleep(WATCHDOG_INTERVAL_MS)
                        val config = daemonConfig ?: continue
                        val probe = probeDaemon(config)
                        if (probe.socketCheck == "1") continue

                        Log.w(
                            TAG,
                            "watchdog: daemon socket missing, restarting; processCheck=${probe.processCheck}",
                        )
                        started = false
                        ensureStarted(appContext)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    } catch (t: Throwable) {
                        Log.e(TAG, "watchdog failure: ${t.message}")
                    }
                }
            },
            "RootDaemonWatchdog",
        ).apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        val config = getOrCreateConfig(context)
        val installedMatches = config.matchesInstalledInfo(context)
        val now = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "ensureStarted: begin started=$started socket=@${config.socketName} uid=${config.allowedUid} version=${config.daemonVersion} protocol=${config.protocolVersion} installedMatches=$installedMatches",
        )
        if (unsupportedAssetPath == config.assetPath) {
            lastStatus = "unsupported_abi:${config.assetPath}"
            return false
        }
        if (!hasDaemonAsset(context, config)) {
            unsupportedAssetPath = config.assetPath
            lastStatus = "unsupported_abi:${config.assetPath}"
            Log.e(TAG, "ensureStarted: daemon asset missing for abi=${config.abi}, assetPath=${config.assetPath}")
            return false
        }
        if (!started && installedMatches) {
            val probe = probeDaemon(config)
            if (probe.socketCheck == "1") {
                started = true
                nextStartRetryAtMs = 0L
                lastStatus = "reused_existing_daemon socket=1 process=${probe.processCheck}"
                Log.i(TAG, "ensureStarted: reused running daemon, status=$lastStatus")
                startWatchdogIfNeeded(context)
                return true
            }
        }
        if (started) {
            val probe = probeDaemon(config)
            if (probe.socketCheck == "1") {
                Log.i(TAG, "ensureStarted: already started, status=$lastStatus")
                startWatchdogIfNeeded(context)
                return true
            }
            Log.w(TAG, "ensureStarted: stale started state detected, retrying launch")
            started = false
        }
        if (now < nextStartRetryAtMs) {
            lastStatus = "retry_backoff_until=$nextStartRetryAtMs previous=$lastStatus"
            Log.i(TAG, "ensureStarted: backing off until elapsedRealtime=$nextStartRetryAtMs")
            return false
        }
        if (!installedMatches) {
            Log.i(TAG, "ensureStarted: daemon version/protocol changed, forcing cleanup and extraction")
        }
        cleanupDaemonProcesses(context, config)
        val daemonPath = extractDaemonBinary(context, config, force = !installedMatches) ?: run {
            nextStartRetryAtMs = now + START_RETRY_BACKOFF_MS
            lastStatus = "extract_failed:${config.assetPath}"
            Log.e(TAG, "ensureStarted: asset extraction failed, assetPath=${config.assetPath}")
            return false
        }
        val configPath = writeDaemonConfig(context, config) ?: run {
            nextStartRetryAtMs = now + START_RETRY_BACKOFF_MS
            lastStatus = "config_write_failed:${config.socketName}"
            return false
        }
        val daemonFile = File(daemonPath)
        if (!daemonFile.exists()) {
            nextStartRetryAtMs = now + START_RETRY_BACKOFF_MS
            lastStatus = "missing_file:$daemonPath"
            Log.e(TAG, "ensureStarted: extracted file missing, daemonPath=$daemonPath")
            return false
        }
        Log.i(TAG, "ensureStarted: assetPath=${config.assetPath} daemonPath=$daemonPath configPath=$configPath size=${daemonFile.length()}")

        val launchResult = runLocalRootCommand(
            "pkill -f '/data/user/0/${context.packageName}/files/daemon/.*/${config.binaryName}' >/dev/null 2>&1 || true\n" +
                "chmod 700 \"$daemonPath\"\n" +
                "nohup \"$daemonPath\" \"$configPath\" >/dev/null 2>&1 &",
        )
        var socketCheck = "0"
        var processCheck = ""
        for (attempt in 0 until 10) {
            val probe = probeDaemon(config)
            socketCheck = probe.socketCheck
            processCheck = probe.processCheck
            Log.i(TAG, "ensureStarted: probe attempt=${attempt + 1} socketCheck=$socketCheck processCheck=$processCheck")
            if (socketCheck == "1") {
                break
            }
            Thread.sleep(200)
        }
        lastStatus = "launchResult=$launchResult socket=$socketCheck process=$processCheck socketName=${config.socketName} path=$daemonPath"
        Log.i(TAG, "ensureStarted: chmod+nohup result='$launchResult'")
        Log.i(TAG, "ensureStarted: final socketCheck=$socketCheck")
        Log.i(TAG, "ensureStarted: final processCheck=$processCheck")

        started = socketCheck == "1"
        if (!started) {
            nextStartRetryAtMs = SystemClock.elapsedRealtime() + START_RETRY_BACKOFF_MS
            Log.w(TAG, "ensureStarted: daemon not detected after launch, status=$lastStatus")
        } else {
            nextStartRetryAtMs = 0L
            startWatchdogIfNeeded(context)
        }
        return started
    }
}
