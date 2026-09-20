package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiThinking
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.util.Constants

class DeepSeekProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_DEEPSEEK
    override val defaultBaseUrl: String = "https://api.deepseek.com"

    /**
     * DeepSeek controls thinking through `thinking.type` and `reasoning_effort`. Neither used to
     * be sent, so the app toggle did nothing and the server default (thinking on, effort high)
     * always applied.
     */
    override fun customizeRequest(
        request: OpenAiChatRequest,
        config: ProviderConfig,
    ): OpenAiChatRequest = request.withDeepSeekThinking(config)

    /** A thinking-mode DeepSeek request with tools fails with 400 unless earlier turns replay CoT. */
    override fun forwardsAssistantReasoningContent(config: ProviderConfig): Boolean =
        config.includeAssistantReasoning

    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).
}

/**
 * DeepSeek accepts only three `reasoning_effort` values. Official mapping:
 * minimal/low -> low, medium/high/xhigh -> high, max -> max, none -> no thinking.
 */
internal fun deepSeekReasoningEffort(thinkingLevel: String): String? =
    when (ThinkingLevels.normalize(thinkingLevel)) {
        "none" -> null
        "minimal", "low" -> "low"
        "medium", "high", "xhigh" -> "high"
        "max" -> "max"
        else -> "high"
    }

/** True when a model id names a DeepSeek model, including hub- and relay-style prefixes. */
internal fun isDeepSeekModel(modelId: String): Boolean =
    modelId.contains("deepseek", ignoreCase = true)

/**
 * Applies the DeepSeek thinking controls to any OpenAI-format request. Used by both the built-in
 * provider and custom endpoints, because a relayed DeepSeek model still requires the same fields.
 */
internal fun OpenAiChatRequest.withDeepSeekThinking(config: ProviderConfig): OpenAiChatRequest {
    val effort = deepSeekReasoningEffort(config.thinkingLevel).takeIf { config.thinkingEnabled }
    return if (effort == null) {
        copy(thinking = OpenAiThinking(type = "disabled"))
    } else {
        copy(
            thinking = OpenAiThinking(type = "enabled"),
            reasoningEffort = effort,
        )
    }
}
