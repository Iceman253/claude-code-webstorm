package com.claudecode.webstorm

import com.claudecode.webstorm.settings.ClaudeSettings
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

data class ChatMessage(
    val role: String, // "user", "assistant", "system", "tool"
    var content: String,
    val timestamp: Long = System.currentTimeMillis(),
    var isStreaming: Boolean = false,
    var costUsd: Double? = null
)

data class ClaudeSession(
    val id: String,
    var name: String,
    val workingDirectory: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var claudeSessionId: String? = null,
    var isProcessing: Boolean = false
)

interface ChatListener {
    fun onMessageAdded(session: ClaudeSession, message: ChatMessage)
    fun onMessageUpdated(session: ClaudeSession, message: ChatMessage)
    fun onSessionChanged(session: ClaudeSession?)
    fun onProcessingChanged(session: ClaudeSession, processing: Boolean)
}

@Service(Service.Level.PROJECT)
class ClaudeSessionManager(private val project: Project) : Disposable {

    private val sessions = CopyOnWriteArrayList<ClaudeSession>()
    private var activeSessionId: String? = null
    private var currentProcess: Process? = null
    val listeners = CopyOnWriteArrayList<ChatListener>()

    companion object {
        fun getInstance(project: Project): ClaudeSessionManager = project.service()
    }

    fun getSessions(): List<ClaudeSession> = sessions.toList()

    fun getActiveSession(): ClaudeSession? =
        sessions.find { it.id == activeSessionId }

    fun setActiveSession(id: String) {
        activeSessionId = id
        listeners.forEach { it.onSessionChanged(getActiveSession()) }
    }

    fun createSession(name: String, workingDir: String? = null): ClaudeSession {
        val dir = workingDir ?: project.basePath ?: System.getProperty("user.home")
        val sessionId = "session_${System.currentTimeMillis()}"
        val session = ClaudeSession(id = sessionId, name = name, workingDirectory = dir)
        sessions.add(session)
        activeSessionId = sessionId
        listeners.forEach { it.onSessionChanged(session) }
        return session
    }

    fun removeSession(id: String) {
        if (getActiveSession()?.id == id) cancelCurrentRequest()
        sessions.removeIf { it.id == id }
        if (activeSessionId == id) {
            activeSessionId = sessions.firstOrNull()?.id
        }
        listeners.forEach { it.onSessionChanged(getActiveSession()) }
    }

    fun hasActiveSessions(): Boolean = sessions.isNotEmpty()

    /**
     * Sends a message to the active Claude session.
     * Runs `claude -p <text> --output-format stream-json [--resume <id>]`
     * and streams the response back through ChatListener events.
     */
    fun sendMessage(text: String) {
        val session = getActiveSession() ?: return
        if (session.isProcessing) return

        // Add user message
        val userMsg = ChatMessage(role = "user", content = text)
        session.messages.add(userMsg)
        listeners.forEach { it.onMessageAdded(session, userMsg) }

        // Start assistant response
        session.isProcessing = true
        listeners.forEach { it.onProcessingChanged(session, true) }

        val assistantMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
        session.messages.add(assistantMsg)
        listeners.forEach { it.onMessageAdded(session, assistantMsg) }

        ApplicationManager.getApplication().executeOnPooledThread {
            runClaudeProcess(session, text, assistantMsg)
        }
    }

    fun cancelCurrentRequest() {
        currentProcess?.destroyForcibly()
        currentProcess = null
        val session = getActiveSession()
        if (session != null && session.isProcessing) {
            session.isProcessing = false
            val lastMsg = session.messages.lastOrNull()
            if (lastMsg?.isStreaming == true) {
                lastMsg.isStreaming = false
                if (lastMsg.content.isEmpty()) lastMsg.content = "(cancelled)"
                SwingUtilities.invokeLater {
                    listeners.forEach { it.onMessageUpdated(session, lastMsg) }
                    listeners.forEach { it.onProcessingChanged(session, false) }
                }
            }
        }
    }

    /**
     * Sends a slash command (e.g., /help, /compact, /clear).
     * Slash commands are sent as regular messages — the claude CLI handles them.
     */
    fun sendSlashCommand(command: String) {
        sendMessage(command)
    }

    fun sendFileReference(filePath: String, prompt: String? = null) {
        val projectBase = project.basePath ?: ""
        val relative = if (filePath.startsWith(projectBase)) {
            filePath.removePrefix(projectBase).trimStart('/', '\\')
        } else filePath
        val message = if (prompt != null) "$prompt @$relative" else "@$relative"
        sendMessage(message)
    }

    fun sendCodeSnippet(code: String, filePath: String, prompt: String? = null) {
        val lang = detectLanguage(filePath)
        val projectBase = project.basePath ?: ""
        val relative = if (filePath.startsWith(projectBase)) {
            filePath.removePrefix(projectBase).trimStart('/', '\\')
        } else filePath
        val header = prompt ?: "Look at this code from $relative:"
        sendMessage("$header\n\n```$lang\n${code.trim()}\n```")
    }

    // -------------------------------------------------------------------------
    // Process Management
    // -------------------------------------------------------------------------

    private fun runClaudeProcess(session: ClaudeSession, text: String, assistantMsg: ChatMessage) {
        try {
            val settings = ClaudeSettings.getInstance()
            val cmd = buildCommand(settings, text, session.claudeSessionId)
            val pb = ProcessBuilder(cmd)
                .directory(File(session.workingDirectory))
                .redirectErrorStream(false)

            val process = pb.start()
            currentProcess = process

            // Close stdin immediately so claude doesn't wait for piped input
            process.outputStream.close()

            val fullContent = StringBuilder()

            // Read stdout line by line (NDJSON stream)
            // Use explicit readLine() loop — useLines/sequences can buffer
            val reader = process.inputStream.bufferedReader()
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val parsed = parseLine(line, session)
                    if (parsed != null) {
                        fullContent.append(parsed)
                        assistantMsg.content = fullContent.toString()
                        SwingUtilities.invokeLater {
                            listeners.forEach { it.onMessageUpdated(session, assistantMsg) }
                        }
                    }
                }
                line = reader.readLine()
            }

            // Read stderr for errors
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0 && fullContent.isEmpty() && stderr.isNotEmpty()) {
                fullContent.append("Error: $stderr")
                assistantMsg.content = fullContent.toString()
            }

            // Finalize
            assistantMsg.isStreaming = false
            if (assistantMsg.content.isEmpty()) {
                assistantMsg.content = "(no response)"
            }

            SwingUtilities.invokeLater {
                listeners.forEach { it.onMessageUpdated(session, assistantMsg) }
                session.isProcessing = false
                listeners.forEach { it.onProcessingChanged(session, false) }
            }

        } catch (e: Exception) {
            assistantMsg.content = "Error running claude: ${e.message}"
            assistantMsg.isStreaming = false
            SwingUtilities.invokeLater {
                listeners.forEach { it.onMessageUpdated(session, assistantMsg) }
                session.isProcessing = false
                listeners.forEach { it.onProcessingChanged(session, false) }
            }
        } finally {
            currentProcess = null
        }
    }

    private fun buildCommand(settings: ClaudeSettings, text: String, resumeId: String?): List<String> {
        val cmd = mutableListOf(settings.claudeExecutable, "-p", text, "--output-format", "stream-json", "--verbose")
        if (resumeId != null) {
            cmd += listOf("--resume", resumeId)
        }
        if (settings.defaultModel.isNotBlank()) {
            cmd += listOf("--model", settings.defaultModel)
        }
        if (settings.additionalArgs.isNotBlank()) {
            cmd += settings.additionalArgs.trim().split("\\s+".toRegex())
        }
        return cmd
    }

    /**
     * Parses a single NDJSON line from `claude --output-format stream-json`.
     * Returns text content to append, or null if the line is metadata-only.
     */
    private fun parseLine(line: String, session: ClaudeSession): String? {
        return try {
            val json = JsonParser.parseString(line).asJsonObject
            val type = json.get("type")?.asString ?: return null

            when (type) {
                "assistant" -> {
                    // Extract text from message content blocks
                    val message = json.getAsJsonObject("message") ?: return null
                    val content = message.getAsJsonArray("content") ?: return null
                    val sb = StringBuilder()
                    for (block in content) {
                        val blockObj = block.asJsonObject
                        when (blockObj.get("type")?.asString) {
                            "text" -> sb.append(blockObj.get("text")?.asString ?: "")
                            "tool_use" -> {
                                val name = blockObj.get("name")?.asString ?: "tool"
                                sb.append("\n[Using tool: $name]\n")
                            }
                        }
                    }
                    sb.toString().ifEmpty { null }
                }

                "content_block_delta" -> {
                    val delta = json.getAsJsonObject("delta") ?: return null
                    delta.get("text")?.asString
                }

                "result" -> {
                    // Extract session ID for multi-turn and cost info
                    json.get("session_id")?.asString?.let { session.claudeSessionId = it }
                    val cost = json.get("cost_usd")?.asDouble
                    if (cost != null) {
                        val lastMsg = session.messages.lastOrNull { it.role == "assistant" }
                        lastMsg?.costUsd = cost
                    }
                    // result text (if no streaming content was received)
                    val resultText = json.get("result")?.asString
                    val lastMsg = session.messages.lastOrNull { it.role == "assistant" }
                    if (lastMsg != null && lastMsg.content.isEmpty() && resultText != null) {
                        return resultText
                    }
                    null
                }

                "system" -> null // init events, ignore
                "error" -> {
                    val errorMsg = json.get("error")?.asJsonObject?.get("message")?.asString
                        ?: json.get("message")?.asString
                        ?: "Unknown error"
                    "Error: $errorMsg"
                }

                else -> null
            }
        } catch (_: Exception) {
            // If JSON parsing fails, return the raw line (fallback)
            if (line.startsWith("{")) null else line
        }
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast('.').lowercase()) {
            "ts" -> "typescript"; "tsx" -> "tsx"; "js" -> "javascript"; "jsx" -> "jsx"
            "mjs", "cjs" -> "javascript"; "json" -> "json"; "css" -> "css"
            "scss", "sass" -> "scss"; "html" -> "html"; "vue" -> "vue"
            "svelte" -> "svelte"; "md" -> "markdown"; "py" -> "python"
            "java" -> "java"; "kt" -> "kotlin"; "go" -> "go"; "rs" -> "rust"
            "sh", "bash" -> "bash"; "yaml", "yml" -> "yaml"; "xml" -> "xml"
            else -> ""
        }
    }

    override fun dispose() {
        cancelCurrentRequest()
        sessions.clear()
    }
}
