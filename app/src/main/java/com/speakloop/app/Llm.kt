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

    data class Enriched(
        val meaning: String,
        val examples: List<String>,
        val dialogue: List<Line>
    )

    private const val SYSTEM = """You are an English-teaching assistant for a Chinese learner.
Given one English phrase, return ONLY compact JSON, no markdown, no explanation:
{"meaning":"中文释义，一句","examples":["例句1","例句2"],"dialogue":[{"s":"A","t":"..."},{"s":"B","t":"..."}]}
Rules: examples must be 2 natural sentences containing the phrase; dialogue must be 4 lines
alternating A and B, a realistic situation using the phrase; all English text stays English."""

    fun enrich(baseUrl: String, apiKey: String, model: String, phrase: String): Enriched {
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM))
        messages.put(JSONObject().put("role", "user").put("content", phrase))

        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("messages", messages)
            .toString()

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
                throw IllegalStateException("接口返回 HTTP $code：${text.take(180)}")
            }

            val content = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            return parse(content)
        } finally {
            conn.disconnect()
        }
    }

    /** 模型偶尔会包一层 ```json，这里把它剥掉再解析。 */
    private fun parse(raw: String): Enriched {
        var text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalStateException("模型没返回 JSON：${raw.take(120)}")
        text = text.substring(start, end + 1)

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
