package com.aigirl.floatball

/**
 * DeepSeek 真实实现。HTTP 调用逻辑已在上轮 v1.4.3 修复并验证（BASE/MODEL/thinking=disabled/
 * timeout/不截断），这里只做薄封装，避免逻辑重复。
 *
 * 后续接入其他家时，按这个模板各写一个 Provider 即可，Service 端无需改动。
 */
class DeepSeekProvider : AiProvider {
    override val supportsBalance: Boolean = true
    override val displayName: String = "DeepSeek"

    override fun chat(
        key: String,
        userMsg: String,
        history: List<Pair<String, String>>,
        characterId: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        DeepSeekApi.chat(key, userMsg, history, characterId, onResult, onError)
    }

    override fun balance(key: String, onResult: (String, Boolean) -> Unit) {
        DeepSeekApi.balance(key, onResult)
    }
}
