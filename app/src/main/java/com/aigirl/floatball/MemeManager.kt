package com.aigirl.floatball

import android.content.Context
import org.json.JSONObject

/**
 * 鲸鲸梗宇宙 - 梗包 Dispatcher
 *
 * v1.4.3 改造目标：把 MemeManager 从“内部触发逻辑”升级成“明确的梗包调用 API”。
 *   - 事件用枚举 [MemeEvent] 表达，业务端不再裸写字符串
 *   - 对外提供 [emit] / [getById] / [pickByTag] / [preview] 四个入口
 *   - [Meme.image] 字段为可选 drawable 名，为后续“可安装梗包 + 图片表情包”预留
 *
 * 选择算法（v1.4.2 重写，保留）：
 * 1. 事件匹配 → 2. 安全过滤(publicSafe/开发者模式) → 3. 角色过滤(character)
 * 4. 冷却过滤（全部在冷却中则返回 null，不再绕过）
 * 5. 全局权重随机：effectiveWeight = weight * (10 - priority)
 *    —— 优先级是软偏置不是硬过滤，低优先级(数字大)的彩蛋仍有小概率被抽中
 */
object MemeManager {

    /**
     * 梗包事件枚举。业务端统一用 [emit] 触发，禁止裸写字符串。
     *
     * [display] 是面向用户的中文事件名（用于“快速添加表情包”UI 和调试面板）。
     * [name] 与 meme_pack.json 里 trigger 字段的字符串一一对应。
     */
    enum class MemeEvent(val display: String) {
        BALANCE_OK("余额充足"),
        BALANCE_LOW("余额不足"),
        TOKEN_SPENT("调用模型后"),
        THINKING_LONG("思考时间较长"),
        API_RETRY("API 正在重试"),
        API_FAILED("API 请求失败"),
        USER_ANGRY("连续快速操作"),
        IDLE_MOFISH("长时间闲置"),
    }

    /** 单条梗图定义 */
    data class Meme(
        val id: String,
        val title: String,
        val text: String,
        val tags: List<String>,
        val trigger: List<String>,
        val pose: String,
        val priority: Int,
        val cooldownSec: Long,
        val publicSafe: Boolean,
        val weight: Int,
        val character: String, // "all" 或角色 id（deepseek/chatgpt/claude/gemini）
        /** 可选 drawable 资源名（不含扩展名）。为 null 表示纯文本梗。
         *  真正接入“可安装梗包”时由 MemePackManager 解析成本地图片路径。 */
        val image: String? = null,
    )

    private var memes: List<Meme> = emptyList()
    private val lastShown = mutableMapOf<String, Long>() // memeId -> 上次展示时间戳(ms)

    /** 加载梗库（内置 pack）。仅加载内置 R.raw.meme_pack，用户包由 [MemePackManager] 用 [appendMemes] 追加。 */
    fun load(context: Context) {
        if (memes.isNotEmpty()) return
        try {
            val json = context.resources.openRawResource(R.raw.meme_pack).bufferedReader().use { it.readText() }
            memes = parseMemes(JSONObject(json).getJSONArray("memes"))
        } catch (e: Exception) {
            memes = emptyList()
        }
    }

    /**
     * 解析 memes / items 数组（兼容内置包的 `memes` 字段名与用户包的 `items` 字段名）。
     * 提到 [MemePackManager] 复用。
     */
    internal fun parseMemes(arr: org.json.JSONArray): List<Meme> {
        val list = mutableListOf<Meme>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tags = mutableListOf<String>()
            val tArr = o.optJSONArray("tags")
            if (tArr != null) for (j in 0 until tArr.length()) tags.add(tArr.getString(j))
            // 兼容两种字段名：内置包用 trigger，用户包用 events
            val trig = mutableListOf<String>()
            val trigArr = o.optJSONArray("trigger") ?: o.optJSONArray("events")
            if (trigArr != null) for (j in 0 until trigArr.length()) trig.add(trigArr.getString(j))
            list.add(
                Meme(
                    id = o.getString("id"),
                    title = o.optString("title", ""),
                    text = o.optString("text", ""),
                    tags = tags,
                    trigger = trig,
                    pose = o.optString("pose", "happy"),
                    priority = o.optInt("priority", 5),
                    cooldownSec = o.optLong("cooldown", 600L),
                    publicSafe = o.optBoolean("publicSafe", true),
                    weight = o.optInt("weight", 5),
                    character = o.optString("character", "all"),
                    image = o.optString("image", "").ifBlank { null },
                )
            )
        }
        return list
    }

    /** 重置全部（内置 + 用户）。MemePackManager.reloadAll 用它先清空再重新装载。 */
    fun reset() {
        memes = emptyList()
        lastShown.clear()
    }

    /** 追加用户包的梗（来自 [MemePackManager]）。注意：id 与内置重复时给前缀避免冲突。 */
    fun appendMemes(list: List<Meme>) {
        if (list.isEmpty()) return
        memes = memes + list
    }

    fun isLoaded(): Boolean = memes.isNotEmpty()

    /* ---------------- 公共 Dispatcher API ---------------- */

    /**
     * 触发一个事件，按权重随机挑一条梗。会写入冷却时间戳。
     * @return 命中的梗；若事件无匹配/全在冷却中/被安全或角色过滤掉则返回 null。
     */
    fun emit(
        event: MemeEvent,
        characterId: String = "all",
        developerMode: Boolean = false,
    ): Meme? = pickForEvent(event.name, developerMode, characterId)

    /** 按 id 精确取一条梗（不消耗冷却，可用于展示/调试）。 */
    fun getById(id: String): Meme? = memes.firstOrNull { it.id == id }

    /**
     * 按 tag 挑一条梗（角色过滤 + 冷却 + 权重随机，会写冷却）。
     * 用于“快速添加表情包”里按 tag 预览，或外部按主题抽样。
     */
    fun pickByTag(tag: String, characterId: String = "all"): Meme? {
        if (memes.isEmpty()) return null
        val now = System.currentTimeMillis()
        val candidates = memes
            .filter { tag in it.tags }
            .filter { it.character == "all" || it.character == characterId }
            .filter { it.publicSafe } // tag 抽样暂不放开开发者彩蛋，避免误触
            .filter { now - (lastShown[it.id] ?: 0L) >= it.cooldownSec * 1000L }
        return weightedPick(candidates, now)
    }

    /**
     * 预览某条梗：返回 [getById] 的结果，但**不**写冷却。
     * 调试/设置页“测试这条梗长什么样”时用。
     */
    fun preview(id: String): Meme? = getById(id)

    /* ---------------- 内部选择逻辑 ---------------- */

    /**
     * 为指定事件挑选一张梗图（内部用，emit 委托到这里）。
     * @param event 事件名（与 [MemeEvent.name] 一致）
     * @param developerMode 是否开发者模式（解锁非 publicSafe 彩蛋）
     * @param characterId 当前角色 id，用于过滤角色专属梗
     */
    private fun pickForEvent(event: String, developerMode: Boolean = false, characterId: String = "all"): Meme? {
        if (memes.isEmpty()) return null
        val now = System.currentTimeMillis()

        // 1. 事件匹配
        val matched = memes.filter { event in it.trigger }
        if (matched.isEmpty()) return null

        // 2. 安全过滤
        val safe = matched.filter { it.publicSafe || developerMode }
        if (safe.isEmpty()) return null

        // 3. 角色过滤：character 为 "all" 或匹配当前角色
        val charFiltered = safe.filter { it.character == "all" || it.character == characterId }
        if (charFiltered.isEmpty()) return null

        // 4. 冷却过滤：全部在冷却中 → 返回 null（不再绕过冷却，避免刷屏）
        val cooled = charFiltered.filter { m ->
            val last = lastShown[m.id] ?: 0L
            now - last >= m.cooldownSec * 1000L
        }
        if (cooled.isEmpty()) return null

        return weightedPick(cooled, now)
    }

    /**
     * 全局权重随机：effectiveWeight = weight * (10 - priority)
     * 优先级是软偏置，priority=9 的彩蛋仍有 (10-9)=1x 的基础权重机会。
     */
    private fun weightedPick(candidates: List<Meme>, now: Long): Meme? {
        if (candidates.isEmpty()) return null
        val totalWeight = candidates.sumOf { it.weight * (10 - it.priority.coerceAtMost(9)) }
        if (totalWeight <= 0) {
            val fallback = candidates.first()
            lastShown[fallback.id] = now
            return fallback
        }
        var r = (0 until totalWeight).random()
        for (m in candidates) {
            r -= m.weight * (10 - m.priority.coerceAtMost(9))
            if (r < 0) {
                lastShown[m.id] = now
                return m
            }
        }
        val fallback = candidates.first()
        lastShown[fallback.id] = now
        return fallback
    }

    /** 重置所有冷却（调试用） */
    fun resetCooldowns() {
        lastShown.clear()
    }

    fun allMemes(): List<Meme> = memes
}
