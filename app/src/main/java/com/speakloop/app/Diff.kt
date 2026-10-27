package com.speakloop.app

/**
 * 逐词对比：用最长公共子序列把「应该说的」和「实际听到的」对齐，
 * 标出漏读（应说的没说到）和多读（说了原文没有的）。
 *
 * 纯算法，不碰 Android API，逻辑可以单独推演。
 */
object Diff {

    const val OK = 0
    const val MISSING = 1
    const val EXTRA = 2

    data class Token(val text: String, val status: Int)

    /** 小写、去掉标点、按空白切词。keeping 下 ' 以便 don't 不被拆成两半。 */
    fun normalize(raw: String): List<String> {
        val cleaned = raw.lowercase().map { ch ->
            if (ch.isLetterOrDigit() || ch == '\'') ch else ' '
        }.joinToString("")
        return cleaned.split(' ').filter { it.isNotBlank() }
    }

    fun align(ref: String, heard: String): List<Token> {
        val a = normalize(ref)
        val b = normalize(heard)
        val n = a.size
        val m = b.size

        // dp[i][j] = a[i..] 和 b[j..] 的 LCS 长度
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val out = ArrayList<Token>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(Token(a[i], OK))
                    i++
                    j++
                }

                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(Token(a[i], MISSING))
                    i++
                }

                else -> {
                    out.add(Token(b[j], EXTRA))
                    j++
                }
            }
        }
        while (i < n) {
            out.add(Token(a[i], MISSING))
            i++
        }
        while (j < m) {
            out.add(Token(b[j], EXTRA))
            j++
        }
        return out
    }

    /** 命中率，用来给一句「对了百分之多少」。 */
    fun accuracy(tokens: List<Token>): Int {
        if (tokens.isEmpty()) return 0
        val hit = tokens.count { it.status == OK }
        return (hit * 100 / tokens.size)
    }
}
