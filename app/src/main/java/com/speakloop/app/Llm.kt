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

    /** 默认服务地址。只集中在这里定义一次，设置页和调用点都引用它。 */
    const val DEFAULT_BASE = "https://api.agnes-ai.cn/v1"

    /** 调用失败。message 已经是给用户看的中文说明，带上了请求地址和排查建议。 */
    class ApiException(val status: Int, val endpoint: String, val body: String, msg: String) :
        Exception(msg)

    data class Enriched(
        val meaning: String,
        val examples: List<Example>,
        val dialogue: List<Line>
    )

    data class TestOutcome(val millis: Long, val endpoint: String, val reply: String)

    private const val SYSTEM = """You are an English-teaching assistant for a Chinese learner.
Given one English phrase, return ONLY compact JSON, no markdown, no explanation:
{"meaning":"中文释义，一句","examples":[{"t":"英文例句","c":"这句的中文"}],"dialogue":[{"s":"A","t":"英文","c":"中文"}]}
Rules:
- examples: exactly 2 natural sentences containing the phrase.
- dialogue: exactly 4 lines alternating A and B, a realistic situation using the phrase.
- t fields stay in English; c fields must be natural, colloquial Chinese translation of that same line (not a paraphrase of the phrase)."""

    private const val TRANSLATE_SYSTEM =
        """You translate English sentences into natural, colloquial Chinese for a Chinese learner.
Return ONLY a compact JSON array of strings, no markdown, no explanation, no keys.
The array must contain exactly one Chinese string per input sentence, in the same order.
Never return an empty string; if a sentence is a fragment, translate it as a fragment."""

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

    /**
     * 只翻译，不重新生成句子。
     *
     * 详情页里已经存在的例句 / 对话可能是用户手改过的，重新走 enrich 会把它们整段覆盖掉，
     * 所以中文缺失时单独调一次翻译，按顺序把结果填回原来的空位。
     */
    fun translateLines(
        baseUrl: String,
        apiKey: String,
        model: String,
        lines: List<String>
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        val endpoint = resolveEndpoint(baseUrl)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", TRANSLATE_SYSTEM))
        messages.put(JSONObject().put("role", "user").put("content", JSONArray(lines).toString()))
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", messages)
            .toString()

        val body = post(endpoint, apiKey, payload)
        val content = contentOf(body)
        if (content.isBlank()) {
            throw ApiException(
                200, endpoint, body,
                "模型返回了空内容。\n换个模型试试，或重试一次。"
            )
        }
        return parseStringArray(content, endpoint, lines.size)
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

        val examples = ArrayList<Example>()
        val exArr = root.optJSONArray("examples")
        if (exArr != null) {
            for (i in 0 until exArr.length()) {
                val o = exArr.optJSONObject(i)
                if (o != null) {
                    val en = o.optString("t", "").ifBlank { o.optString("text", "") }
                    if (en.isNotBlank()) {
                        examples.add(Example(en, pickCn(o)))
                    }
                } else {
                    // 模型偶尔还是只给字符串数组，兼容一下
                    val s = exArr.optString(i, "")
                    if (s.isNotBlank()) examples.add(Example(s, ""))
                }
            }
        }

        val dialogue = ArrayList<Line>()
        val dlgArr = root.optJSONArray("dialogue")
        if (dlgArr != null) {
            for (i in 0 until dlgArr.length()) {
                val o = dlgArr.optJSONObject(i)
                if (o != null) {
                    val en = o.optString("t", "").ifBlank { o.optString("text", "") }
                    val sp = o.optString("s", if (i % 2 == 0) "A" else "B")
                    if (en.isNotBlank()) dialogue.add(Line(sp, en, pickCn(o)))
                } else {
                    val s = dlgArr.optString(i, "")
                    if (s.isNotBlank()) {
                        dialogue.add(Line(if (i % 2 == 0) "A" else "B", s, ""))
                    }
                }
            }
        }

        return Enriched(meaning, examples, dialogue)
    }

    /** 中文可能在 c，也可能是 cn / zh，模型不太稳定，都认。 */
    private fun pickCn(o: JSONObject): String =
        o.optString("c", "").ifBlank { o.optString("cn", "") }.ifBlank { o.optString("zh", "") }

    /**
     * 从模型回复里抠出字符串数组。有的模型会包成 {"translations":[...]}，
     * 所以按第一个 [ 到最后一个 ] 截，比按顶层 JSONObject 解析宽容。
     */
    private fun parseStringArray(raw: String, endpoint: String, expect: Int): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) {
            throw ApiException(
                200, endpoint, raw,
                "模型没有返回数组，无法解析。\n原始内容：${raw.take(200)}"
            )
        }
        val arr = try {
            JSONArray(raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw ApiException(
                200, endpoint, raw,
                "模型返回的数组格式不对。\n原始内容：${raw.take(200)}"
            )
        }
        val out = ArrayList<String>(expect)
        for (i in 0 until expect) {
            out.add(if (i < arr.length()) arr.optString(i, "").trim() else "")
        }
        return out
    }
}