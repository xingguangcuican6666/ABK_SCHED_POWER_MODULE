package com.abk.extension.schedpower

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class OobeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostAuthority = intent.getStringExtra("com.abk.kernel.extra.HOST_PROVIDER").orEmpty()
        val extensionId = intent.getStringExtra("com.abk.kernel.extra.EXTENSION_ID").orEmpty()
        if (hostAuthority.isBlank() || extensionId.isBlank()) {
            Toast.makeText(this, getString(R.string.status_missing_host), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val bridge = HostBridge(contentResolver, hostAuthority, extensionId)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply { text = getString(R.string.oobe_title) }
        val desc = TextView(this).apply { text = getString(R.string.oobe_desc) }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val balanced = RadioButton(this).apply { text = getString(R.string.mode_balanced) }
        val perf = RadioButton(this).apply { text = getString(R.string.mode_perf) }
        group.addView(balanced)
        group.addView(perf)
        balanced.isChecked = true
        val displayLabel = TextView(this).apply { text = getString(R.string.display_state_label) }
        val displayState = EditText(this).apply { setText("9") }
        val perApp = CheckBox(this).apply { text = getString(R.string.per_app_label) }
        val rulesLabel = TextView(this).apply { text = getString(R.string.rules_label) }
        val rulesEditor = EditText(this).apply {
            minLines = 4
        }
        val save = Button(this).apply { text = getString(R.string.oobe_save) }

        save.setOnClickListener {
            val config = ExtensionConfig(
                defaultMode = if (perf.isChecked) "perf" else "balanced",
                conservativeDisplayState = displayState.text.toString().toIntOrNull() ?: 9,
                perAppEnabled = perApp.isChecked,
                oobeCompleted = true,
                appRules = parseRules(rulesEditor.text.toString())
            )
            val result = bridge.write(config)
            if (result.isSuccess) {
                val applyResult = SchedulerPolicyController.applyCurrentConfig(this, bridge)
                if (applyResult.isSuccess) {
                    startForegroundService(Intent(this, SchedulerPolicyService::class.java))
                    Toast.makeText(this, getString(R.string.status_save_ok), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, applyResult.exceptionOrNull()?.message ?: getString(R.string.status_save_fail), Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, result.exceptionOrNull()?.message ?: getString(R.string.status_save_fail), Toast.LENGTH_LONG).show()
            }
        }

        root.addView(title)
        root.addView(desc)
        root.addView(group)
        root.addView(displayLabel)
        root.addView(displayState)
        root.addView(perApp)
        root.addView(rulesLabel)
        root.addView(rulesEditor)
        root.addView(save)
        setContentView(root)
    }

    private fun parseRules(raw: String): List<ExtensionConfig.AppRule> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val packageName = parts[0].trim()
                if (packageName.isBlank()) return@mapNotNull null
                val valueParts = parts[1].split(":", limit = 2)
                val mode = valueParts[0].trim().ifBlank { "balanced" }
                val state = valueParts.getOrNull(1)?.trim()?.toIntOrNull() ?: 9
                ExtensionConfig.AppRule(packageName, mode, state)
            }
            .toList()
}
