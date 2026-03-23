package com.claudecode.webstorm

import com.claudecode.webstorm.settings.ClaudeSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// All slash commands available in Claude Code CLI
private val SLASH_COMMANDS = listOf(
    SlashCmd("/add-dir", "Add a directory to context"),
    SlashCmd("/batch", "Batch mode for multiple prompts"),
    SlashCmd("/bug", "Report a bug"),
    SlashCmd("/clear", "Clear conversation history"),
    SlashCmd("/compact", "Compact conversation to save context"),
    SlashCmd("/config", "View or modify configuration"),
    SlashCmd("/context", "Show current context"),
    SlashCmd("/cost", "Show token usage and cost"),
    SlashCmd("/debug", "Toggle debug mode"),
    SlashCmd("/doctor", "Run diagnostics"),
    SlashCmd("/extra-usage", "Show extra usage info"),
    SlashCmd("/help", "Show available commands"),
    SlashCmd("/init", "Initialize project CLAUDE.md"),
    SlashCmd("/insights", "Show conversation insights"),
    SlashCmd("/login", "Log in to your account"),
    SlashCmd("/logout", "Log out of your account"),
    SlashCmd("/loop", "Run a prompt on a recurring interval"),
    SlashCmd("/memory", "View or edit Claude's memory"),
    SlashCmd("/model", "Switch the active model"),
    SlashCmd("/permissions", "View or modify permissions"),
    SlashCmd("/review", "Review code changes"),
    SlashCmd("/status", "Show session status"),
    SlashCmd("/terminal-setup", "Configure terminal integration"),
    SlashCmd("/vim", "Toggle vim keybindings"),
)

private data class SlashCmd(val command: String, val description: String) {
    override fun toString(): String = "$command  —  $description"
}

/**
 * Chat panel: plain text input, markdown-rendered responses from Claude,
 * and a `/` autocomplete popup for slash commands.
 */
class ClaudeChatPanel(private val project: Project) : JPanel(BorderLayout()), ChatListener {

    private val manager = ClaudeSessionManager.getInstance(project)
    private val messagesPanel = ScrollablePanel()
    private val freezableViewport = FreezableViewport()
    private val scrollPane: JBScrollPane
    private val inputArea = JBTextArea(3, 0)
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop")
    private val statusLabel = JBLabel(" ")
    private val modelCombo = JComboBox<String>()
    private val timeFormat = SimpleDateFormat("HH:mm")
    private val bubbleMap = mutableMapOf<ChatMessage, MessageBubble>()
    private var scrollToBottomOnNextUpdate = false

    companion object {
        private val KNOWN_MODELS = arrayOf(
            "", // empty = default / whatever CLI uses
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001",
            "claude-sonnet-4-5-20250514",
        )
    }

    init {
        background = UIUtil.getPanelBackground()

        messagesPanel.layout = BoxLayout(messagesPanel, BoxLayout.Y_AXIS)
        messagesPanel.background = UIUtil.getEditorPaneBackground()
        messagesPanel.border = JBUI.Borders.empty(8)

        // MECHANISM 3 FIX: Use our FreezableViewport instead of JBScrollPane's default.
        // This lets us freeze the viewport position during streaming so that
        // ViewportLayout.layoutContainer() can't auto-scroll when the view grows.
        freezableViewport.view = messagesPanel
        scrollPane = JBScrollPane().apply {
            setViewport(freezableViewport)
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        addWelcomeMessage()

        add(scrollPane, BorderLayout.CENTER)
        add(buildBottomPanel(), BorderLayout.SOUTH)

        manager.listeners.add(this)
    }

    // -------------------------------------------------------------------------
    // Bottom panel: input + status
    // -------------------------------------------------------------------------

    private fun buildBottomPanel(): JComponent {
        inputArea.apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
            emptyText.text = "Message Claude... (Enter to send, Shift+Enter for newline)"
            font = JBUI.Fonts.label(15f)
        }

        // Enter to send, Shift+Enter for newline
        val im = inputArea.getInputMap(JComponent.WHEN_FOCUSED)
        val am = inputArea.actionMap
        im.put(KeyStroke.getKeyStroke("ENTER"), "send")
        im.put(KeyStroke.getKeyStroke("shift ENTER"), "newline")
        am.put("send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (slashPopupVisible) return
                doSend()
            }
        })
        am.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                inputArea.insert("\n", inputArea.caretPosition)
            }
        })

        // Slash command autocomplete
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = checkSlash()
            override fun removeUpdate(e: DocumentEvent) = dismissSlashPopup()
            override fun changedUpdate(e: DocumentEvent) {}
        })

        sendButton.apply {
            addActionListener { doSend() }
            icon = AllIcons.Actions.Execute
            toolTipText = "Send message (Enter)"
        }

        stopButton.apply {
            addActionListener { manager.cancelCurrentRequest() }
            icon = AllIcons.Actions.Suspend
            toolTipText = "Cancel current request"
            isVisible = false
        }

        // Model selector combo box
        val settings = ClaudeSettings.getInstance()
        modelCombo.apply {
            isEditable = true
            for (m in KNOWN_MODELS) addItem(m)
            val current = settings.defaultModel
            if (current.isNotBlank() && current !in KNOWN_MODELS) {
                addItem(current)
            }
            selectedItem = current
            toolTipText = "Model (empty = CLI default)"
            preferredSize = Dimension(180, preferredSize.height)
            maximumSize = Dimension(200, preferredSize.height)
            addActionListener {
                val model = (selectedItem as? String)?.trim() ?: ""
                settings.defaultModel = model
            }
        }

        val modelLabel = JBLabel("Model:").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 4, 0, 2)
        }

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 4)
            add(Box.createVerticalGlue())
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(stopButton)
                add(sendButton)
            })
            add(Box.createVerticalStrut(4))
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(modelLabel)
                add(modelCombo)
            })
            add(Box.createVerticalGlue())
        }

        val inputScroll = JBScrollPane(inputArea).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(4, 8)
            )
            preferredSize = Dimension(0, 90)
        }

        statusLabel.apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(2, 8)
        }

        return JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(inputScroll, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    // -------------------------------------------------------------------------
    // Slash command autocomplete popup
    // -------------------------------------------------------------------------

    private var slashPopupVisible = false

    private fun checkSlash() {
        SwingUtilities.invokeLater {
            val text = inputArea.text
            if (text.startsWith("/")) {
                showSlashPopup(text)
            } else {
                dismissSlashPopup()
            }
        }
    }

    private fun dismissSlashPopup() {
        slashPopupVisible = false
    }

    private fun showSlashPopup(typed: String) {
        val filter = typed.lowercase()
        val matches = SLASH_COMMANDS.filter { it.command.lowercase().startsWith(filter) }
        if (matches.isEmpty()) {
            dismissSlashPopup()
            return
        }

        slashPopupVisible = true

        val step = object : BaseListPopupStep<SlashCmd>("", matches) {
            override fun onChosen(selectedValue: SlashCmd, finalChoice: Boolean): PopupStep<*>? {
                SwingUtilities.invokeLater {
                    inputArea.text = ""
                    ensureSession()
                    manager.sendSlashCommand(selectedValue.command)
                }
                slashPopupVisible = false
                return PopupStep.FINAL_CHOICE
            }

            override fun getTextFor(value: SlashCmd): String = value.toString()

            override fun canceled() {
                slashPopupVisible = false
            }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val point = Point(0, -popup.content.preferredSize.height)
        SwingUtilities.convertPointToScreen(point, inputArea)
        popup.showInScreenCoordinates(inputArea, Point(point.x, point.y))
    }

    // -------------------------------------------------------------------------
    // Send / session
    // -------------------------------------------------------------------------

    private fun doSend() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        ensureSession()
        inputArea.text = ""
        scrollToBottomOnNextUpdate = true
        manager.sendMessage(text)
    }

    private fun ensureSession() {
        if (!manager.hasActiveSessions()) {
            manager.createSession("Session 1", project.basePath)
        }
    }

    fun clearChat() {
        bubbleMap.clear()
        messagesPanel.removeAll()
        addWelcomeMessage()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    // -------------------------------------------------------------------------
    // Welcome
    // -------------------------------------------------------------------------

    private fun addWelcomeMessage() {
        val welcome = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(20, 12)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("Claude Code").apply {
                font = JBUI.Fonts.label(18f).deriveFont(Font.BOLD)
                foreground = JBColor(0xD97757, 0xE8956A)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(JBLabel("Type a message or press / for commands.").apply {
                font = JBUI.Fonts.label(13f)
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        messagesPanel.add(welcome)
    }

    // -------------------------------------------------------------------------
    // ChatListener
    // -------------------------------------------------------------------------

    override fun onMessageAdded(session: ClaudeSession, message: ChatMessage) {
        if (session.id != manager.getActiveSession()?.id) return
        if (session.messages.size <= 2 && messagesPanel.componentCount > 0) {
            val first = messagesPanel.getComponent(0)
            if (first !is MessageBubble) messagesPanel.remove(first)
        }
        val bubble = MessageBubble(message, timeFormat)
        bubbleMap[message] = bubble
        messagesPanel.add(bubble)
        messagesPanel.revalidate()
        // Only scroll to bottom when user just sent a message
        if (scrollToBottomOnNextUpdate) {
            scrollToBottomOnNextUpdate = false
            forceScrollToBottom()
        }
    }

    override fun onMessageUpdated(session: ClaudeSession, message: ChatMessage) {
        if (session.id != manager.getActiveSession()?.id) return
        // MECHANISM 3: Freeze the viewport so layout can't change scroll position.
        // We freeze here (not in onProcessingChanged) so that the initial
        // forceScrollToBottom from doSend has already executed.
        if (!freezableViewport.frozen && message.isStreaming) {
            freezableViewport.freeze()
        }
        bubbleMap[message]?.update(message)
    }

    override fun onSessionChanged(session: ClaudeSession?) {
        freezableViewport.unfreeze()  // Allow scroll position changes when switching sessions
        bubbleMap.clear()
        messagesPanel.removeAll()
        if (session == null || session.messages.isEmpty()) {
            addWelcomeMessage()
        } else {
            for (msg in session.messages) {
                val bubble = MessageBubble(msg, timeFormat)
                bubbleMap[msg] = bubble
                messagesPanel.add(bubble)
            }
        }
        messagesPanel.revalidate()
        messagesPanel.repaint()
        forceScrollToBottom()
    }

    override fun onProcessingChanged(session: ClaudeSession, processing: Boolean) {
        sendButton.isEnabled = !processing
        stopButton.isVisible = processing
        statusLabel.text = if (processing) "Claude is thinking..." else " "

        // MECHANISM 3: Freeze viewport during generation so ViewportLayout
        // can't auto-scroll. Unfreeze when done so user can scroll normally.
        if (processing) {
            // Don't freeze yet — we want the initial scroll-to-bottom from doSend to work.
            // The freeze happens after the first onMessageUpdated (see below).
        } else {
            freezableViewport.unfreeze()
        }
    }

    private fun forceScrollToBottom() {
        SwingUtilities.invokeLater {
            // Temporarily unfreeze so we can actually set the position
            val wasFrozen = freezableViewport.frozen
            if (wasFrozen) freezableViewport.unfreeze()
            val sb = scrollPane.verticalScrollBar
            sb.value = sb.maximum
            // Don't re-freeze here — freeze will happen on first onMessageUpdated
        }
    }
}

/**
 * A single message bubble.
 * - User messages: plain text, left-aligned
 * - Assistant messages: plain text while streaming (smooth typing), HTML when done
 */
class MessageBubble(
    private var message: ChatMessage,
    private val timeFormat: SimpleDateFormat
) : JPanel() {

    // MECHANISM 1 FIX: Override scrollRectToVisible on the JTextPane itself.
    // When caret calls scrollRectToVisible(), it propagates up through every
    // parent until it hits a JViewport. By overriding it to no-op on the
    // JTextPane, the call never reaches the viewport.
    private val contentPane = object : JTextPane() {
        override fun scrollRectToVisible(aRect: Rectangle?) {
            // No-op: block caret-triggered scroll from reaching the outer viewport
        }
    }
    private val metaLabel = JBLabel()
    private val costLabel = JBLabel()
    private val inner: RoundedPanel

    // Typing animation state
    private var targetContent = ""
    private var displayedLength = 0
    private var revealTimer: Timer? = null
    private var isCurrentlyStreaming = false
    private var lastRevalidateTime = 0L

    companion object {
        private val USER_BG = JBColor(Color(0xE3F2FD), Color(0x2B3D50))
        private val ASSISTANT_BG = JBColor(Color(0xFFF3E0), Color(0x3D3020))
        private val SYSTEM_BG = JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
        private const val MAX_WIDTH_FRACTION = 0.78
        private const val ARC = 16
        private const val TYPING_MS = 10         // interval between ticks
        private const val CHARS_PER_TICK = 2     // characters per tick
        private const val CURSOR = "\u2588"      // block cursor █
        private const val REVALIDATE_INTERVAL = 150L // ms between layout passes
    }

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        alignmentX = Component.LEFT_ALIGNMENT

        val isUser = message.role == "user"
        val bg = when (message.role) {
            "user" -> USER_BG
            "assistant" -> ASSISTANT_BG
            else -> SYSTEM_BG
        }

        inner = RoundedPanel(bg, ARC).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 12)
        }

        val roleText = when (message.role) {
            "user" -> "You"
            "assistant" -> "Claude"
            else -> message.role.replaceFirstChar { it.uppercase() }
        }

        metaLabel.apply {
            text = "$roleText  ${timeFormat.format(Date(message.timestamp))}"
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = when (message.role) {
                "user" -> JBColor(0x1565C0, 0x90CAF9)
                "assistant" -> JBColor(0xD97757, 0xE8956A)
                else -> UIUtil.getLabelDisabledForeground()
            }
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }

        costLabel.apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }

        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(metaLabel, BorderLayout.WEST)
            add(costLabel, BorderLayout.EAST)
        }

        contentPane.apply {
            isEditable = false
            background = bg
            border = JBUI.Borders.empty()
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            font = if (isUser) JBUI.Fonts.label(15f) else JBUI.Fonts.label(14f)
            // MECHANISM 1 BELT: Also set caret policy as defense-in-depth
            (caret as? javax.swing.text.DefaultCaret)?.updatePolicy =
                javax.swing.text.DefaultCaret.NEVER_UPDATE
        }

        inner.add(headerPanel, BorderLayout.NORTH)
        inner.add(contentPane, BorderLayout.CENTER)

        if (isUser) {
            add(inner)
            add(Box.createHorizontalGlue())
        } else {
            add(Box.createHorizontalGlue())
            add(inner)
        }

        renderFinal()
    }

    // MECHANISM 2 FIX: Override scrollRectToVisible on the MessageBubble itself.
    // Any scrollRectToVisible call from any child (RoundedPanel, headerPanel, etc.)
    // propagates through here. Block it.
    override fun scrollRectToVisible(aRect: Rectangle?) {
        // No-op: block all scroll propagation from children
    }

    override fun getMaximumSize(): Dimension {
        val w = if (parent != null) parent.width else Short.MAX_VALUE.toInt()
        return Dimension(w, preferredSize.height)
    }

    override fun getPreferredSize(): Dimension {
        val parentW = if (parent != null) parent.width else Short.MAX_VALUE.toInt()
        val maxBubbleW = (parentW * MAX_WIDTH_FRACTION).toInt()
        inner.maximumSize = Dimension(maxBubbleW, Short.MAX_VALUE.toInt())
        val pref = super.getPreferredSize()
        return Dimension(parentW, pref.height)
    }

    /**
     * Re-apply NEVER_UPDATE on the caret. Must be called after every
     * setContentType() because that replaces the EditorKit and can reset the caret.
     */
    private fun reapplyCaretPolicy() {
        (contentPane.caret as? javax.swing.text.DefaultCaret)?.updatePolicy =
            javax.swing.text.DefaultCaret.NEVER_UPDATE
    }

    fun update(msg: ChatMessage) {
        this.message = msg

        if (message.role != "assistant") {
            renderFinal()
            return
        }

        val newContent = message.content
        isCurrentlyStreaming = message.isStreaming

        if (message.isStreaming) {
            targetContent = newContent
            if (revealTimer == null) {
                startTyping()
            }
        } else {
            revealTimer?.stop()
            revealTimer = null
            targetContent = newContent
            displayedLength = newContent.length
            renderFinal()
        }

        message.costUsd?.let {
            costLabel.text = String.format("$%.4f", it)
        }
    }

    private fun startTyping() {
        contentPane.contentType = "text/plain"
        reapplyCaretPolicy()
        contentPane.font = JBUI.Fonts.label(14f)
        contentPane.text = CURSOR
        displayedLength = 0

        revealTimer = Timer(TYPING_MS) {
            if (displayedLength < targetContent.length) {
                val newEnd = (displayedLength + CHARS_PER_TICK).coerceAtMost(targetContent.length)
                val chunk = targetContent.substring(displayedLength, newEnd)
                val doc = contentPane.document
                doc.insertString(doc.length - 1, chunk, null)
                displayedLength = newEnd

                val now = System.currentTimeMillis()
                if (now - lastRevalidateTime > REVALIDATE_INTERVAL) {
                    lastRevalidateTime = now
                    revalidate()
                    repaint()
                }
            } else if (!isCurrentlyStreaming) {
                revealTimer?.stop()
                revealTimer = null
                renderFinal()
            }
        }.apply { start() }
    }

    private fun renderFinal() {
        if (message.role == "user") {
            contentPane.contentType = "text/plain"
            reapplyCaretPolicy()
            contentPane.font = JBUI.Fonts.label(15f)
            contentPane.text = message.content
        } else {
            contentPane.contentType = "text/html"
            reapplyCaretPolicy()
            val html = markdownToHtml(message.content)
            val fontFamily = JBUI.Fonts.label().family
            val fg = UIUtil.getLabelForeground()
            val hexFg = String.format("#%06x", fg.rgb and 0xFFFFFF)
            contentPane.text = """
                <html><body style="font-family: $fontFamily; font-size: 14px; color: $hexFg; margin: 0; padding: 0;">
                $html
                </body></html>
            """.trimIndent()
        }
        message.costUsd?.let {
            costLabel.text = String.format("$%.4f", it)
        }
        revalidate()
        repaint()
    }

    private fun markdownToHtml(md: String): String {
        val codeBg = JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
        val hexCodeBg = String.format("#%06x", codeBg.rgb and 0xFFFFFF)

        val sb = StringBuilder()
        var inCodeBlock = false
        for (line in md.split("\n")) {
            if (line.trimStart().startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    sb.append("<pre style=\"background: $hexCodeBg; padding: 8px; font-family: monospace; font-size: 12px; white-space: pre-wrap;\"><code style=\"font-family: monospace; font-size: 12px;\">")
                } else {
                    inCodeBlock = false
                    sb.append("</code></pre>")
                }
                continue
            }
            if (inCodeBlock) {
                sb.append(escapeHtml(line)).append("\n")
            } else if (line.isNotBlank()) {
                sb.append("<p style=\"margin: 4px 0;\">").append(inlineMarkdown(escapeHtml(line), hexCodeBg)).append("</p>")
            }
        }
        if (inCodeBlock) sb.append("</code></pre>")
        return sb.toString()
    }

    private fun inlineMarkdown(text: String, hexCodeBg: String): String {
        var s = text
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        s = s.replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
        s = s.replace(Regex("`([^`]+)`"), "<code style=\"font-family: monospace; font-size: 12px; background: $hexCodeBg; padding: 1px 4px;\">$1</code>")
        s = s.replace(Regex("\\[Using tool: (.+?)]"), "<i style='color: gray;'>[Using: $1]</i>")
        return s
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * A JPanel with rounded corners and a solid background.
 */
private class RoundedPanel(
    private val bg: Color,
    private val arc: Int
) : JPanel() {

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * A JPanel that implements Scrollable so that JTextPane word-wrap
 * works correctly inside a JScrollPane.
 *
 * MECHANISM 2 FIX: overrides scrollRectToVisible to block propagation
 * from child components (MessageBubble, JTextPane, etc.) to the viewport.
 */
private class ScrollablePanel : JPanel(), Scrollable {
    override fun scrollRectToVisible(aRect: Rectangle?) {
        // No-op: block all scroll propagation from children to viewport
    }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 16
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

/**
 * MECHANISM 3 FIX: A JViewport that can freeze its view position.
 * When frozen, ViewportLayout.layoutContainer() cannot change the scroll position.
 * This blocks the "bottom-justified" layout behavior in ViewportLayout that
 * auto-scrolls when the view grows taller.
 */
private class FreezableViewport : JViewport() {
    var frozen = false
    private var savedPosition: Point? = null

    fun freeze() {
        savedPosition = viewPosition
        frozen = true
    }

    fun unfreeze() {
        frozen = false
        savedPosition = null
    }

    override fun setViewPosition(p: Point) {
        if (frozen) {
            // During freeze, restore saved position instead of accepting layout's position
            savedPosition?.let { super.setViewPosition(it) }
        } else {
            super.setViewPosition(p)
        }
    }
}
