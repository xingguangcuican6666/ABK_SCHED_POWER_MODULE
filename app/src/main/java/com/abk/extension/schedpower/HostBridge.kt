package com.abk.extension.schedpower

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

private const val EXTRA_ID = "com.abk.kernel.extra.EXTENSION_ID"

data class ExtensionConfig(
    val defaultMode: String = "balanced",
    val conservativeDisplayState: Int = 9,
    val perAppEnabled: Boolean = false,
    val oobeCompleted: Boolean = false,
    val appRules: List<AppRule> = emptyList(),
) {
    data class AppRule(
        val packageName: String,
        val mode: String,
        val conservativeDisplayState: Int,
    )

    fun toJson(): String = JSONObject()
        .put("oobe_completed", oobeCompleted)
        .put("summary", summary())
        .put(
            "settings",
            JSONObject()
                .put("default_mode", defaultMode)
                .put("conservative_display_state", conservativeDisplayState)
                .put("per_app_enabled", perAppEnabled)
                .put(
                    "app_rules",
                    org.json.JSONArray().apply {
                        appRules.forEach { rule ->
                            put(
                                JSONObject()
                                    .put("package_name", rule.packageName)
                                    .put("mode", rule.mode)
                                    .put("conservative_display_state", rule.conservativeDisplayState)
                            )
                        }
                    }
                )
        )
        .toString()

    fun summary(): String = buildString {
        append(if (defaultMode == "perf") "性能优先" else "保守优先")
        append(" · State=")
        append(conservativeDisplayState)
        if (perAppEnabled && appRules.isNotEmpty()) {
            append(" · AppRules=")
            append(appRules.size)
        }
    }

    companion object {
        fun fromJson(raw: String?): ExtensionConfig {
            if (raw.isNullOrBlank()) return ExtensionConfig()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return ExtensionConfig()
            val settings = json.optJSONObject("settings")
            val rules = buildList {
                val array = settings?.optJSONArray("app_rules") ?: return@buildList
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val packageName = item.optString("package_name").trim()
                    if (packageName.isBlank()) continue
                    add(
                        AppRule(
                            packageName = packageName,
                            mode = item.optString("mode").ifBlank { "balanced" },
                            conservativeDisplayState = item.optInt("conservative_display_state", 9)
                        )
                    )
                }
            }
            return ExtensionConfig(
                defaultMode = settings?.optString("default_mode").takeUnless { it.isNullOrBlank() } ?: "balanced",
                conservativeDisplayState = settings?.optInt("conservative_display_state", 9) ?: 9,
                perAppEnabled = settings?.optBoolean("per_app_enabled", false) ?: false,
                oobeCompleted = json.optBoolean("oobe_completed", false),
                appRules = rules
            )
        }
    }
}

class HostBridge(
    private val resolver: ContentResolver,
    authority: String,
    private val extensionId: String,
) {
    private val uri: Uri = Uri.parse("content://$authority")

    fun read(): Result<ExtensionConfig> {
        val bundle = resolver.call(
            uri,
            "get_extension_state",
            extensionId,
            Bundle().apply { putString(EXTRA_ID, extensionId) }
        ) ?: return Result.failure(IllegalStateException("null bundle"))
        if (!bundle.getBoolean("success")) {
            return Result.failure(IllegalStateException(bundle.getString("error").orEmpty()))
        }
        return Result.success(
            ExtensionConfig.fromJson(bundle.getString("state_json"))
        )
    }

    fun write(config: ExtensionConfig): Result<Unit> {
        val bundle = resolver.call(
            uri,
            "put_extension_state",
            extensionId,
            Bundle().apply {
                putString(EXTRA_ID, extensionId)
                putString("state_json", config.toJson())
            }
        ) ?: return Result.failure(IllegalStateException("null bundle"))
        if (!bundle.getBoolean("success")) {
            return Result.failure(IllegalStateException(bundle.getString("error").orEmpty()))
        }
        return Result.success(Unit)
    }

    fun readControlStatus(): Result<String> {
        val bundle = resolver.call(
            uri,
            "get_control_status",
            extensionId,
            Bundle().apply { putString(EXTRA_ID, extensionId) }
        ) ?: return Result.failure(IllegalStateException("null bundle"))
        if (!bundle.getBoolean("success")) {
            return Result.failure(IllegalStateException(bundle.getString("error").orEmpty()))
        }
        return Result.success(bundle.getString("status_json").orEmpty())
    }

    fun runControlCommand(command: String): Result<Unit> {
        val bundle = resolver.call(
            uri,
            "run_control_command",
            extensionId,
            Bundle().apply {
                putString(EXTRA_ID, extensionId)
                putString("command", command)
            }
        ) ?: return Result.failure(IllegalStateException("null bundle"))
        if (!bundle.getBoolean("success")) {
            return Result.failure(IllegalStateException(bundle.getString("error").orEmpty()))
        }
        return Result.success(Unit)
    }

    fun readForegroundPackage(): Result<String> {
        val bundle = resolver.call(
            uri,
            "get_foreground_package",
            extensionId,
            Bundle().apply { putString(EXTRA_ID, extensionId) }
        ) ?: return Result.failure(IllegalStateException("null bundle"))
        if (!bundle.getBoolean("success")) {
            return Result.failure(IllegalStateException(bundle.getString("error").orEmpty()))
        }
        return Result.success(bundle.getString("package_name").orEmpty())
    }
}
