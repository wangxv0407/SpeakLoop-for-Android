package com.speakloop.app

/** 一条例句：英文原文 + 中文意思。cn 为空表示这条还没翻译（老数据就是这样）。 */
data class Example(val text: String, val cn: String = "")

/** 情景对话的一句：说话人 + 英文 + 中文。 */
data class Line(val speaker: String, val text: String, val cn: String = "")

/** 一条短语。 */
data class Phrase(
    val id: Long = 0L,
    val phrase: String = "",
    val meaning: String = "",
    val examples: List<Example> = emptyList(),
    val dialogue: List<Line> = emptyList(),
    val playCount: Int = 0,
    val createdAt: Long = 0L
)
