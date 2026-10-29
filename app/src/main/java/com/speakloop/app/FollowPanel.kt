package com.speakloop.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 跟读面板：正在听（波形 + 计时 + 取消/停下）+ 结果（指标 + 回放）。
 *
 * 全部本地能力：AudioRecord 采集、RMS 算波形、WAV 回放。
 * 不依赖系统语音识别（华为假可用）、不依赖网络（骑行弱网）。
 *
 * 骑车时双手在车把上，所以「说完自动收尾」是主路径（[FollowReader.onSpeechEnd]），
 * 按钮只在停下等红灯时用。
 */
class FollowPanel(
    private val host: Activity,
    /** 复习流里用：跟读结束后让服务立刻往下一句走 */
    private val onAdvance: () -> Unit = {}
) {

    companion object {
        /** 说完静音这么久自动进结果，避免一直录下去把整轮的节奏拖死 */
        private const val RESULT_LINGER_MS = 4500L

        private const val REQ_AUDIO = 4021
    }

    val view: View

    private lateinit var title: TextView
    private lateinit var wave: WaveBars
    private lateinit var timer: TextView
    private lateinit var btnRow: LinearLayout
    private lateinit var cancelBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var body: TextView

    /** 面板自己的主线程 Handler：hideResult 的延时收起要靠它，Activity 上没有这些方法 */
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private val reader = FollowReader(host)
    private var player: MediaPlayer? = null

    private var phraseId: Long = -1L
    private var words = 0
    private var listening = false
    private var lastResult: FollowReader.Result? = null

    private val hideResult = Runnable { hide() }

    init {
        view = build()
        reader.onLevel = { lv -> if (listening) wave.push(lv) }
        reader.onTick = { ms -> if (listening) timer.text = clock(ms) }
        // 静音足够久 + 确实说过话 → 自动收尾并直接往下一句走。
        // 骑车时双手在车把上，这条才是主路径，按钮只在等红灯时用
        reader.onSpeechEnd = { if (listening) finishNow() }
    }

    /** 面板是否正在录音。宿主用它决定要不要接管留白推进。 */
    val isListening: Boolean get() = listening

    /** 正在展示结果：宿主不要用新的留白把它顶掉 */
    var showingResult = false
        private set

    // ---------- 骨架 ----------

    private fun build(): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = Ui.card(host, 22)
            visibility = View.GONE
        }

        title = Ui.text(host, "正在听… 请跟读", Ui.T.SUB, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        }
        card.addView(title, match())

        wave = WaveBars(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ).apply { topMargin = dp(10) }
        }
        card.addView(wave)

        timer = Ui.text(host, "0:00", Ui.T.H1, Ui.GOOD, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            letterSpacing = -0.01f
        }
        card.addView(timer)

        // 结果态的说明文字，听的时候藏着
        body = Ui.text(host, "", Ui.T.SUB, Ui.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        card.addView(body)

        btnRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        cancelBtn = actionButton("取消", primary = false) { cancel() }
        stopBtn = actionButton("停下，看结果", primary = true) { finishNow() }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnRow.addView(
            stopBtn,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) }
        )
        card.addView(btnRow)
        return card
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val fg = if (primary) Ui.ON_PLAY else Ui.DIM
        return Ui.text(host, label, Ui.T.BODY, fg, primary).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = if (primary) {
                Ui.ripple(host, 24, Ui.GOOD, 0, Ui.STROKE)
            } else {
                Ui.ripple(host, 24, Ui.SURFACE_2, 1, Ui.STROKE)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int) = Ui.dp(host, v)

    // ---------- 对外 ----------

    /**
     * 开始跟读。[text] 只用来数词估语速，不做识别。
     */
    fun show(phraseId: Long, text: String) {
        this.phraseId = phraseId
        words = Diff.normalize(text).size
        ui.removeCallbacks(hideResult)
        view.visibility = View.VISIBLE

        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showNoPermission()
            return
        }
        startListening()
    }

    /** 重新录一遍（面板上的按钮） */
    fun retry() {
        if (phraseId < 0L) return
        startListening()
    }

    fun hide() {
        ui.removeCallbacks(hideResult)
        showingResult = false
        if (listening) {
            listening = false
            reader.cancel()
        }
        stopPlayback()
        view.visibility = View.GONE
    }

    fun release() {
        ui.removeCallbacks(hideResult)
        listening = false
        reader.release()
        stopPlayback()
    }

    // ---------- 录音 ----------

    private fun startListening() {
        stopPlayback()
        ui.removeCallbacks(hideResult)
        listening = true
        showingResult = false
        lastResult = null

        title.text = "正在听… 请跟读"
        title.setTextColor(Ui.MUTED)
        timer.text = "0:00"
        timer.setTextColor(Ui.GOOD)
        timer.visibility = View.VISIBLE
        body.visibility = View.GONE
        wave.reset()
        wave.visibility = View.VISIBLE
        cancelBtn.text = "取消"
        stopBtn.text = "停下，看结果"
        stopBtn.setTextColor(Ui.ON_PLAY)
        stopBtn.background = Ui.ripple(host, 24, Ui.GOOD, 0, Ui.STROKE)
        // 结果态会把这两个监听改成「再读一遍 / 回放」，回到录音态要换回来
        cancelBtn.setOnClickListener { cancel() }
        stopBtn.setOnClickListener { finishNow() }

        if (!reader.start(phraseId)) {
            listening = false
            title.text = "麦克风启动失败"
            title.setTextColor(Ui.WARN)
            timer.visibility = View.GONE
            wave.visibility = View.GONE
            body.text = "可能有别的应用正在占用麦克风，关掉录音/通话类应用再试。"
            body.setTextColor(Ui.WARN)
            body.visibility = View.VISIBLE
            cancelBtn.text = "关闭"
            stopBtn.text = "重试"
            cancelBtn.setOnClickListener { hide() }
            stopBtn.setOnClickListener { retry() }
        }
    }

    private fun showNoPermission() {
        listening = false
        showingResult = false
        title.text = "需要麦克风权限"
        title.setTextColor(Ui.WARN)
        timer.visibility = View.GONE
        wave.visibility = View.GONE
        body.text = "跟读只在这台手机上录音和分析，不上传。点「允许」后即可开始。"
        body.setTextColor(Ui.DIM)
        body.visibility = View.VISIBLE
        cancelBtn.text = "关闭"
        stopBtn.text = "允许"
        stopBtn.setTextColor(Ui.ON_PLAY)
        stopBtn.background = Ui.ripple(host, 24, Ui.GOOD, 0, Ui.STROKE)
        // 用户主动点「允许」时不受 10 秒限流约束
        cancelBtn.setOnClickListener { hide() }
        stopBtn.setOnClickListener {
            permAskedAt = 0L
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }

        // 每句留白都会走到这里，10 秒内只弹一次系统权限框，别把屏幕刷爆
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - permAskedAt > 10000L) {
            permAskedAt = now
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    private var permAskedAt = 0L

    /** 权限弹窗回来后由宿主转发。[requestCode] 要对上，否则通知权限的回执会误触发录音。 */
    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode != REQ_AUDIO) return
        if (view.visibility != View.VISIBLE) return
        if (granted) {
            if (phraseId >= 0L) startListening()
        } else {
            showNoPermission()
        }
    }

    /**
     * 停止录音并出结果。说完自动收尾和点「停下」走同一条路径，
     * 都会立刻让复习流往下一句走 —— 停下就是为了继续下一句，没必要再干等剩下的留白。
     */
    fun finishNow() {
        if (!listening) return
        listening = false
        val r = reader.stop()
        lastResult = r
        renderResult(r)
        onAdvance()
        ui.removeCallbacks(hideResult)
        ui.postDelayed(hideResult, RESULT_LINGER_MS)
    }

    private fun cancel() {
        if (listening) {
            listening = false
            reader.cancel()
            hide()
            return
        }
        // 结果态下「取消」= 关闭
        hide()
    }

    // ---------- 结果 ----------

    private fun renderResult(r: FollowReader.Result?) {
        showingResult = true
        title.text = "跟读结果"
        title.setTextColor(Ui.FG)
        wave.visibility = View.GONE
        body.visibility = View.VISIBLE

        if (r == null || r.ms < 300L || r.file == null) {
            timer.text = "没录到"
            timer.setTextColor(Ui.WARN)
            body.text = "这次没听到声音。检查麦克风是否被占用，或离麦克风近一点再试。"
            body.setTextColor(Ui.WARN)
        } else {
            val secs = String.format("%.1f", r.ms / 1000f)
            timer.text = "${secs}s"
            timer.setTextColor(Ui.GOOD)

            val ratio = (r.voicedRatio * 100).toInt()
            val speed = r.wpm(words)
            val f = StringBuilder()
            f.append("发声 ").append(ratio).append("% · 平均 ").append(r.avgDb.toInt()).append(" dB")
            if (speed > 0) f.append(" · 语速 ").append(speed).append(" wpm")
            f.append('\n').append(verdict(r, ratio, speed))
            body.text = f.toString()
            body.setTextColor(if (ratio < 25) Ui.WARN else Ui.DIM)
        }

        cancelBtn.text = "再读一遍"
        stopBtn.text = "回放对比"
        // 结果态两个按钮都换成次级：播放态才用绿色，避免「可以点」和「正在播」混在一起
        stopBtn.setTextColor(Ui.FG)
        stopBtn.background = Ui.ripple(host, 24, Ui.SURFACE_2, 1, Ui.STROKE)
        cancelBtn.setOnClickListener { retry() }
        stopBtn.setOnClickListener { playBack() }
    }

    /** 本地能给的最诚实的判断：说没说、说多久、快不快。不假装能判对错。 */
    private fun verdict(r: FollowReader.Result, ratio: Int, speed: Int): String = when {
        ratio < 25 -> "几乎没听到说话声，可能是没开口或离得太远。"
        speed in 60..220 && words > 0 -> "时长和语速都在正常范围，点「回放对比」自己听一遍最准。"
        speed > 220 -> "说得偏快，听不清尾音时容易漏词，试着放慢一点。"
        speed in 1..59 -> "说得偏慢或中间停顿较多，可以连贯一点。"
        else -> "录到了 ${r.ms / 1000} 秒，点「回放对比」自己听一遍。"
    }

    // ---------- 回放 ----------

    private fun playBack() {
        val f = lastResult?.file ?: run {
            android.widget.Toast.makeText(host, "这次没有录音", android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        stopPlayback()
        try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stopPlayback() }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            android.widget.Toast.makeText(host, "回放失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        return String.format("%d:%02d", total / 60, total % 60)
    }

    // ---------- 波形 ----------

    /**
     * 中间向外的对称波形：新值从右往左推进，像录音软件那样。
     * 33 根条足够看出「有没有在说话、音量起伏大不大」，再多就纯耗电。
     */
    private class WaveBars(c: Context) : View(c) {

        private val bars = FloatArray(33) { 0.06f }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun push(level: Float) {
            System.arraycopy(bars, 1, bars, 0, bars.size - 1)
            bars[bars.size - 1] = level.coerceIn(0.04f, 1f)
            invalidate()
        }

        fun reset() {
            for (i in bars.indices) bars[i] = 0.06f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = Ui.GOOD
            val n = bars.size
            val gap = Ui.dp(context, 3).toFloat()
            val barW = ((width - gap * (n - 1)) / n).coerceAtLeast(Ui.dp(context, 2).toFloat())
            val cy = height / 2f
            val maxH = height.toFloat()
            val minH = Ui.dp(context, 4).toFloat()
            val r = barW / 2f

            for (i in 0 until n) {
                val h = minH + (maxH - minH) * bars[i]
                val left = i * (barW + gap)
                canvas.drawRoundRect(
                    left, cy - h / 2f, left + barW, cy + h / 2f, r, r, paint
                )
            }
        }
    }
}
