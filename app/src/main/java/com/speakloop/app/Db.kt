package com.speakloop.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单文件 SQLite。例句和对话序列化成 JSON 存在一行里，
 * 这样不用建三张表，也就没有跨表一致性问题。
 *
 * 中文翻译也塞在同一个 JSON 里，所以加翻译**不用升数据库版本**：
 * 老数据解码时按「没有翻译」处理，不影响读取。
 */
class Db(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "speakloop.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE phrase (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "phrase TEXT NOT NULL," +
                "meaning TEXT NOT NULL DEFAULT ''," +
                "examples TEXT NOT NULL DEFAULT '[]'," +
                "dialogue TEXT NOT NULL DEFAULT '[]'," +
                "play_count INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只有 v1，暂时不需要迁移
    }

    /** 复习优先级：播得少的在前，同次数按创建时间，越老越靠前。 */
    fun all(): List<Phrase> {
        val out = ArrayList<Phrase>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, phrase, meaning, examples, dialogue, play_count, created_at " +
                "FROM phrase ORDER BY play_count ASC, created_at ASC",
            null
        )
        try {
            while (cursor.moveToNext()) out.add(read(cursor))
        } finally {
            cursor.close()
        }
        return out
    }

    /** 详情页按 id 取一条。返回 null 表示这条已经被删了。 */
    fun byId(id: Long): Phrase? {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, phrase, meaning, examples, dialogue, play_count, created_at " +
                "FROM phrase WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        )
        try {
            return if (cursor.moveToFirst()) read(cursor) else null
        } finally {
            cursor.close()
        }
    }

    fun insert(p: Phrase): Long {
        val v = ContentValues()
        v.put("phrase", p.phrase)
        v.put("meaning", p.meaning)
        v.put("examples", encodeExamples(p.examples))
        v.put("dialogue", encodeLines(p.dialogue))
        v.put("play_count", p.playCount)
        v.put("created_at", if (p.createdAt > 0L) p.createdAt else System.currentTimeMillis())
        return writableDatabase.insert("phrase", null, v)
    }

    /** 补全中文后回写内容，id / 播放次数 / 创建时间保持不变。 */
    fun updateContent(id: Long, meaning: String, examples: List<Example>, dialogue: List<Line>) {
        val v = ContentValues()
        v.put("meaning", meaning)
        v.put("examples", encodeExamples(examples))
        v.put("dialogue", encodeLines(dialogue))
        writableDatabase.update("phrase", v, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("phrase", "id=?", arrayOf(id.toString()))
    }

    fun bumpPlay(ids: List<Long>) {
        if (ids.isEmpty()) return
        val list = ids.joinToString(",") { it.toString() }
        writableDatabase.execSQL(
            "UPDATE phrase SET play_count = play_count + 1 WHERE id IN ($list)"
        )
    }

    fun count(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM phrase", null)
        try {
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } finally {
            cursor.close()
        }
    }

    /** 第一次打开时塞几条示例，方便立刻试听整条链路。 */
    fun seedIfEmpty() {
        if (count() > 0) return
        insert(
            Phrase(
                phrase = "Forgive me for",
                meaning = "请原谅我……（为某事道歉）",
                examples = listOf(
                    Example("Forgive me for being late.", "请原谅我迟到了。"),
                    Example(
                        "Forgive me for asking, but is this seat taken?",
                        "不好意思问一句，这个位子有人吗？"
                    )
                ),
                dialogue = listOf(
                    Line(
                        "A",
                        "Forgive me for interrupting. Do you have a minute?",
                        "抱歉打断一下，你有空吗？"
                    ),
                    Line("B", "Sure, what's up?", "当然，怎么了？"),
                    Line("A", "Could you send me the report by Friday?", "周五前能把报告发我吗？"),
                    Line("B", "No problem, I'll get it to you.", "没问题，我发给你。")
                )
            )
        )
        insert(
            Phrase(
                phrase = "I'm swamped",
                meaning = "我忙得不可开交",
                examples = listOf(
                    Example(
                        "I'm swamped this week, can we push it to Monday?",
                        "我这周忙疯了，能推到周一吗？"
                    ),
                    Example(
                        "Sorry for the late reply — I've been swamped.",
                        "抱歉回得晚，我一直忙得脱不开身。"
                    )
                ),
                dialogue = listOf(
                    Line("A", "Want to grab lunch?", "要不要一起去吃午饭？"),
                    Line("B", "Can't today. I'm swamped.", "今天不行，我忙不过来。"),
                    Line("A", "No worries, tomorrow then.", "没事，那明天。"),
                    Line("B", "Count me in.", "算我一个。")
                )
            )
        )
    }

    /** 列顺序必须和上面两条 SQL 的 SELECT 一致。 */
    private fun read(cursor: Cursor): Phrase = Phrase(
        id = cursor.getLong(0),
        phrase = cursor.getString(1) ?: "",
        meaning = cursor.getString(2) ?: "",
        examples = decodeExamples(cursor.getString(3)),
        dialogue = decodeLines(cursor.getString(4)),
        playCount = cursor.getInt(5),
        createdAt = cursor.getLong(6)
    )

    // ---------- JSON 编解码 ----------

    private fun encodeExamples(items: List<Example>): String {
        val arr = JSONArray()
        for (e in items) arr.put(JSONObject().put("t", e.text).put("c", e.cn))
        return arr.toString()
    }

    private fun encodeLines(lines: List<Line>): String {
        val arr = JSONArray()
        for (line in lines) {
            arr.put(JSONObject().put("s", line.speaker).put("t", line.text).put("c", line.cn))
        }
        return arr.toString()
    }

    /** 老版本存的是纯字符串数组，这里兼容：读不出对象就当成没翻译的例句。 */
    private fun decodeExamples(raw: String?): List<Example> {
        val out = ArrayList<Example>()
        if (raw.isNullOrBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    val t = o.optString("t", "")
                    if (t.isNotBlank()) out.add(Example(t, o.optString("c", "")))
                } else {
                    val s = arr.optString(i, "")
                    if (s.isNotBlank()) out.add(Example(s, ""))
                }
            }
        } catch (ignored: Exception) {
            // 数据坏了就当没有例句，不让整个列表打不开
        }
        return out
    }

    private fun decodeLines(raw: String?): List<Line> {
        val out = ArrayList<Line>()
        if (raw.isNullOrBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "")
                if (t.isBlank()) continue
                out.add(Line(o.optString("s", "A"), t, o.optString("c", "")))
            }
        } catch (ignored: Exception) {
        }
        return out
    }
}
