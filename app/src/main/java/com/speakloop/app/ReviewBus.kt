package com.speakloop.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 播放状态的单点转播。
 *
 * [ReviewService] 是前台服务，复习页是 Activity，两者生命周期不同步，
 * 而界面又需要知道「现在读到哪句、留白还剩几秒」才能画圆环倒数。
 *
 * 不用 LiveData / ViewModel：那要引 lifecycle 组件，而这里要传的只是一份不可变快照，
 * 一个 @Volatile 字段 + 一组回调就够了。回调都在主线程（服务用主线程 handler 发布），
 * 所以监听方可以直接改 View。
 */
object ReviewBus {

    /** 一次发布就是一次完整状态，界面不需要自己做差量判断。 */
    data class State(
        /** 服务在跑（可能处于暂停） */
        val running: Boolean = false,
        val paused: Boolean = false,
        /** 当前正在读（或刚读完）的句子 */
        val text: String = "",
        val cn: String = "",
        /** true = 朗读结束进入留白，等你自己读 */
        val gap: Boolean = false,
        /** 留白剩余毫秒，只有 gap 为 true 时有意义 */
        val remainMs: Long = 0,
        /** 留白总长，用来算圆环进度 */
        val gapTotalMs: Long = 0,
        /** 这一句属于第几条短语（1 起） */
        val phraseIndex: Int = 0,
        /** 本轮共几条短语 */
        val phraseTotal: Int = 0,
        /** 当前短语原文，用于页面上始终显示「在练哪一条」 */
        val phrase: String = "",
        /** 这一轮的范围说明，例如「今日 3 + 待复习 5」 */
        val scopeLabel: String = "",
        /** 第几句 / 共几句（整轮） */
        val stepIndex: Int = 0,
        val stepTotal: Int = 0
    )

    interface Listener {
        fun onReviewState(s: State)
    }

    @Volatile
    var state: State = State()
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun add(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        l.onReviewState(state)
    }

    fun remove(l: Listener) {
        listeners.remove(l)
    }

    fun publish(s: State) {
        state = s
        for (l in listeners) l.onReviewState(s)
    }

    /** 服务停掉时清空，避免下次进复习页还显示上一轮的字。 */
    fun reset() = publish(State())
}
