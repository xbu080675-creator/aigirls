package com.aigirl.floatball

/**
 * Provider 注册表：按角色 id 路由到对应 [AiProvider]。
 *
 * 这样 Service 不再硬编码 [DeepSeekApi]，而是：
 *   ProviderRegistry.forCharacter(Prefs.characterId).chat(...)
 *
 * 四角色 → provider 映射固定：
 *   deepseek → DeepSeekProvider（真实可用）
 *   chatgpt  → OpenAiProvider（骨架）
 *   claude   → AnthropicProvider（骨架）
 *   gemini   → GeminiProvider（骨架）
 *   未知 id  → DeepSeekProvider（兜底）
 */
object ProviderRegistry {

    private val deepseek = DeepSeekProvider()
    private val openai = OpenAiProvider()
    private val anthropic = AnthropicProvider()
    private val gemini = GeminiProvider()

    fun forCharacter(characterId: String): AiProvider = when (characterId) {
        "deepseek" -> deepseek
        "chatgpt" -> openai
        "claude" -> anthropic
        "gemini" -> gemini
        else -> deepseek
    }
}
