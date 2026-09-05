package com.aigirl.floatball

import android.content.Context
import org.json.JSONObject

/**
 * 鲸鲸梗宇宙 - 梗图管理器
 *
 * 职责：
 * 1. 从 res/raw/meme_pack.json 加载 20 张梗图元数据
 * 2. 按事件（BALANCE_OK / THINKING_LONG ...）筛选候选梗
 * 3. 按权重随机抽取 + 冷却控制（避免同一张频繁出现）
 * 4. 非 publicSafe 的彩蛋梗仅在开发者模式下可见
 *
 * 设计参考：用户整理的 5 大类梗图（余额/思考/出错/人设/视觉反应）
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
    )

    private var memes: List<Meme> = emptyList()
    private val lastShown = mutableMapOf<String, Long>() // memeId -> 上次展示时间戳(ms)

    /** 加载梗库（在 Application/Service onCreate 中调用一次即可） */
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
                    )
                )
            }
            memes = list
        } catch (e: Exception) {
            memes = emptyList()
        }
    }

    /** 是否已加载 */
    fun isLoaded(): Boolean = memes.isNotEmpty()

    /**
     * 为指定事件挑选一张梗图
     * @param event 事件名，如 BALANCE_OK
     * @param developerMode 是否开启开发者模式（决定非 publicSafe 彩蛋是否可见）
     * @return 选中的 Meme；若无可用则返回 null
     */
    fun pickForEvent(event: String, developerMode: Boolean = false): Meme? {
        if (memes.isEmpty()) return null
        val now = System.currentTimeMillis()

        // 1. 事件匹配
        val matched = memes.filter { event in it.trigger }
        if (matched.isEmpty()) return null

        // 2. 安全过滤：非 publicSafe 仅在开发者模式可见
        val safe = matched.filter { it.publicSafe || developerMode }
        if (safe.isEmpty()) return null

        // 3. 冷却过滤：cooldownSec 内不重复
        val cooled = safe.filter { m ->
            val last = lastShown[m.id] ?: 0L
            now - last >= m.cooldownSec * 1000L
        }
        val pool = cooled.ifEmpty { safe } // 全在冷却中则放宽（仍展示）

        // 4. 按优先级分组，取最高优先级组
        val maxPriority = pool.minOf { it.priority }
        val top = pool.filter { it.priority == maxPriority }

        // 5. 权重随机抽取
        val totalWeight = top.sumOf { it.weight }
        if (totalWeight <= 0) return top.firstOrNull()
        var r = (0 until totalWeight).random()
        for (m in top) {
            r -= m.weight
            if (r < 0) {
                lastShown[m.id] = now
                return m
            }
        }
        val fallback = top.first()
        lastShown[fallback.id] = now
        return fallback
    }

    /** 重置所有冷却（调试用） */
    fun resetCooldowns() {
        lastShown.clear()
    }

    /** 调试：获取所有梗（用于设置界面预览） */
    fun allMemes(): List<Meme> = memes
}
