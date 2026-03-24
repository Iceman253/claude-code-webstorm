package com.claudecode.webstorm

import com.claudecode.webstorm.settings.ClaudeSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
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
import javax.swing.text.DefaultCaret

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
 * Chat panel with plain-text input, rendered responses, model selector,
 * slash-command autocomplete, and smart auto-scrolling.
 *
 * Scroll architecture — two layers of defense:
 *
 *   Layer 1: Block Swing's internal auto-scroll triggers
 *     - NoScrollTextPane: overrides scrollRectToVisible → no-op (blocks caret scroll)
 *     - MessageBubble: overrides scrollRectToVisible → no-op (blocks component-tree propagation)
 *     - ChatScrollablePanel: overrides scrollRectToVisible → no-op (blocks panel-level propagation)
 *     - DefaultCaret.NEVER_UPDATE: re-applied after every setContentType (belt-and-suspenders)
 *
 *   Layer 2: Smart scroll-to-bottom via ScrollController
 *     - Tracks autoFollow state via AdjustmentListener on the scrollbar
 *     - Distinguishes user scrolls from programmatic scrolls with isProgrammatic flag
 *     - Coalesces rapid scroll requests with a 50ms debounce timer
 *     - Calls validate() before reading sb.maximum to avoid stale-value bugs
 *     - Uses plain JScrollPane (not JBScrollPane) to avoid JBViewport anchoring quirks
 */
class ClaudeChatPanel(private val project: Project) : JPanel(BorderLayout()), ChatListener {

    private val manager = ClaudeSessionManager.getInstance(project)
    private val messagesPanel = ChatScrollablePanel()
    private val scrollPane: JScrollPane
    private val inputArea = JBTextArea(3, 0)
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop")
    private val statusLabel = JBLabel(" ")
    private val modelCombo = JComboBox<String>()
    private val timeFormat = SimpleDateFormat("HH:mm")
    private val bubbleMap = mutableMapOf<ChatMessage, MessageBubble>()
    private var slashPopupVisible = false
    private val scroll = ScrollController()

    companion object {
        private val KNOWN_MODELS = arrayOf(
            "",
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001",
            "claude-sonnet-4-5-20250514",
        )
    }

    init {
        background = UIUtil.getPanelBackground()

        messagesPanel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getEditorPaneBackground()
            border = JBUI.Borders.empty(8)
        }

        // Plain JScrollPane — no JBScrollPane (avoids JBViewport anchoring behavior).
        // All auto-scroll prevention is handled by scrollRectToVisible no-ops on
        // NoScrollTextPane, MessageBubble, and ChatScrollablePanel.
        // ScrollController handles smart follow-the-stream via the scrollbar API.
        scrollPane = JScrollPane(messagesPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        scroll.attach(scrollPane)
        addWelcomeMessage()
        add(scrollPane, BorderLayout.CENTER)
        add(buildInputPanel(), BorderLayout.SOUTH)
        manager.listeners.add(this)
    }

    // -- Input panel --------------------------------------------------------

    private fun buildInputPanel(): JComponent {
        inputArea.apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
            emptyText.text = "Message Claude... (Enter to send, Shift+Enter for newline)"
            font = JBUI.Fonts.label(15f)
        }
        bindInputKeys()
        bindSlashAutocomplete()

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

        val settings = ClaudeSettings.getInstance()
        modelCombo.apply {
            isEditable = true
            KNOWN_MODELS.forEach { addItem(it) }
            val current = settings.defaultModel
            if (current.isNotBlank() && current !in KNOWN_MODELS) addItem(current)
            selectedItem = current
            toolTipText = "Model (empty = CLI default)"
            preferredSize = Dimension(180, preferredSize.height)
            maximumSize = Dimension(200, preferredSize.height)
            addActionListener {
                settings.defaultModel = (selectedItem as? String)?.trim() ?: ""
            }
        }

        val modelLabel = JBLabel("Model:").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 4, 0, 2)
        }

        val buttonsRow = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(stopButton)
            add(sendButton)
        }
        val modelRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(modelLabel)
            add(modelCombo)
        }
        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 4)
            add(Box.createVerticalGlue())
            add(buttonsRow)
            add(Box.createVerticalStrut(4))
            add(modelRow)
            add(Box.createVerticalGlue())
        }

        val inputScroll = JScrollPane(inputArea).apply {
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

    private fun bindInputKeys() {
        val im = inputArea.getInputMap(JComponent.WHEN_FOCUSED)
        val am = inputArea.actionMap
        im.put(KeyStroke.getKeyStroke("ENTER"), "send")
        im.put(KeyStroke.getKeyStroke("shift ENTER"), "newline")
        am.put("send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (!slashPopupVisible) doSend()
            }
        })
        am.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                inputArea.insert("\n", inputArea.caretPosition)
            }
        })
    }

    private fun bindSlashAutocomplete() {
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = checkSlash()
            override fun removeUpdate(e: DocumentEvent) { slashPopupVisible = false }
            override fun changedUpdate(e: DocumentEvent) {}
        })
    }

    // -- Slash popup --------------------------------------------------------

    private fun checkSlash() {
        SwingUtilities.invokeLater {
            val text = inputArea.text
            if (text.startsWith("/")) showSlashPopup(text) else slashPopupVisible = false
        }
    }

    private fun showSlashPopup(typed: String) {
        val matches = SLASH_COMMANDS.filter { it.command.lowercase().startsWith(typed.lowercase()) }
        if (matches.isEmpty()) { slashPopupVisible = false; return }
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
            override fun canceled() { slashPopupVisible = false }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val pt = Point(0, -popup.content.preferredSize.height)
        SwingUtilities.convertPointToScreen(pt, inputArea)
        popup.showInScreenCoordinates(inputArea, pt)
    }

    // -- Send / session -----------------------------------------------------

    private fun doSend() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        ensureSession()
        inputArea.text = ""
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

    // -- Welcome banner -----------------------------------------------------

    private fun addWelcomeMessage() {
        messagesPanel.add(JPanel().apply {
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
        })
    }

    // -- ChatListener -------------------------------------------------------

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
        // New message added — always scroll to bottom (user just sent, or assistant started)
        scroll.forceToBottom()
    }

    override fun onMessageUpdated(session: ClaudeSession, message: ChatMessage) {
        if (session.id != manager.getActiveSession()?.id) return
        bubbleMap[message]?.update(message)
        // During streaming, only scroll if user hasn't scrolled away
        if (message.isStreaming) {
            scroll.followIfAtBottom()
        }
    }

    override fun onSessionChanged(session: ClaudeSession?) {
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
        scroll.forceToBottom()
    }

    override fun onProcessingChanged(session: ClaudeSession, processing: Boolean) {
        sendButton.isEnabled = !processing
        stopButton.isVisible = processing
        statusLabel.text = if (processing) "Claude is thinking..." else " "
    }

    // =====================================================================
    // ScrollController — owns ALL scroll-to-bottom decisions
    // =====================================================================

    /**
     * Centralised scroll manager that:
     * - Tracks whether the user wants auto-follow (are they at the bottom?)
     * - Distinguishes user scrolls from programmatic scrolls via a guard flag
     * - Coalesces rapid scroll requests with a debounce timer (avoids layout thrash)
     * - Calls validate() before reading scrollbar.maximum (avoids stale-value bugs)
     */
    private inner class ScrollController {
        /** True = user is following the stream (at/near bottom). False = user scrolled up. */
        var autoFollow = true
            private set

        /** Set while we are programmatically moving the scrollbar. */
        private var isProgrammatic = false

        /** Debounce timer — coalesces rapid scroll-to-bottom requests. */
        private var debounceTimer: Timer? = null

        /** Pixels from the bottom within which we consider "at bottom". */
        private val threshold = 80

        /** Debounce interval in ms — limits scroll operations during fast streaming. */
        private val debounceMs = 50

        fun attach(sp: JScrollPane) {
            // Listen for ALL scrollbar value changes.
            // When the change was NOT caused by us (isProgrammatic=false), it must be
            // the user scrolling — update autoFollow accordingly.
            sp.verticalScrollBar.addAdjustmentListener {
                if (!isProgrammatic) {
                    autoFollow = isAtBottom(sp)
                }
            }
        }

        /** User initiated action (sent message, switched session) — force to bottom. */
        fun forceToBottom() {
            autoFollow = true
            scheduleScroll()
        }

        /** Streaming update — only scroll if user is still following. */
        fun followIfAtBottom() {
            if (autoFollow) scheduleScroll()
        }

        private fun isAtBottom(sp: JScrollPane): Boolean {
            val sb = sp.verticalScrollBar
            return sb.value + sb.visibleAmount >= sb.maximum - threshold
        }

        /**
         * Schedule a debounced scroll-to-bottom.
         *
         * Why debounce?  During streaming, onMessageUpdated fires on every NDJSON
         * line (could be 50+ times/sec).  Each would trigger validate()+scroll.
         * Debouncing to [debounceMs] means at most ~20 scrolls/sec — smooth to the
         * eye, light on layout.
         *
         * Why validate()?  revalidate() is deferred — calling sb.maximum before
         * layout runs gives a stale value.  validate() forces pending layout
         * synchronously so maximum reflects the true content height.
         */
        private fun scheduleScroll() {
            debounceTimer?.stop()
            debounceTimer = Timer(debounceMs) {
                debounceTimer = null
                performScroll()
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun performScroll() {
            // Force any pending layout so sb.maximum is accurate
            scrollPane.validate()

            isProgrammatic = true
            try {
                val sb = scrollPane.verticalScrollBar
                sb.value = sb.maximum - sb.visibleAmount
            } finally {
                // Clear the flag on the NEXT EDT event so our own AdjustmentEvent
                // (which fires synchronously inside sb.value=) is already past.
                SwingUtilities.invokeLater { isProgrammatic = false }
            }
        }
    }
}

// ===========================================================================
// MessageBubble
// ===========================================================================

class MessageBubble(
    private var message: ChatMessage,
    private val timeFormat: SimpleDateFormat
) : JPanel() {

    private val contentPane = NoScrollTextPane()
    private val metaLabel = JBLabel()
    private val costLabel = JBLabel()
    private val inner: RoundedPanel

    private var targetContent = ""
    private var displayedLength = 0
    private var typingTimer: Timer? = null
    private var streamingActive = false
    private var lastLayoutTime = 0L

    companion object {
        private val USER_BG = JBColor(Color(0xE3F2FD), Color(0x2B3D50))
        private val ASSISTANT_BG = JBColor(Color(0xFFF3E0), Color(0x3D3020))
        private val SYSTEM_BG = JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
        private const val MAX_WIDTH_FRACTION = 0.78
        private const val ARC = 16
        private const val TYPING_INTERVAL_MS = 10
        private const val CHARS_PER_TICK = 2
        private const val CURSOR_CHAR = "\u2588"
        private const val LAYOUT_THROTTLE_MS = 150L
    }

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        alignmentX = Component.LEFT_ALIGNMENT

        val isUser = message.role == "user"
        val bg = roleBg(message.role)

        inner = RoundedPanel(bg, ARC).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 12)
        }

        metaLabel.apply {
            text = "${roleLabel(message.role)}  ${timeFormat.format(Date(message.timestamp))}"
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = roleFg(message.role)
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        costLabel.apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }

        val header = JPanel(BorderLayout()).apply {
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
            lockCaret()
        }

        inner.add(header, BorderLayout.NORTH)
        inner.add(contentPane, BorderLayout.CENTER)

        if (isUser) { add(inner); add(Box.createHorizontalGlue()) }
        else        { add(Box.createHorizontalGlue()); add(inner) }

        renderFinal()
    }

    // Block scrollRectToVisible from propagating up to the viewport (Mechanism 2)
    override fun scrollRectToVisible(aRect: Rectangle?) { /* no-op */ }

    override fun getMaximumSize(): Dimension {
        val w = parent?.width ?: Short.MAX_VALUE.toInt()
        return Dimension(w, preferredSize.height)
    }

    override fun getPreferredSize(): Dimension {
        val parentW = parent?.width ?: Short.MAX_VALUE.toInt()
        inner.maximumSize = Dimension((parentW * MAX_WIDTH_FRACTION).toInt(), Short.MAX_VALUE.toInt())
        val pref = super.getPreferredSize()
        return Dimension(parentW, pref.height)
    }

    fun update(msg: ChatMessage) {
        this.message = msg
        if (msg.role != "assistant") { renderFinal(); return }

        streamingActive = msg.isStreaming
        targetContent = msg.content

        if (msg.isStreaming) {
            if (typingTimer == null) startTypingAnimation()
        } else {
            typingTimer?.stop()
            typingTimer = null
            displayedLength = targetContent.length
            renderFinal()
        }
        msg.costUsd?.let { costLabel.text = String.format("$%.4f", it) }
    }

    // -- Typing animation ---------------------------------------------------

    private fun startTypingAnimation() {
        setContentTypeSafe("text/plain")
        contentPane.font = JBUI.Fonts.label(14f)
        contentPane.text = CURSOR_CHAR
        displayedLength = 0

        typingTimer = Timer(TYPING_INTERVAL_MS) {
            if (displayedLength < targetContent.length) {
                val end = (displayedLength + CHARS_PER_TICK).coerceAtMost(targetContent.length)
                val chunk = targetContent.substring(displayedLength, end)
                contentPane.document.insertString(contentPane.document.length - 1, chunk, null)
                displayedLength = end
                throttledLayout()
            } else if (!streamingActive) {
                typingTimer?.stop()
                typingTimer = null
                renderFinal()
            }
        }.apply { start() }
    }

    private fun throttledLayout() {
        val now = System.currentTimeMillis()
        if (now - lastLayoutTime > LAYOUT_THROTTLE_MS) {
            lastLayoutTime = now
            revalidate()
            repaint()
        }
    }

    // -- Final render -------------------------------------------------------

    private fun renderFinal() {
        if (message.role == "user") {
            setContentTypeSafe("text/plain")
            contentPane.font = JBUI.Fonts.label(15f)
            contentPane.text = message.content
        } else {
            setContentTypeSafe("text/html")
            contentPane.text = buildHtmlBody(markdownToHtml(message.content))
        }
        message.costUsd?.let { costLabel.text = String.format("$%.4f", it) }
        revalidate()
        repaint()
    }

    // -- Caret safety -------------------------------------------------------

    private fun setContentTypeSafe(type: String) {
        contentPane.contentType = type
        lockCaret()
    }

    private fun lockCaret() {
        (contentPane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
    }

    // -- Markdown / HTML ----------------------------------------------------

    private fun buildHtmlBody(innerHtml: String): String {
        val family = JBUI.Fonts.label().family
        val fg = String.format("#%06x", UIUtil.getLabelForeground().rgb and 0xFFFFFF)
        return """<html><body style="font-family: $family; font-size: 14px; color: $fg; margin: 0; padding: 0;">$innerHtml</body></html>"""
    }

    private fun markdownToHtml(md: String): String {
        val codeBg = String.format("#%06x", JBColor(Color(0xF5F5F5), Color(0x2D2D2D)).rgb and 0xFFFFFF)
        val sb = StringBuilder()
        var inCode = false
        for (line in md.split("\n")) {
            if (line.trimStart().startsWith("```")) {
                sb.append(if (!inCode) "<pre style=\"background:$codeBg;padding:8px;font-family:monospace;font-size:12px;white-space:pre-wrap;\"><code style=\"font-family:monospace;font-size:12px;\">"
                          else "</code></pre>")
                inCode = !inCode
                continue
            }
            if (inCode) sb.append(escHtml(line)).append("\n")
            else if (line.isNotBlank()) sb.append("<p style=\"margin:4px 0;\">").append(inlineMarkdown(escHtml(line), codeBg)).append("</p>")
        }
        if (inCode) sb.append("</code></pre>")
        return sb.toString()
    }

    private fun inlineMarkdown(text: String, codeBg: String): String =
        text.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
            .replace(Regex("`([^`]+)`"), "<code style=\"font-family:monospace;font-size:12px;background:$codeBg;padding:1px 4px;\">$1</code>")
            .replace(Regex("\\[Using tool: (.+?)]"), "<i style='color:gray;'>[Using: $1]</i>")

    private fun escHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // -- Role helpers -------------------------------------------------------

    private fun roleBg(role: String) = when (role) {
        "user"      -> USER_BG
        "assistant" -> ASSISTANT_BG
        else        -> SYSTEM_BG
    }

    private fun roleLabel(role: String) = when (role) {
        "user"      -> "You"
        "assistant" -> "Claude"
        else        -> role.replaceFirstChar { it.uppercase() }
    }

    private fun roleFg(role: String) = when (role) {
        "user"      -> JBColor(0x1565C0, 0x90CAF9)
        "assistant" -> JBColor(0xD97757, 0xE8956A)
        else        -> UIUtil.getLabelDisabledForeground()
    }
}

// ===========================================================================
// Scroll-safe component hierarchy
// ===========================================================================

/**
 * JTextPane that blocks scrollRectToVisible (Mechanism 1).
 *
 * When DefaultCaret processes a document change it calls scrollRectToVisible()
 * which propagates up through every parent until it hits a JViewport.
 * Overriding to no-op here stops it at the source.
 */
private class NoScrollTextPane : JTextPane() {
    override fun scrollRectToVisible(aRect: Rectangle?) { /* no-op */ }
}

/** Rounded-corner panel with solid fill. */
private class RoundedPanel(private val bg: Color, private val arc: Int) : JPanel() {
    init { isOpaque = false }
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
 * Scrollable panel that enables correct word-wrap in child JTextPanes.
 * Blocks scrollRectToVisible to prevent children from moving the viewport (Mechanism 2).
 */
private class ChatScrollablePanel : JPanel(), Scrollable {
    override fun scrollRectToVisible(aRect: Rectangle?) { /* no-op */ }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 16
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
