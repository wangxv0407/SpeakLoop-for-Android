package com.speakloop.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 OpenAI 兼容接口补全释义 / 例句 / 情景对话。
 * 只依赖 HttpURLConnection，不引 OkHttp，少一个依赖少一份风险。
 */
object Llm {

    /** 调用失败。message 已经是给用户看的中文说明，带上了请求地址和排查建议。 */
    class ApiException(val status: Int, val endpoint: String, val body: String, msg: String) :
        Exception(msg)

    data class Enriched(
        val meaning: String,
        val examples: List<String>,
        val dialogue: List<Line>
    )

    data class TestOutcome(val millis: Long, val endpoint: String, val reply: String)

    private const val SYSTEM = """You are an English-teaching assistant for a Chinese learner.
Given one English phrase, return ONLY compact JSON, no markdown, no explanation:
{"meaning":"中文释义，一句","examples":["例句1","例句2"],"dialogue":[{"s":"A","t":"..."},{"s":"B","t":"..."}]}
Rules: examples must be 2 natural sentences containing the phrase; dialogue must be 4 lines
alternating A and B, a realistic situation using the phrase; all English text stays English."""

    /**
     * 把用户填的 Base URL 归一成完整的 chat/completions 地址。
     *
     * 手机上从别处粘贴很容易带尾随空格或斜杠，也有人直接粘了整条
     * `.../v1/chat/completions`。这些都会拼出奇怪的路径然后 404，所以先清洗。
     */
    fun resolveEndpoint(baseUrl: String): String {
        val tail = "/chat/completions"
        var b = baseUrl.trim()
        while (b.endsWith("/")) b = b.dropLast(1)
        if (b.endsWith(tail, ignoreCase = true)) {
            b = b.substring(0, b.length - tail.length)
            while (b.endsWith("/")) b = b.dropLast(1)
        }
        return b + tail
    }

    /** 用当前配置发一次最小请求，验证地址 / 密钥 / 模型三项是否都通。 */
    fun test(baseUrl: String, apiKey: String, model: String): TestOutcome {
        val started = System.currentTimeMillis()
        val endpoint = resolveEndpoint(baseUrl)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "user").put("content", "ping"))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .toString()

        val body = post(endpoint, apiKey, payload)
        return TestOutcome(
            millis = System.currentTimeMillis() - started,
            endpoint = endpoint,
            reply = contentOf(body).replace("\n", " ").take(60)
        )
    }

    fun enrich(baseUrl: String, apiKey: String, model: String, phrase: String): Enriched {
        val endpoint = resolveEndpoint(baseUrl)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM))
        messages.put(JSONObject().put("role", "user").put("content", phrase))
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("messages", messages)
            .toString()

        val body = post(endpoint, apiKey, payload)
        val content = contentOf(body)
        if (content.isBlank()) {
            throw ApiException(
                200, endpoint, body,
                "模型返回了空内容。\n这是推理模型，可能把输出都放进了 reasoning_content。\n换个模型试试，或重试一次。"
            )
        }
        return parse(content, endpoint)
    }

    // ---------- 内部 ----------

    private fun post(endpoint: String, apiKey: String, payload: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException(code, endpoint, text, explain(code, endpoint, text))
            }
            return text
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(
                0, endpoint, "",
                "连不上服务器：${e.message ?: e.javaClass.simpleName}\n" +
                    "检查手机网络是否能访问这个地址：\n$endpoint"
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 把 HTTP 状态翻成人话。顺序很重要：model_not_found 也返回 404，
     * 如果先判 404 就会误导用户去改地址。
     */
    private fun explain(code: Int, endpoint: String, body: String): String {
        return when {
            body.contains("model_not_found", ignoreCase = true) ->
                "HTTP $code · 模型名不可用（model_not_found）\n" +
                    "你填的模型在当前账号下没有权限。\n" +
                    "建议改成：agnes-2.5-flash\n" +
                    "请求地址：$endpoint"

            code == 401 || code == 403 ->
                "HTTP $code · 密钥无效或没有权限\n" +
                    "检查 API Key 是否填错、是否已被吊销。\n" +
                    "请求地址：$endpoint\n" +
                    "服务端回应：${body.take(200)}"

            code == 404 ->
                "HTTP $code · 地址不存在\n" +
                    "Base URL 应该只填到 /v1 这一段，不要带 /chat/completions。\n" +
                    "请求地址：$endpoint\n" +
                    "服务端回应：${body.take(200)}"

            else ->
                "HTTP $code\n请求地址：$endpoint\n服务端回应：${body.take(300)}"
        }
    }

    private fun contentOf(body: String): String {
        return try {
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
                .trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** 模型偶尔会包一层 ```json，这里把它剥掉再解析。 */
    private fun parse(raw: String, endpoint: String): Enriched {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw ApiException(
                200, endpoint, raw,
                "模型没有返回 JSON，无法解析。\n原始内容：${raw.take(200)}"
            )
        }
        val text = raw.substring(start, end + 1)

        val root = JSONObject(text)
        val meaning = root.optString("meaning", "")

        val examples = ArrayList<String>()
        val exArr = root.optJSONArray("examples")
        if (exArr != null) {
            for (i in 0 until exArr.length()) {
                val s = exArr.optString(i, "")
                if (s.isNotBlank()) examples.add(s)
            }
        }

        val dialogue = ArrayList<Line>()
        val dlgArr = root.optJSONArray("dialogue")
        if (dlgArr != null) {
            for (i in 0 until dlgArr.length()) {
                val o = dlgArr.optJSONObject(i)
                if (o != null) {
                    val sp = o.optString("s", if (i % 2 == 0) "A" else "B")
                    val tx = o.optString("t", "")
                    if (tx.isNotBlank()) dialogue.add(Line(sp, tx))
                } else {
                    val tx = dlgArr.optString(i, "")
                    if (tx.isNotBlank()) {
                        dialogue.add(Line(if (i % 2 == 0) "A" else "B", tx))
                    }
                }
            }
        }

        return Enriched(meaning, examples, dialogue)
    }
}
