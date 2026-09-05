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

    private const val BASE = "https://api.deepseek.com/v1"
    private const val MODEL = "deepseek-chat"
    private const val BALANCE_URL = "https://api.deepseek.com/user/balance"
    private const val SYSTEM_PROMPT = "你是桌面宠物大肥鱼（DeepSeek鲸鱼娘），爱吃白米饭，聪明但懒，傲娇嘴甜，管主人叫鱼片，绝不承认自己胖。说话贱兮兮但可爱，每句话不超过25字，偶尔吐槽但别真骂人。"

    /** 对话回调 */
    fun chat(key: String, userMsg: String, history: List<Pair<String, String>>,
             onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (key.isBlank()) { onError("请先在设置里填写 DeepSeek API Key"); return }
        Thread {
            try {
                val messages = mutableListOf<Map<String, String>>()
                messages.add(mapOf("role" to "system", "content" to SYSTEM_PROMPT))
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
                }

                val conn = (URL("$BASE/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 12000
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
                    val short = if (reply.length > 30) reply.substring(0, 28) + "…" else reply
                    onResult(short)
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
