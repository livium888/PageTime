package com.pagetime.app.data.learning

sealed interface AiGenerationState {
    data object Idle : AiGenerationState
    data object Disabled : AiGenerationState
    data object Generating : AiGenerationState
    data class Generated(val count: Int, val topicCount: Int) : AiGenerationState
    data class Failed(val message: String) : AiGenerationState
}
