package io.github.xiaotong6666.feature.swap

import android.content.Context

internal data class MemoryProfile(
    val swapEnabled: Boolean,
    val swapSizeMB: Int,
    val swapPriority: Int,
    val swapUsesLoop: Boolean,
    val zramEnabled: Boolean,
    val zramSizeMB: Int,
    val zramAlgorithm: String,
    val swappiness: Int,
    val extraFreeKbytes: Long?,
    val watermarkScaleFactor: Int?,
)

internal object MemoryProfileStore {
    private const val PreferencesName = "memory_profile"
    private const val KeyAutoApply = "auto_apply"
    private const val KeySwapEnabled = "swap_enabled"
    private const val KeySwapSizeMB = "swap_size_mb"
    private const val KeySwapPriority = "swap_priority"
    private const val KeySwapUsesLoop = "swap_uses_loop"
    private const val KeyZramEnabled = "zram_enabled"
    private const val KeyZramSizeMB = "zram_size_mb"
    private const val KeyZramAlgorithm = "zram_algorithm"
    private const val KeySwappiness = "swappiness"
    private const val KeyExtraFreeKbytes = "extra_free_kbytes"
    private const val KeyWatermarkScaleFactor = "watermark_scale_factor"

    fun load(context: Context): MemoryProfile? {
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        if (!preferences.contains(KeyZramSizeMB)) return null
        return MemoryProfile(
            swapEnabled = preferences.getBoolean(KeySwapEnabled, false),
            swapSizeMB = preferences.getInt(KeySwapSizeMB, 0),
            swapPriority = preferences.getInt(KeySwapPriority, -2),
            swapUsesLoop = preferences.getBoolean(KeySwapUsesLoop, false),
            zramEnabled = preferences.getBoolean(KeyZramEnabled, false),
            zramSizeMB = preferences.getInt(KeyZramSizeMB, 0),
            zramAlgorithm = preferences.getString(KeyZramAlgorithm, "lz4").orEmpty(),
            swappiness = preferences.getInt(KeySwappiness, 60),
            extraFreeKbytes = if (preferences.contains(KeyExtraFreeKbytes)) {
                preferences.getLong(KeyExtraFreeKbytes, 0L)
            } else {
                null
            },
            watermarkScaleFactor = if (preferences.contains(KeyWatermarkScaleFactor)) {
                preferences.getInt(KeyWatermarkScaleFactor, -1)
            } else {
                null
            },
        )
    }

    fun save(context: Context, state: SwapState) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit()
            .putBoolean(KeySwapEnabled, state.swapExists)
            .putInt(KeySwapSizeMB, state.swapFileSize)
            .putInt(KeySwapPriority, state.swapPriority)
            .putBoolean(KeySwapUsesLoop, state.swapUsesLoop)
            .putBoolean(KeyZramEnabled, state.zramEnabled)
            .putInt(KeyZramSizeMB, state.zramSizeMB)
            .putString(KeyZramAlgorithm, state.zramAlgorithm)
            .putInt(KeySwappiness, state.swappiness)
            .apply {
                if (state.extraFreeSupported) {
                    putLong(KeyExtraFreeKbytes, state.extraFreeKbytes)
                } else {
                    remove(KeyExtraFreeKbytes)
                }
                if (state.watermarkSupported) {
                    putInt(KeyWatermarkScaleFactor, state.watermarkScaleFactor)
                } else {
                    remove(KeyWatermarkScaleFactor)
                }
            }
            .apply()
    }

    fun isAutoApplyEnabled(context: Context): Boolean = context
        .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        .getBoolean(KeyAutoApply, false)

    fun setAutoApplyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit()
            .putBoolean(KeyAutoApply, enabled)
            .apply()
    }
}

internal fun applyMemoryProfile(profile: MemoryProfile) {
    require(!profile.swapEnabled || profile.swapSizeMB > 0) { "invalid swap profile" }
    require(!profile.zramEnabled || profile.zramSizeMB > 0) { "invalid zram profile" }
    require(profile.swapPriority in setOf(-2, 0, 5)) { "invalid swap priority" }
    require(profile.swappiness in 0..200) { "invalid swappiness" }
    val current = loadSwapState()
    if (profile.swapEnabled) {
        if (!current.swapExists || current.swapFileSize != profile.swapSizeMB ||
            current.swapUsesLoop != profile.swapUsesLoop
        ) {
            applySwapCreate(profile.swapSizeMB, profile.swapPriority, profile.swapUsesLoop)
        } else if (current.swapPriority != profile.swapPriority) {
            applySwapPriority(profile.swapPriority)
        }
    } else if (current.swapExists) {
        applySwapDisable(removeFile = false)
    }

    if (profile.zramEnabled) {
        require(current.zramDevices.size <= 1) { "multiple active zram devices are not configurable" }
        if (!current.zramEnabled || current.zramSizeMB != profile.zramSizeMB ||
            current.zramAlgorithm != profile.zramAlgorithm
        ) {
            applyZramResize(profile.zramSizeMB, profile.zramAlgorithm)
        }
    } else if (current.zramEnabled) {
        applyZramDisable()
    }

    applyVmParameters(
        swappiness = profile.swappiness,
        extraFreeKbytes = profile.extraFreeKbytes.takeIf { current.extraFreeSupported },
        watermarkScaleFactor = profile.watermarkScaleFactor.takeIf { current.watermarkSupported },
    )
}
