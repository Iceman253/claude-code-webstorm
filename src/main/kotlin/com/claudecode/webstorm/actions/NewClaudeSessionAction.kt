package com.claudecode.webstorm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.claudecode.webstorm.ClaudeSessionManager

class NewClaudeSessionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = ClaudeSessionManager.getInstance(project)

        // Use the directory of the currently open file as default working dir
        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val defaultDir = currentFile?.parent?.path ?: project.basePath ?: ""

        val sessionCount = manager.getSessions().size + 1
        val name = Messages.showInputDialog(
            project,
            "Session name:",
            "New Claude Session",
            null,
            "Session $sessionCount",
            null
        ) ?: return

        manager.createSession(name.ifBlank { "Session $sessionCount" }, defaultDir)

        // Show the tool window
        ToolWindowManager.getInstance(project).getToolWindow("Claude Code")?.show()
    }
}
