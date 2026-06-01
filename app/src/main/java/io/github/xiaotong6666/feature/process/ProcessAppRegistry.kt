package io.github.xiaotong6666.feature.process

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ProcessAppMetadata(
    val packageName: String,
    val label: String,
    val applicationInfo: ApplicationInfo,
)

internal class ProcessAppRegistry private constructor(
    private val byProcessName: Map<String, ProcessAppMetadata>,
    private val byPackageName: Map<String, ProcessAppMetadata>,
    private val byAppId: Map<Int, List<ProcessAppMetadata>>,
) {
    fun resolve(process: ToolsProcessData): ProcessAppMetadata? {
        byProcessName[process.name]?.let { return it }
        byPackageName[process.name.substringBefore(':')]?.let { return it }
        if (!process.isAppProcess || process.uid < 0) return null
        return byAppId[process.uid % ANDROID_USER_OFFSET]?.singleOrNull()
    }

    companion object {
        val Empty = ProcessAppRegistry(emptyMap(), emptyMap(), emptyMap())

        @Suppress("DEPRECATION")
        fun load(context: Context): ProcessAppRegistry {
            val packageManager = context.packageManager
            val applications = packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            val metadata = applications.map { applicationInfo ->
                val packageName = applicationInfo.packageName
                ProcessAppMetadata(
                    packageName = packageName,
                    label = runCatching { applicationInfo.loadLabel(packageManager).toString() }
                        .getOrDefault(packageName)
                        .ifBlank { packageName },
                    applicationInfo = applicationInfo,
                )
            }
            return ProcessAppRegistry(
                byProcessName = metadata.associateBy { it.applicationInfo.processName },
                byPackageName = metadata.associateBy(ProcessAppMetadata::packageName),
                byAppId = metadata.groupBy { it.applicationInfo.uid % ANDROID_USER_OFFSET },
            )
        }
    }
}

@Composable
internal fun rememberProcessAppRegistry(): ProcessAppRegistry {
    val context = LocalContext.current.applicationContext
    var registry by remember(context) { mutableStateOf(ProcessAppRegistry.Empty) }
    LaunchedEffect(context) {
        registry = withContext(Dispatchers.IO) { ProcessAppRegistry.load(context) }
    }
    return registry
}

private const val ANDROID_USER_OFFSET = 100_000
