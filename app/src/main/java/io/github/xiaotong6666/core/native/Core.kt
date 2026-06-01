package io.github.xiaotong6666.core.native

import android.os.Looper
import android.util.Log
import io.github.xiaotong6666.app.ToolsApp
import io.github.xiaotong6666.core.daemon.RootDaemonManager

object Core {
    private const val TAG = "CoreInit"
    private const val SLOW_CALL_MS = 400L

    @Volatile
    private var initialized = false

    @Volatile
    private var lastInitSummary = "never"

    init {
        System.loadLibrary("tools-jni")
    }

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        val daemonAliveBefore = runCatching { nativeIsAlive() }.getOrDefault(false)
        Log.d(
            TAG,
            "ensureInitialized: enter initialized=$initialized daemonAliveBefore=$daemonAliveBefore spawn_guard=false lastInitSummary=$lastInitSummary",
        )
        val initConfig = RootDaemonManager.buildClientInitConfig(ToolsApp.context)
        val daemonStarted = runCatching {
            RootDaemonManager.ensureStarted(ToolsApp.context)
        }.getOrDefault(false)
        Log.i(
            TAG,
            "ensureInitialized: daemonStarted=$daemonStarted socket=@${RootDaemonManager.currentSocketName()} daemonStatus=${RootDaemonManager.lastStatus()}",
        )
        initialized = runCatching {
            nativeInit(initConfig)
        }.getOrDefault(false)
        val daemonAlive = runCatching { nativeIsAlive() }.getOrDefault(false)
        initialized = initialized && daemonAlive
        lastInitSummary = "initialized=$initialized daemonStarted=$daemonStarted daemonAlive=$daemonAlive"
        Log.i(TAG, "ensureInitialized: nativeInit initialized=$initialized spawn_guard=false nativeIsAlive=$daemonAlive")
    }

    fun executeCommand(type: String, jsonArgs: String = "{}"): String {
        ensureInitialized()
        var result = executeWithDiagnostics(type, jsonArgs)
        if (isTransportError(result)) {
            invalidateInitialization()
            ensureInitialized()
            result = executeWithDiagnostics(type, jsonArgs)
        }
        return result
    }

    @Synchronized
    private fun invalidateInitialization() {
        initialized = false
    }

    private fun isTransportError(result: String): Boolean = result == "{\"error\":\"connect_failed\"}" ||
        result == "{\"error\":\"read_failed\"}" ||
        result == "{\"error\":\"daemon_unavailable\"}"

    private fun executeWithDiagnostics(type: String, jsonArgs: String): String {
        val startedAt = System.nanoTime()
        val result = nativeExecute(type, jsonArgs)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        if (elapsedMs >= SLOW_CALL_MS) {
            Log.w(
                TAG,
                "slow native call type=$type elapsedMs=$elapsedMs mainThread=${Looper.myLooper() == Looper.getMainLooper()}",
            )
        }
        return result
    }

    @JvmStatic
    external fun nativeInit(configJson: String): Boolean

    @JvmStatic
    external fun nativeIsAlive(): Boolean

    @JvmStatic
    external fun nativeExecute(cmdType: String, jsonArgs: String): String

    @JvmStatic
    external fun nativeGetBatteryInfo(): String

    external fun nativeGetGpuRenderer(): String
}
