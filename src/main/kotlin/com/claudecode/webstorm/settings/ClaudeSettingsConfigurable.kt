package com.claudecode.webstorm.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

class ClaudeSettingsConfigurable : BoundSearchableConfigurable(
    "Claude Code",
    "com.claudecode.webstorm.settings"
) {

    private val settings = ClaudeSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {

        group("CLI") {
            row("Executable:") {
                textField()
                    .bindText(settings::claudeExecutable)
                    .comment("Path to the <code>claude</code> binary, or just <code>claude</code> if it's on your PATH")
                    .columns(COLUMNS_LARGE)
            }
            row("Default model:") {
                textField()
                    .bindText(settings::defaultModel)
                    .comment("e.g. <code>claude-opus-4-6</code>. Leave blank to use claude's default.")
                    .columns(COLUMNS_LARGE)
            }
            row("Extra CLI args:") {
                expandableTextField()
                    .bindText(settings::additionalArgs)
                    .comment("Additional flags passed to <code>claude</code> when starting a session.")
                    .columns(COLUMNS_LARGE)
            }
        }

        group("Behaviour") {
            row {
                checkBox("Auto-open Claude panel on project startup")
                    .bindSelected(settings::autoOpenOnStartup)
            }
            row {
                checkBox("Show floating prompt hint on first use")
                    .bindSelected(settings::showFloatingPromptHint)
            }
            row("Default session name:") {
                textField()
                    .bindText(settings::defaultSessionName)
                    .columns(COLUMNS_SHORT)
            }
        }

        group("Keyboard shortcuts") {
            row {
                comment(
                    """
                    <b>Ctrl+Esc</b> (Win/Linux) / <b>⌘Esc</b> (Mac) — Toggle Claude panel<br>
                    <b>Alt+Shift+C</b> — Floating prompt popup<br>
                    <b>Ctrl+Alt+Shift+S</b> — Send selection to Claude<br>
                    <b>Ctrl+Alt+Shift+F</b> — Ask Claude about current file<br>
                    <b>Ctrl+Alt+Shift+N</b> — New Claude session<br>
                    <br>
                    Customise these in <b>Settings → Keymap → Claude Code</b>.
                    """.trimIndent()
                )
            }
        }
    }
}
