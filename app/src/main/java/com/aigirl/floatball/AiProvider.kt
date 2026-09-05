package com.aigirl.floatball

/**
 * AI 模型 Provider 抽象。
 *
 * v1.4.3 改造目标：把“到底找哪个模型”从 [DeepSeekApi] 解耦出来。
 * 现状是四角色（deepseek/chatgpt/claude/gemini）全都走 DeepSeek API 只换 system prompt
 * ——“ChatGPT 娘戴着 DeepSeek 的假发”。这一版先立接口 + DeepSeek 真实实现 + 其他三家骨架
 * （调用即返回“接入开发中”），后续逐家补真实 HTTP 实现。
 *
 * 调用方约定：
 *   val provider = ProviderRegistry.forCharacter(Prefs.characterId)
 *   provider.chat(Prefs.dsApiKey, msg, history, characterId, onResult = ..., onError = ...)
 *   if (provider.supportsBalance) provider.balance(key) { ... }
 *
 * 回调线程：与 [DeepSeekApi] 一致，在工作线程触发，调用方需自行 post 回主线程。
 */
interface AiProvider {

    /** 该 provider 是否支持余额查询。不支持则设置页不显示余额开关、Service 不跑余额轮询。 */
    val supportsBalance: Boolean

    /** 人类可读名（用于错误提示与设置页）。 */
    val displayName: String

    /**
     * 发起对话。
     * @param key API Key（不同 provider 用各自的 key；现阶段统一存于 [Prefs.dsApiKey]）
     * @param userMsg 用户本轮输入
     * @param history 多轮上下文，List<(user, assistant)>
     * @param characterId 角色人设 id（同一 provider 下也可有多个人设）
     * @param onResult 完整回复（不截断，历史需保留完整文本）
     * @param onError 简短错误文案
     */
    fun chat(
        key: String,
        userMsg: String,
        history: List<Pair<String, String>>,
        characterId: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * 余额查询。不支持时回调 (false)。
     * @return (显示文本, 是否成功)
     */
    fun balance(key: String, onResult: (String, Boolean) -> Unit)
}
