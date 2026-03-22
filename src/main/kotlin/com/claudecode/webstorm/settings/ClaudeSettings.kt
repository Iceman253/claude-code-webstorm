package com.claudecode.webstorm.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

@State(
    name = "ClaudeCodeSettings",
    storages = [Storage("claudeCode.xml")]
)
@Service(Service.Level.APP)
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    data class State(
        var claudeExecutable: String = "claude",
        var defaultModel: String = "",
        var additionalArgs: String = "",
        var autoOpenOnStartup: Boolean = false,
        var showFloatingPromptHint: Boolean = true,
        var defaultSessionName: String = "Session"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // Convenience accessors
    var claudeExecutable: String
        get() = state.claudeExecutable
        set(value) { state.claudeExecutable = value }

    var defaultModel: String
        get() = state.defaultModel
        set(value) { state.defaultModel = value }

    var additionalArgs: String
        get() = state.additionalArgs
        set(value) { state.additionalArgs = value }

    var autoOpenOnStartup: Boolean
        get() = state.autoOpenOnStartup
        set(value) { state.autoOpenOnStartup = value }

    var showFloatingPromptHint: Boolean
        get() = state.showFloatingPromptHint
        set(value) { state.showFloatingPromptHint = value }

    var defaultSessionName: String
        get() = state.defaultSessionName
        set(value) { state.defaultSessionName = value }

    companion object {
        fun getInstance(): ClaudeSettings =
            ApplicationManager.getApplication().service()
    }
}
