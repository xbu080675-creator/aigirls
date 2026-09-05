package com.aigirl.floatball

/**
 * 三家未接入 Provider 的骨架。
 *
 * v1.4.3 只搭抽象层，四角色里除 DeepSeek 外其余三家调用即返回“接入开发中”，
 * 不崩溃、不静默成功，让用户清楚知道当前能用的只有鲸鱼娘。
 *
 * 后续每家补真实实现时：把 chat() 里的 onError(...) 换成自家 HTTP 调用，
 * supportsBalance 按官方是否提供余额接口调整。
 */

class OpenAiProvider : AiProvider {
    override val supportsBalance: Boolean = false
    override val displayName: String = "OpenAI"
    override fun chat(
        key: String,
        userMsg: String,
        history: List<Pair<String, String>>,
        characterId: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("ChatGPT 接入开发中，敬请期待～\n目前仅 DeepSeek 鲸鱼娘可对话")
    }
    override fun balance(key: String, onResult: (String, Boolean) -> Unit) {
        onResult("", false)
    }
}

class AnthropicProvider : AiProvider {
    override val supportsBalance: Boolean = false
    override val displayName: String = "Anthropic"
    override fun chat(
        key: String,
        userMsg: String,
        history: List<Pair<String, String>>,
        characterId: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("Claude 接入开发中，敬请期待～\n目前仅 DeepSeek 鲸鱼娘可对话")
    }
    override fun balance(key: String, onResult: (String, Boolean) -> Unit) {
        onResult("", false)
    }
}

class GeminiProvider : AiProvider {
    override val supportsBalance: Boolean = false
    override val displayName: String = "Google"
    override fun chat(
        key: String,
        userMsg: String,
        history: List<Pair<String, String>>,
        characterId: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("Gemini 接入开发中，敬请期待～\n目前仅 DeepSeek 鲸鱼娘可对话")
    }
    override fun balance(key: String, onResult: (String, Boolean) -> Unit) {
        onResult("", false)
    }
}
