package com.claudecode.webstorm

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Main tool window: session sidebar on the left, chat panel on the right.
 * No terminal — all interaction is through the chat UI, which talks
 * to the `claude` CLI via subprocess JSON streaming.
 */
class ClaudeToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : SimpleToolWindowPanel(false, true) {

    private val manager = ClaudeSessionManager.getInstance(project)
    private val sessionListModel = DefaultListModel<ClaudeSession>()
    private val sessionList = JBList(sessionListModel)
    private val chatPanel = ClaudeChatPanel(project)
    private val timeFormat = SimpleDateFormat("HH:mm")

    init {
        val leftPanel = buildLeftPanel()
        val mainSplit = JBSplitter(false, 0.18f).apply {
            firstComponent = leftPanel
            secondComponent = chatPanel
            setHonorComponentsMinimumSize(true)
            dividerWidth = 1
        }

        toolbar = buildToolbar()
        setContent(mainSplit)
        refreshSessionList()

        // Listener for session list changes
        manager.listeners.add(object : ChatListener {
            override fun onMessageAdded(session: ClaudeSession, message: ChatMessage) {}
            override fun onMessageUpdated(session: ClaudeSession, message: ChatMessage) {}
            override fun onProcessingChanged(session: ClaudeSession, processing: Boolean) {}
            override fun onSessionChanged(session: ClaudeSession?) {
                ApplicationManager.getApplication().invokeLater { refreshSessionList() }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Left Sidebar
    // -------------------------------------------------------------------------

    private fun buildLeftPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty()
            background = UIUtil.getPanelBackground()
            minimumSize = Dimension(120, 0)
        }

        val headerButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(InplaceButton("Delete session", AllIcons.General.Remove) {
                val session = sessionList.selectedValue ?: manager.getActiveSession()
                session?.let { deleteSession(it) }
            })
            add(InplaceButton("New session", AllIcons.General.Add) { newSession() })
        }

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 4, 8)
            background = UIUtil.getPanelBackground()
            add(JLabel("Sessions").apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                foreground = UIUtil.getLabelForeground()
            }, BorderLayout.WEST)
            add(headerButtons, BorderLayout.EAST)
        }

        sessionList.apply {
            cellRenderer = SessionCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty()
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val selected = selectedValue ?: return@addListSelectionListener
                    manager.setActiveSession(selected.id)
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) selectedValue?.let { renameSession(it) }
                }
                override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
                override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
            })
            // Delete key removes selected session
            getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke("DELETE"), "deleteSession"
            )
            actionMap.put("deleteSession", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    selectedValue?.let { deleteSession(it) }
                }
            })
        }

        val sessionScroll = JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(sessionScroll, BorderLayout.CENTER)
        return panel
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("New Session", "Start a new Claude session", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = newSession()
            })
            addSeparator()
            add(object : AnAction("Send Current File", "Send active file to Claude", AllIcons.FileTypes.Unknown) {
                override fun actionPerformed(e: AnActionEvent) {
                    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
                    manager.sendFileReference(file.path)
                    toolWindow.show()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = manager.hasActiveSessions()
                }
            })
            addSeparator()
            add(object : AnAction("Clear Chat", "Clear the chat panel", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) = chatPanel.clearChat()
            })
            addSeparator()
            add(object : AnAction("Settings", "Claude Code settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, "Claude Code")
                }
            })
        }

        return ActionManager.getInstance()
            .createActionToolbar("ClaudeCode.ToolWindow", group, true)
            .apply { targetComponent = this@ClaudeToolWindowPanel }
            .component
    }

    // -------------------------------------------------------------------------
    // Session Actions
    // -------------------------------------------------------------------------

    private fun newSession() {
        val name = Messages.showInputDialog(
            project, "Session name:", "New Claude Session",
            null, "Session ${sessionListModel.size() + 1}", null
        ) ?: return
        manager.createSession(name.ifBlank { "Session ${sessionListModel.size() + 1}" })
    }

    private fun renameSession(session: ClaudeSession) {
        val name = Messages.showInputDialog(
            project, "Rename session:", "Rename",
            null, session.name, null
        ) ?: return
        session.name = name
        refreshSessionList()
    }

    private fun deleteSession(session: ClaudeSession) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete session \"${session.name}\"?",
            "Delete Session",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return
        manager.removeSession(session.id)
        // Force immediate refresh in case the listener's invokeLater hasn't run yet
        refreshSessionList()
    }

    private fun showContextMenu(e: MouseEvent) {
        val idx = sessionList.locationToIndex(e.point)
        if (idx < 0) return
        sessionList.selectedIndex = idx
        val session = sessionListModel.getElementAt(idx)
        JPopupMenu().apply {
            add(JMenuItem("Rename").apply { addActionListener { renameSession(session) } })
            add(JMenuItem("Delete").apply { addActionListener { deleteSession(session) } })
            show(e.component, e.x, e.y)
        }
    }

    private fun refreshSessionList() {
        val sessions = manager.getSessions()
        sessionListModel.clear()
        sessions.forEach { sessionListModel.addElement(it) }
        val activeId = manager.getActiveSession()?.id
        val activeIdx = sessions.indexOfFirst { it.id == activeId }
        if (activeIdx >= 0) sessionList.selectedIndex = activeIdx
    }

    // -------------------------------------------------------------------------
    // Cell Renderer
    // -------------------------------------------------------------------------

    private inner class SessionCellRenderer : JPanel(BorderLayout()), ListCellRenderer<ClaudeSession> {
        private val nameLabel = JBLabel()
        private val timeLabel = JBLabel()
        private val indicator = JPanel().apply { preferredSize = Dimension(4, 0) }

        init {
            border = JBUI.Borders.empty(5, 8)
            isOpaque = true
            add(indicator, BorderLayout.WEST)
            add(nameLabel.apply { border = EmptyBorder(0, 6, 0, 0) }, BorderLayout.CENTER)
            add(timeLabel.apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
            }, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out ClaudeSession>, value: ClaudeSession,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val isActive = value.id == manager.getActiveSession()?.id
            background = when {
                isSelected -> list.selectionBackground
                isActive -> JBUI.CurrentTheme.StatusBar.hoverBackground()
                else -> list.background
            }
            nameLabel.text = value.name
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            timeLabel.text = timeFormat.format(Date(value.createdAt))
            indicator.background = if (isActive) JBColor(0x4CAF50, 0x4CAF50) else background
            return this
        }
    }
}
