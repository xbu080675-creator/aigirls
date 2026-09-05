package com.aigirl.floatball

import android.content.Context
import org.json.JSONObject

/**
 * 鲸鲸梗宇宙 - 梗图管理器
 *
 * 选择算法（v1.4.2 重写）：
 * 1. 事件匹配 → 2. 安全过滤(publicSafe/开发者模式) → 3. 角色过滤(character)
 * 4. 冷却过滤（全部在冷却中则返回 null，不再绕过）
 * 5. 全局权重随机：effectiveWeight = weight * (10 - priority)
 *    —— 优先级是软偏置不是硬过滤，低优先级(数字大)的彩蛋仍有小概率被抽中
 */
object MemeManager {

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
    )

    private var memes: List<Meme> = emptyList()
    private val lastShown = mutableMapOf<String, Long>() // memeId -> 上次展示时间戳(ms)

    /** 加载梗库 */
    fun load(context: Context) {
        if (memes.isNotEmpty()) return
        try {
            val json = context.resources.openRawResource(R.raw.meme_pack).bufferedReader().use { it.readText() }
            val arr = JSONObject(json).getJSONArray("memes")
            val list = mutableListOf<Meme>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tags = mutableListOf<String>()
                val tArr = o.optJSONArray("tags")
                if (tArr != null) for (j in 0 until tArr.length()) tags.add(tArr.getString(j))
                val trig = mutableListOf<String>()
                val trigArr = o.optJSONArray("trigger")
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
                    )
                )
            }
            memes = list
        } catch (e: Exception) {
            memes = emptyList()
        }
    }

    fun isLoaded(): Boolean = memes.isNotEmpty()

    /**
     * 为指定事件挑选一张梗图
     * @param event 事件名
     * @param developerMode 是否开发者模式（解锁非 publicSafe 彩蛋）
     * @param characterId 当前角色 id，用于过滤角色专属梗
     */
    fun pickForEvent(event: String, developerMode: Boolean = false, characterId: String = "all"): Meme? {
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

        // 5. 全局权重随机：effectiveWeight = weight * (10 - priority)
        //    优先级是软偏置，priority=9 的彩蛋仍有 (10-9)=1x 的基础权重机会
        val totalWeight = cooled.sumOf { it.weight * (10 - it.priority.coerceAtMost(9)) }
        if (totalWeight <= 0) {
            val fallback = cooled.first()
            lastShown[fallback.id] = now
            return fallback
        }
        var r = (0 until totalWeight).random()
        for (m in cooled) {
            r -= m.weight * (10 - m.priority.coerceAtMost(9))
            if (r < 0) {
                lastShown[m.id] = now
                return m
            }
        }
        val fallback = cooled.first()
        lastShown[fallback.id] = now
        return fallback
    }

    /** 重置所有冷却（调试用） */
    fun resetCooldowns() {
        lastShown.clear()
    }

    fun allMemes(): List<Meme> = memes
}
