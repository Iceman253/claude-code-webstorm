package com.claudecode.webstorm

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.claudecode.webstorm.settings.ClaudeSettings

/**
 * Runs once after the project is fully loaded.
 * - Optionally auto-opens the Claude panel.
 * - Checks that the `claude` executable is on PATH and notifies if not.
 */
class ClaudeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = ClaudeSettings.getInstance()

        // Check claude is available
        if (!isClaudeAvailable(settings.claudeExecutable)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Code")
                .createNotification(
                    "Claude Code",
                    "Could not find <b>${settings.claudeExecutable}</b> on PATH. " +
                        "Install the Claude Code CLI or update the path in " +
                        "<a href=\"settings\">Settings → Tools → Claude Code</a>.",
                    NotificationType.WARNING
                )
                .setListener { notification, event ->
                    if (event.description == "settings") {
                        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, "Claude Code")
                        notification.expire()
                    }
                }
                .notify(project)
            return
        }

        // Optionally auto-open the tool window
        if (settings.autoOpenOnStartup) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow("Claude Code")?.show()
            }
        }
    }

    private fun isClaudeAvailable(executable: String): Boolean {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val cmd = if (isWindows) arrayOf("where", executable) else arrayOf("which", executable)
            val proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
