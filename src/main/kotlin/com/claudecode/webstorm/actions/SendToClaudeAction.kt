package com.claudecode.webstorm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.claudecode.webstorm.ClaudeSessionManager

/**
 * Sends the currently selected text (or full file if nothing is selected)
 * to the active Claude session, with an optional prompt prefix.
 *
 * Differs from VS Code: shows a prompt dialog asking what to do with
 * the snippet before sending, so the user can add context.
 */
class SendToClaudeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val manager = ClaudeSessionManager.getInstance(project)

        // Ensure there is an active session; create one if not
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

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        val filePath = file?.path ?: ""

        if (selectedText.isNullOrBlank()) {
            // Nothing selected — send file reference instead
            if (filePath.isNotBlank()) {
                manager.sendFileReference(filePath)
                showToolWindow(project)
            }
            return
        }

        // Ask the user for an optional prompt to prefix the snippet
        val prompt = Messages.showInputDialog(
            project,
            "What should Claude do with this code? (leave blank to just send it)",
            "Send to Claude",
            null,
            "",
            null
        ) ?: return

        manager.sendCodeSnippet(selectedText, filePath, prompt.ifBlank { null })
        showToolWindow(project)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor != null && e.project != null
    }

    private fun showToolWindow(project: com.intellij.openapi.project.Project) {
        ToolWindowManager.getInstance(project).getToolWindow("Claude Code")?.show()
    }
}
