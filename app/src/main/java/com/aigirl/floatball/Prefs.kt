package com.aigirl.floatball

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * 角色定义 & 偏好管理
 * 所有角色素材均为原创 MIT 协议或取自 MIT 协议项目
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
            "whale",
            R.drawable.char_whale_girl,
            R.string.char_whale_name,
            R.string.hello_whale,
            "#3B82F6",
        ),
        CharacterDef(
            "cat",
            R.drawable.char_cat_girl,
            R.string.char_cat_name,
            R.string.hello_cat,
            "#F97316",
        ),
        CharacterDef(
            "maid",
            R.drawable.char_maid_girl,
            R.string.char_maid_name,
            R.string.hello_maid,
            "#EC4899",
        ),
        CharacterDef(
            "mecha",
            R.drawable.char_mecha_girl,
            R.string.char_mecha_name,
            R.string.hello_mecha,
            "#6366F1",
        ),
        CharacterDef(
            "mage",
            R.drawable.char_mage_girl,
            R.string.char_mage_name,
            R.string.hello_mage,
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
        get() = s().getString("character_id", "whale") ?: "whale"
        set(v) = s().edit().putString("character_id", v).apply()

    var enabled: Boolean
        get() = s().getBoolean("enabled", false)
        set(v) = s().edit().putBoolean("enabled", v).apply()

    var sizePx: Int
        get() = s().getInt("size_px", 200) // 默认 200px
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
        get() = s().getString("click_action", "hello") ?: "hello"
        set(v) = s().edit().putString("click_action", v).apply()

    // 保存悬浮球上一次位置
    var lastX: Int
        get() = s().getInt("last_x", -1)
        set(v) = s().edit().putInt("last_x", v).apply()

    var lastY: Int
        get() = s().getInt("last_y", -1)
        set(v) = s().edit().putInt("last_y", v).apply()

    val sizeDp: Int get() = (sizePx / App.instance.resources.displayMetrics.density).toInt()
}
