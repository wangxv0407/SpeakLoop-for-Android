package com.speakloop.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 有短语的某一天，给日期选择器用。 */
data class DayCount(val date: LocalDate, val count: Int)

/**
 * 单文件 SQLite。
 *
 * 例句和对话序列化成 JSON 存在 `phrase` 一行里，不用建三张表，也就没有跨表一致性问题。
 * 中文翻译也塞在同一个 JSON 里，所以加翻译不用升版本，老数据解码时按「没有翻译」处理。
 *
 * v2 加了 `play_log`：每次真正读到某条短语就记一行「哪天练了它几次」。
 * 这张表是复习排队、热力图、每日目标的共同数据源，
 * 也是 [Phrase.playCount] 的正确来源（它现在是「练过的轮数」，不再是模糊的累计值）。
 */
class Db(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "speakloop.db", null, VERSION) {

    companion object {
        const val VERSION = 2

        /** 列顺序必须和 [read] 里的下标一致。 */
        private const val SELECT_COLS =
            "SELECT id, phrase, meaning, examples, dialogue, play_count, created_at"

        private const val ORDER_ALL =
            "$SELECT_COLS FROM phrase ORDER BY play_count ASC, created_at ASC"
    }

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
        createPlayLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2：只加一张表，原有短语一条不动
        if (oldVersion < 2) createPlayLog(db)
    }

    /** 一条短语一天一行，`times` 是那天读了几次。 */
    private fun createPlayLog(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS play_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "phrase_id INTEGER NOT NULL," +
                "day TEXT NOT NULL," +
                "times INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_play_log_day ON play_log(day)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_play_log_phrase_day " +
                "ON play_log(phrase_id, day)"
        )
    }

    /** 全部短语，按「播得少在前，同次数按创建时间」排。只给「全部」范围用。 */
    fun all(): List<Phrase> = query(ORDER_ALL, null)

    /** 详情页按 id 取一条。返回 null 表示这条已经被删了。 */
    fun byId(id: Long): Phrase? {
        val list = query("$SELECT_COLS FROM phrase WHERE id = ? LIMIT 1", arrayOf(id.toString()))
        return list.firstOrNull()
    }

    /**
     * 某一天新加的短语，按创建时间正序。
     * 日期边界用毫秒传进来，由调用方按本地时区算，避免 SQL 里踩时区。
     */
    fun addedOn(startMs: Long, endMs: Long): List<Phrase> = query(
        "$SELECT_COLS FROM phrase WHERE created_at >= ? AND created_at < ? " +
            "ORDER BY created_at ASC",
        arrayOf(startMs.toString(), endMs.toString())
    )

    /** 从没练过的（play_count = 0），最老的在前。这是「含待复习」补进来的那部分。 */
    fun neverPlayed(): List<Phrase> = query(
        "$SELECT_COLS FROM phrase WHERE play_count = 0 ORDER BY created_at ASC", null
    )

    /** 有短语的日期，从新到旧。日期选择器只列这些天，避免满屏空白。 */
    fun daysWithPhrases(): List<DayCount> {
        val out = ArrayList<DayCount>()
        val cursor = readableDatabase.rawQuery(
            "SELECT created_at FROM phrase ORDER BY created_at DESC", null
        )
        val zone = ZoneId.systemDefault()
        val counter = LinkedHashMap<LocalDate, Int>()
        try {
            while (cursor.moveToNext()) {
                val d = Instant.ofEpochMilli(cursor.getLong(0)).atZone(zone).toLocalDate()
                counter[d] = (counter[d] ?: 0) + 1
            }
        } finally {
            cursor.close()
        }
        for ((d, n) in counter) out.add(DayCount(d, n))
        return out
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

    /** 改内容（补全中文、手改例句都走这里），id / 播放次数 / 创建时间不动。 */
    fun updateContent(id: Long, meaning: String, examples: List<Example>, dialogue: List<Line>) {
        val v = ContentValues()
        v.put("meaning", meaning)
        v.put("examples", encodeExamples(examples))
        v.put("dialogue", encodeLines(dialogue))
        writableDatabase.update("phrase", v, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        val db = writableDatabase
        db.delete("phrase", "id=?", arrayOf(id.toString()))
        db.delete("play_log", "phrase_id=?", arrayOf(id.toString()))
    }

    /**
     * 真的读到这条短语了（不是「按了开始」）：次数 +1，并在今天的 play_log 里记一笔。
     *
     * v1 是在开播时给全表 bumpPlay，等于一句没读所有条都 +1，
     * 列表上那个「12 次」和排序优先级从那时起就是错的。现在改成逐条记账。
     */
    fun markPlayed(phraseId: Long, day: String) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE phrase SET play_count = play_count + 1 WHERE id = ?",
            arrayOf(phraseId)
        )
        // SQLite 3.24 才有 UPSERT，minSdk 26 的机器可能还跑在 3.18 上，手工两步更稳
        val existed = DatabaseUtils.longForQuery(
            db, "SELECT COUNT(*) FROM play_log WHERE phrase_id = ? AND day = ?",
            arrayOf(phraseId.toString(), day)
        ) > 0
        if (existed) {
            db.execSQL(
                "UPDATE play_log SET times = times + 1 WHERE phrase_id = ? AND day = ?",
                arrayOf(phraseId, day)
            )
        } else {
            db.execSQL(
                "INSERT INTO play_log(phrase_id, day, times) VALUES(?, ?, 1)",
                arrayOf(phraseId, day)
            )
        }
    }

    /** 某天练了几条不同的短语，给「今日已练 N 条」和热力图用。 */
    fun playedCountOn(day: String): Int = DatabaseUtils.longForQuery(
        readableDatabase, "SELECT COUNT(*) FROM play_log WHERE day = ?", arrayOf(day)
    ).toInt()

    /** 某天练过的短语 id，用来算「今天还有哪些没练」。 */
    fun playedIdsOn(day: String): Set<Long> {
        val out = HashSet<Long>()
        val cursor = readableDatabase.rawQuery(
            "SELECT phrase_id FROM play_log WHERE day = ?", arrayOf(day)
        )
        try {
            while (cursor.moveToNext()) out.add(cursor.getLong(0))
        } finally {
            cursor.close()
        }
        return out
    }

    /** 区间内每天练了几条：day(YYYY-MM-DD) → 条数。热力图直接拿它铺格子。 */
    fun playCountByDay(fromDay: String, toDay: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        val cursor = readableDatabase.rawQuery(
            "SELECT day, COUNT(*) FROM play_log WHERE day >= ? AND day <= ? GROUP BY day",
            arrayOf(fromDay, toDay)
        )
        try {
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getInt(1)
        } finally {
            cursor.close()
        }
        return out
    }

    /** 区间内练过的不同短语总数。 */
    fun distinctPlayedBetween(fromDay: String, toDay: String): Int = DatabaseUtils.longForQuery(
        readableDatabase,
        "SELECT COUNT(DISTINCT phrase_id) FROM play_log WHERE day >= ? AND day <= ?",
        arrayOf(fromDay, toDay)
    ).toInt()

    /** 区间内练得最多的几条短语。 */
    fun topPlayed(fromDay: String, toDay: String, limit: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val cursor = readableDatabase.rawQuery(
            "SELECT p.phrase, COUNT(l.id) AS n FROM play_log l " +
                "JOIN phrase p ON p.id = l.phrase_id " +
                "WHERE l.day >= ? AND l.day <= ? GROUP BY l.phrase_id " +
                "ORDER BY n DESC, p.phrase ASC LIMIT $limit",
            arrayOf(fromDay, toDay)
        )
        try {
            while (cursor.moveToNext()) out.add(cursor.getString(0) to cursor.getInt(1))
        } finally {
            cursor.close()
        }
        return out
    }

    /** 连续记录天数：从今天（今天还没练就从昨天）往前数，每天都有练习。 */
    fun streakDays(today: LocalDate): Int {
        val days = HashSet<String>()
        val cursor = readableDatabase.rawQuery("SELECT DISTINCT day FROM play_log", null)
        try {
            while (cursor.moveToNext()) days.add(cursor.getString(0))
        } finally {
            cursor.close()
        }
        if (days.isEmpty()) return 0
        var cursor2 = if (days.contains(today.toString())) today else today.minusDays(1)
        var n = 0
        while (days.contains(cursor2.toString())) {
            n++
            cursor2 = cursor2.minusDays(1)
        }
        return n
    }

    fun count(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM phrase", null)
        try {
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } finally {
            cursor.close()
        }
    }

    // ---------- 内部 ----------

    private fun query(sql: String, args: Array<String>?): List<Phrase> {
        val out = ArrayList<Phrase>()
        val cursor = readableDatabase.rawQuery(sql, args)
        try {
            while (cursor.moveToNext()) out.add(read(cursor))
        } finally {
            cursor.close()
        }
        return out
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
