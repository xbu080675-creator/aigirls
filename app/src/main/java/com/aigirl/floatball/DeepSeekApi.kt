package com.aigirl.floatball

import org.json.JSONArray
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
            // 显式用 JSONArray + JSONObject 构造 messages。
            // 绝对不能用 JSONObject.put("messages", List<Map>) —— Android 的 org.json
            // 对 List<Map> 不会自动转成 JSONArray，而是调用 toString() 变成
            // "[{role=system, content=...}]" 字符串，导致服务端 400 "expected a sequence"。
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt(characterId))
            })
            val take = history.takeLast(20)
            for ((u, a) in take) {
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", u)
                })
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", a)
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMsg)
            })

            // 单次请求：返回 Triple(成功?, 回复或错误消息, HTTP状态码)
            fun postRequest(payload: JSONObject): Triple<Boolean, String, Int> {
                return try {
                    val conn = (URL("$BASE/chat/completions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 60000
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $key")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                        it.write(payload.toString())
                        it.flush()
                    }
                    val code = conn.responseCode
                    val body = if (code == 200) conn.inputStream else conn.errorStream
                    val text = BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { it.readText() }
                    if (code == 200) {
                        val reply = JSONObject(text)
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                        Triple(true, reply, code)
                    } else {
                        val msg = try {
                            JSONObject(text).getJSONObject("error").getString("message")
                        } catch (_: Exception) { text.take(200) }
                        Triple(false, msg, code)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Triple(false, "请求超时，检查网络", 0)
                } catch (e: java.net.UnknownHostException) {
                    Triple(false, "网络不可用", 0)
                } catch (e: Exception) {
                    Triple(false, "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}", 0)
                }
            }

            // 第一次尝试：带 thinking=disabled（V4 默认 effort=high 太慢，桌宠不需要 CoT）
            val payload1 = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", 256)
                // 去掉 temperature：思考模式下不生效，非思考模式去掉可减少 400 面
                put("thinking", JSONObject().apply { put("type", "disabled") })
            }
            val (ok1, msg1, code1) = postRequest(payload1)
            if (ok1) {
                onResult(msg1)
                return@Thread
            }

            // 若第一次失败是 400 反序列化错误（"Failed to deserialize..."），
            // 说明 thinking 参数在当前模型/网关下不被接受，自动 fallback 到不带 thinking。
            // 此时用默认思考模式（enabled + high effort），把 max_tokens 放大避免被思考挤占。
            val isDeserializeError = code1 == 400 && msg1.contains("deserialize", ignoreCase = true)
            if (isDeserializeError) {
                val payload2 = JSONObject().apply {
                    put("model", MODEL)
                    put("messages", messages)
                    put("max_tokens", 512)
                    // 不带 thinking：走默认 enabled + high effort
                }
                val (ok2, msg2, code2) = postRequest(payload2)
                if (ok2) {
                    onResult(msg2)
                    return@Thread
                }
                // fallback 也失败：展示第二次的错误（更可能是真实问题）
                onError("API错误 $code2: $msg2")
                return@Thread
            }

            // 非反序列化错误（401/402/429/500 等）：直接展示第一次的完整错误
            onError("API错误 $code1: $msg1")
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
