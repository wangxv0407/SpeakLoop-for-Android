package com.speakloop.app

/** 一条短语。examples 是例句列表，dialogue 是情景对话（说话人 + 内容）。 */
data class Phrase(
    val id: Long = 0L,
    val phrase: String = "",
    val meaning: String = "",
    val examples: List<String> = emptyList(),
    val dialogue: List<Line> = emptyList(),
    val playCount: Int = 0,
    val createdAt: Long = 0L
)

data class Line(val speaker: String, val text: String)
