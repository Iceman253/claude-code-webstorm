package com.claudecode.webstorm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.claudecode.webstorm.ClaudeSessionManager
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Shows a floating popup with a multi-line text area for composing a
 * prompt to send to the active Claude session.
 *
 * Unique to this plugin: anchored near the caret position in the editor,
 * not in the tool window. Press Enter to send, Shift+Enter for newline.
 */
class FloatingPromptAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val manager = ClaudeSessionManager.getInstance(project)

        val textArea = JBTextArea(4, 50).apply {
            emptyText.text = "Ask Claude… (Enter to send, Shift+Enter for newline)"
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
        }

        val sendButton = JButton("Send to Claude").apply {
            addActionListener {
                sendPrompt(project, manager, textArea.text.trim())
            }
        }

        val panel = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(460, 140)
            add(textArea, BorderLayout.CENTER)
            add(sendButton, BorderLayout.SOUTH)
        }

        // Override Enter key to send, Shift+Enter to insert newline
        val inputMap = textArea.inputMap
        val actionMap = textArea.actionMap
        inputMap.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "send")
        inputMap.put(javax.swing.KeyStroke.getKeyStroke("shift ENTER"), "newline")
        actionMap.put("send", object : javax.swing.AbstractAction() {
            override fun actionPerformed(ev: java.awt.event.ActionEvent) {
                sendPrompt(project, manager, textArea.text.trim())
            }
        })
        actionMap.put("newline", object : javax.swing.AbstractAction() {
            override fun actionPerformed(ev: java.awt.event.ActionEvent) {
                val pos = textArea.caretPosition
                textArea.document.insertString(pos, "\n", null)
            }
        })

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textArea)
            .setTitle("Quick Claude Prompt")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .createPopup()

        // Store popup reference so sendPrompt can close it
        textArea.putClientProperty("popup", popup)

        if (editor != null) {
            popup.showInBestPositionFor(editor)
        } else {
            popup.showCenteredInCurrentWindow(project)
        }
    }

    private fun sendPrompt(
        project: com.intellij.openapi.project.Project,
        manager: ClaudeSessionManager,
        text: String
    ) {
        if (text.isBlank()) return

        if (!manager.hasActiveSessions()) {
            manager.createSession("Session 1")
        }

        manager.sendMessage(text)
        ToolWindowManager.getInstance(project).getToolWindow("Claude Code")?.show()
    }
}
