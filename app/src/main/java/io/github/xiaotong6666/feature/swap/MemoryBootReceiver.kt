package io.github.xiaotong6666.feature.swap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MemoryBootReceiver : BroadcastReceiver() {
    private companion object {
        const val Tag = "MemoryBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (!MemoryProfileStore.isAutoApplyEnabled(context)) return
        val profile = MemoryProfileStore.load(context) ?: return
        val pendingResult = goAsync()
        Thread(
            {
                runCatching { applyMemoryProfile(profile) }
                    .onFailure { error -> Log.e(Tag, "Unable to apply memory profile", error) }
                pendingResult.finish()
            },
            "MemoryProfileBootApply",
        ).start()
    }
}
