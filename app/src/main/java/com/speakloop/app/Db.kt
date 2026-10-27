package com.speakloop.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单文件 SQLite。例句和对话序列化成 JSON 存在一行里，
 * 这样不用建三张表，也就没有跨表一致性问题。
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
            while (cursor.moveToNext()) {
                out.add(
                    Phrase(
                        id = cursor.getLong(0),
                        phrase = cursor.getString(1) ?: "",
                        meaning = cursor.getString(2) ?: "",
                        examples = decodeList(cursor.getString(3)),
                        dialogue = decodeLines(cursor.getString(4)),
                        playCount = cursor.getInt(5),
                        createdAt = cursor.getLong(6)
                    )
                )
            }
        } finally {
            cursor.close()
        }
        return out
    }

    fun insert(p: Phrase): Long {
        val v = ContentValues()
        v.put("phrase", p.phrase)
        v.put("meaning", p.meaning)
        v.put("examples", encodeList(p.examples))
        v.put("dialogue", encodeLines(p.dialogue))
        v.put("play_count", p.playCount)
        v.put("created_at", if (p.createdAt > 0L) p.createdAt else System.currentTimeMillis())
        return writableDatabase.insert("phrase", null, v)
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
                    "Forgive me for being late.",
                    "Forgive me for asking, but is this seat taken?"
                ),
                dialogue = listOf(
                    Line("A", "Forgive me for interrupting. Do you have a minute?"),
                    Line("B", "Sure, what's up?"),
                    Line("A", "Could you send me the report by Friday?")
                )
            )
        )
        insert(
            Phrase(
                phrase = "I'm swamped",
                meaning = "我忙得不可开交",
                examples = listOf(
                    "I'm swamped this week, can we push it to Monday?",
                    "Sorry for the late reply — I've been swamped."
                ),
                dialogue = listOf(
                    Line("A", "Want to grab lunch?"),
                    Line("B", "Can't today. I'm swamped."),
                    Line("A", "No worries, tomorrow then.")
                )
            )
        )
    }

    private fun encodeList(items: List<String>): String {
        val arr = JSONArray()
        for (s in items) arr.put(s)
        return arr.toString()
    }

    private fun decodeList(raw: String?): List<String> {
        val out = ArrayList<String>()
        if (raw.isNullOrBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) out.add(s)
            }
        } catch (ignored: Exception) {
            // 数据坏了就当没例句，不让整个列表打不开
        }
        return out
    }

    private fun encodeLines(lines: List<Line>): String {
        val arr = JSONArray()
        for (line in lines) {
            val o = JSONObject()
            o.put("s", line.speaker)
            o.put("t", line.text)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decodeLines(raw: String?): List<Line> {
        val out = ArrayList<Line>()
        if (raw.isNullOrBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Line(o.optString("s", "A"), o.optString("t", "")))
            }
        } catch (ignored: Exception) {
        }
        return out
    }
}
