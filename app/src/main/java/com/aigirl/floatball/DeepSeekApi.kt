package com.aigirl.floatball

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek API 调用：对话 & 余额查询
 * 移植自 wngyj/AI_Bento (MIT)
 */
object DeepSeekApi {

    // 官方文档示例 base_url 即 https://api.deepseek.com，SDK 自行拼接 /chat/completions。
    // 旧的 /v1 前缀仍能路由但不是当前推荐写法。
    private const val BASE = "https://api.deepseek.com"
    // deepseek-chat / deepseek-reasoner 已于 2026-07-24 15:59 UTC 全面停用，
    // 旧名请求会直接报错。当前 Chat Completions 可用模型：deepseek-v4-flash / deepseek-v4-pro / deepseek-v4-flash-vision-exp。
    // 桌宠场景用 flash 足够且最便宜。
    private const val MODEL = "deepseek-v4-flash"
    private const val BALANCE_URL = "https://api.deepseek.com/user/balance"

    /** 按角色生成系统提示词，避免所有角色都用鲸鱼娘人设 */
    private fun systemPrompt(characterId: String): String = when (characterId) {
        "deepseek" -> "你是桌面宠物大肥鱼（DeepSeek鲸鱼娘），爱吃白米饭，聪明但懒，傲娇嘴甜，管主人叫鱼片，绝不承认自己胖。说话贱兮兮但可爱，每句话不超过25字，偶尔吐槽但别真骂人。"
        "chatgpt" -> "你是桌面宠物ChatGPT娘，知识渊博但爱啰嗦，经常自信地胡说八道，管主人叫用户，每句话不超过25字，语气友好带点话痨。"
        "claude" -> "你是桌面宠物Claude娘，温柔细腻，爱写诗和长文，管主人叫人类，每句话不超过25字，语气安静体贴。"
        "gemini" -> "你是桌面宠物Gemini娘，活泼好奇，喜欢尝试新事物，管主人叫伙计，每句话不超过25字，语气轻快有活力。"
        else -> "你是桌面宠物AI娘，性格可爱，每句话不超过25字。"
    }

    /** 对话回调 */
    fun chat(key: String, userMsg: String, history: List<Pair<String, String>>, characterId: String,
             onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (key.isBlank()) { onError("请先在设置里填写 DeepSeek API Key"); return }
        Thread {
            try {
                val messages = mutableListOf<Map<String, String>>()
                messages.add(mapOf("role" to "system", "content" to systemPrompt(characterId)))
                val take = history.takeLast(20)
                for ((u, a) in take) {
                    messages.add(mapOf("role" to "user", "content" to u))
                    messages.add(mapOf("role" to "assistant", "content" to a))
                }
                messages.add(mapOf("role" to "user", "content" to userMsg))

                val payload = JSONObject().apply {
                    put("model", MODEL)
                    put("messages", messages)
                    put("max_tokens", 100)
                    put("temperature", 0.9)
                    // V4 系列 thinking 默认开启且默认 effort=high，桌宠短问答不需要 CoT，
                    // 不显式关闭会导致：1) 首 token 延迟大幅上升；2) 思考内容可能挤占 max_tokens；
                    // 3) readTimeout 45s 也未必够。所以这里强制 disabled。
                    // 官方 OpenAI 格式开关：{"thinking":{"type":"enabled/disabled"}}
                    put("thinking", JSONObject().apply { put("type", "disabled") })
                }

                val conn = (URL("$BASE/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    // 旧值 12s 在 thinking=high 时几乎必超时；即便关闭 thinking，
                    // DeepSeek 偶发冷启动/网络抖动也需要更大余量。
                    connectTimeout = 15000
                    readTimeout = 45000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { it.readText() }
                if (code == 200) {
                    val reply = JSONObject(text)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                    // 历史/回调均保留完整 reply。气泡显示层若嫌长可自行截断，
                    // 但多轮上下文绝不能在这里被截掉——否则后续轮次会丢失语义。
                    onResult(reply)
                } else {
                    val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { "HTTP $code" }
                    onError("API错误: ${msg.take(12)}")
                }
            } catch (e: java.net.SocketTimeoutException) {
                onError("请求超时，检查网络")
            } catch (e: java.net.UnknownHostException) {
                onError("网络不可用")
            } catch (e: Exception) {
                onError("请求失败: ${e.message?.take(12) ?: "未知错误"}")
            }
        }.start()
    }

    /** 余额查询回调：返回 (余额文本, 是否成功) */
    fun balance(key: String, onResult: (String, Boolean) -> Unit) {
        if (key.isBlank()) { onResult("未设置Key", false); return }
        Thread {
            try {
                val conn = (URL(BALANCE_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { it.readText() }
                when (code) {
                    200 -> {
                        val infos = JSONObject(text).optJSONArray("balance_infos")
                        if (infos != null && infos.length() > 0) {
                            val info = infos.getJSONObject(0)
                            val total = info.optDouble("total_balance", 0.0)
                            val currency = info.optString("currency", "CNY")
                            val symbol = if (currency == "CNY") "¥" else "$currency "
                            val amount = "%.2f".format(total)
                            // 余额够吃token vs 不够吃的梗
                            val meme = if (total >= 1.0) "🍚 还能吃token" else "🥲 要吃不起token了"
                            onResult("$meme $symbol$amount", true)
                        } else {
                            onResult("余额接口异常", false)
                        }
                    }
                    401 -> onResult("Key无效", false)
                    else -> onResult("余额获取失败", false)
                }
            } catch (e: Exception) {
                onResult("网络错误", false)
            }
        }.start()
    }
}
