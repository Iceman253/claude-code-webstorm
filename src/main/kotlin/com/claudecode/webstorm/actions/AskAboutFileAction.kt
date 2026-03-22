package com.claudecode.webstorm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.claudecode.webstorm.ClaudeSessionManager

/**
 * Sends the current file as an @-reference to the active Claude session.
 * If triggered from the Project view, uses the selected file.
 * Optionally asks the user for a question/prompt about the file.
 */
class AskAboutFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = ClaudeSessionManager.getInstance(project)

        // Resolve file from editor or project tree selection
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.PSI_FILE)?.virtualFile
            ?: return

        if (!manager.hasActiveSessions()) {
            val create = Messages.showYesNoDialog(
                project,
                "No active Claude session. Start one now?",
                "Claude Code",
                Messages.getQuestionIcon()
            )
            if (create != Messages.YES) return
            manager.createSession("Session 1")
        }

        val prompt = Messages.showInputDialog(
            project,
            "What do you want to ask Claude about ${file.name}? (leave blank to just reference it)",
            "Ask Claude",
            null,
            "",
            null
        ) ?: return

        manager.sendFileReference(file.path, prompt.ifBlank { null })
        ToolWindowManager.getInstance(project).getToolWindow("Claude Code")?.show()
    }

    override fun update(e: AnActionEvent) {
        val hasFile = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
            || e.getData(CommonDataKeys.PSI_FILE) != null
        e.presentation.isEnabled = hasFile && e.project != null
    }
}
