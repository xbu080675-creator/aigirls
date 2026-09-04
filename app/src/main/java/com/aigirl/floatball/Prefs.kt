package com.aigirl.floatball

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * AI 拟人化角色定义 & 偏好管理
 * 角色图片为二创风格，仅供非商业用途学习交流
 */
data class CharacterDef(
    val id: String,
    @DrawableRes val drawableRes: Int,
    @StringRes val nameRes: Int,
    @StringRes val helloRes: Int,
    val accentColor: String,
)

object CharacterStore {
    val CHARACTERS = listOf(
        CharacterDef(
            "deepseek",
            R.drawable.char_deepseek,
            R.string.char_deepseek_name,
            R.string.hello_deepseek,
            "#3B82F6",
        ),
        CharacterDef(
            "chatgpt",
            R.drawable.char_chatgpt,
            R.string.char_chatgpt_name,
            R.string.hello_chatgpt,
            "#22C55E",
        ),
        CharacterDef(
            "claude",
            R.drawable.char_claude,
            R.string.char_claude_name,
            R.string.hello_claude,
            "#F59E0B",
        ),
        CharacterDef(
            "gemini",
            R.drawable.char_gemini,
            R.string.char_gemini_name,
            R.string.hello_gemini,
            "#8B5CF6",
        ),
    )

    fun find(id: String): CharacterDef =
        CHARACTERS.firstOrNull { it.id == id } ?: CHARACTERS.first()
}

object Prefs {
    private const val NAME = "ai_girl_float_ball_prefs"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    private fun s() = sp

    var characterId: String
        get() = s().getString("character_id", "deepseek") ?: "deepseek"
        set(v) = s().edit().putString("character_id", v).apply()

    var enabled: Boolean
        get() = s().getBoolean("enabled", false)
        set(v) = s().edit().putBoolean("enabled", v).apply()

    var sizePx: Int
        get() = s().getInt("size_px", 200)
        set(v) = s().edit().putInt("size_px", v).apply()

    var opacity: Int
        get() = s().getInt("opacity", 100)
        set(v) = s().edit().putInt("opacity", v).apply()

    var autoEdge: Boolean
        get() = s().getBoolean("auto_edge", true)
        set(v) = s().edit().putBoolean("auto_edge", v).apply()

    var showHelloOnStart: Boolean
        get() = s().getBoolean("show_hello", true)
        set(v) = s().edit().putBoolean("show_hello", v).apply()

    var clickAction: String
        get() = s().getString("click_action", "toolbar") ?: "toolbar"
        set(v) = s().edit().putString("click_action", v).apply()

    var petName: String
        get() = s().getString("pet_name", "") ?: ""
        set(v) = s().edit().putString("pet_name", v).apply()

    var heartEnabled: Boolean
        get() = s().getBoolean("heart_enabled", true)
        set(v) = s().edit().putBoolean("heart_enabled", v).apply()

    var lastX: Int
        get() = s().getInt("last_x", -1)
        set(v) = s().edit().putInt("last_x", v).apply()

    var lastY: Int
        get() = s().getInt("last_y", -1)
        set(v) = s().edit().putInt("last_y", v).apply()

    val sizeDp: Int get() = (sizePx / App.instance.resources.displayMetrics.density).toInt()
}
