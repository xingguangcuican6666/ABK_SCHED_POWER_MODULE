package com.abk.extension.schedpower

import android.content.Context

internal object SchedulerPolicyController {
    fun applyCurrentConfig(context: Context, bridge: HostBridge): Result<Unit> {
        val config = bridge.read().getOrElse { return Result.failure(it) }
        val foreground = bridge.readForegroundPackage().getOrElse { "" }.trim()
        val effective = if (config.perAppEnabled) {
            config.appRules.firstOrNull { it.packageName == foreground }
        } else {
            null
        }
        val mode = effective?.mode ?: config.defaultMode
        val displayState = effective?.conservativeDisplayState ?: config.conservativeDisplayState
        val modeCommand = if (mode == "perf") "command sched_power_backport mode aggressive"
        else "command sched_power_backport mode conservative"
        bridge.runControlCommand(modeCommand).getOrElse { return Result.failure(it) }
        bridge.runControlCommand("command sched_power_backport display_state $displayState")
            .getOrElse { return Result.failure(it) }
        return Result.success(Unit)
    }
}
