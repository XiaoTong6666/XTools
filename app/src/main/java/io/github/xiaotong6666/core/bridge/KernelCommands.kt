package io.github.xiaotong6666.core.bridge

import io.github.xiaotong6666.core.native.Core
import org.json.JSONArray
import org.json.JSONObject

internal data class KernelPathEntry(
    val name: String,
    val path: String,
    val value: String,
    val isDir: Boolean,
)

internal fun readKernelProp(path: String): String = Core.executeCommand(
    "get-kernel-prop",
    JSONObject().put("path", path).toString(),
).trim()

internal fun readKernelProps(paths: List<String>): Map<String, String> {
    if (paths.isEmpty()) return emptyMap()
    val raw = Core.executeCommand("get-kernel-props", JSONArray(paths).toString())
    val json = JSONObject(raw)
    val result = LinkedHashMap<String, String>(paths.size)
    for (path in paths) {
        result[path] = json.optString(path, "").trim()
    }
    return result
}

internal fun executeToolsCommand(type: String, payload: JSONObject) {
    Core.executeCommand(type, payload.toString())
}

internal fun executeToolsCommand(type: String) {
    Core.executeCommand(type)
}

internal fun writeKernelProp(path: String, value: String) {
    Core.executeCommand(
        "set-kernel-prop",
        JSONObject().put("path", path).put("value", value).toString(),
    )
}

internal fun writeTextPath(path: String, text: String) {
    val raw = Core.executeCommand(
        "text-write",
        JSONObject().put("path", path).put("text", text).toString(),
    )
    val json = JSONObject(raw)
    if (json.has("error")) {
        throw IllegalStateException("text-write failed: ${json.optString("error")}")
    }
}

internal fun readPathEntries(path: String, suffix: String = ""): List<KernelPathEntry> {
    val raw = Core.executeCommand(
        "path-list",
        JSONObject().put("path", path).put("suffix", suffix).toString(),
    )
    val json = JSONObject(raw)
    if (json.has("error")) {
        throw IllegalStateException("path-list failed: ${json.optString("error")}")
    }
    val itemsJson = json.optJSONArray("items") ?: JSONArray()
    val items = ArrayList<KernelPathEntry>(itemsJson.length())
    for (i in 0 until itemsJson.length()) {
        val item = itemsJson.optJSONObject(i) ?: continue
        items.add(
            KernelPathEntry(
                name = item.optString("name", ""),
                path = item.optString("path", ""),
                value = item.optString("value", ""),
                isDir = item.optBoolean("is_dir", false),
            ),
        )
    }
    return items
}
