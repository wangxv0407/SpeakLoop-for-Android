package com.speakloop.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 跟读录音引擎：AudioRecord 采 16kHz 单声道 PCM16，边录边算 RMS 驱动波形，
 * 同时落一份 WAV（回放和以后喂离线识别共用同一份数据）。
 *
 * 为什么不用 MediaRecorder：它只吐压缩文件，拿不到实时振幅，画不出波形。
 * AudioRecord 是平台原生（懒惰阶梯第 4 级），华为上 100% 可用，
 * 不依赖系统识别服务、不依赖网络。
 *
 * 采样源用 VOICE_RECOGNITION 而不是 MIC：系统对它做过语音优化（抑制稳态噪声），
 * 骑车时的风噪会小一截。
 */
class FollowReader(private val c: Context) {

    /** 一次跟读的统计结果。全部是本地算出来的，没有任何服务参与。 */
    data class Result(
        val ms: Long,
        val voicedMs: Long,
        val avgDb: Float,
        val peakDb: Float,
        /** 录到的 WAV，回放用；启动失败时为 null */
        val file: File?
    ) {
        /** 有效发声占比：说话时间 / 总时长，太低说明没开口或只说了半个词 */
        val voicedRatio: Float get() = if (ms <= 0L) 0f else voicedMs.toFloat() / ms

        /** 按原文词数估语速。词数由调用方给，引擎自己不碰文本。 */
        fun wpm(words: Int): Int {
            if (ms < 500L || words <= 0) return 0
            return (words * 60000f / ms).toInt()
        }
    }

    companion object {
        const val SR = 16000

        /** 40ms 一帧：100ms 一次太粗（波形一卡一卡），20ms 又浪费电 */
        private const val FRAME_MS = 40L

        /** 判定「在说话」的门限。安静房间底噪约 -55dB，正常说话 -30~-15dB */
        private const val VOICE_DB = -45f

        /** 说完之后静音这么久就认为这句读完了 */
        private const val END_SILENCE_MS = 1200L

        /** 少于这个有效发声时长不算「说过话」，避免咳嗽一声就被判成读完 */
        private const val MIN_VOICE_MS = 400L

        /** 每条短语留几段录音。多到能听出「上次和这次的差别」，又不至于攒成垃圾堆 */
        const val KEEP_FILES = 3

        /**
         * 某条短语的录音文件，新的在前。
         *
         * 同时认两种命名：`f12_1730...wav` 是现在的按次留存，
         * `f12.wav` 是 v2.2.3 之前「一句一个文件、覆盖写」的老格式 ——
         * 一并列出，旧文件会自然排到最后被 [prune] 清掉，不用单独写迁移。
         */
        fun filesOf(c: Context, phraseId: Long): List<File> =
            File(c.filesDir, "follow").listFiles()
                ?.filter {
                    it.name.startsWith("f$phraseId_") || it.name.startsWith("f$phraseId.")
                }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

        /** 只留最近 [KEEP_FILES] 段，多余的删掉 */
        fun prune(c: Context, phraseId: Long) {
            filesOf(c, phraseId).drop(KEEP_FILES).forEach { it.delete() }
        }

        /** 短语被删掉时连录音一起清，别留没人引用的 wav */
        fun purge(c: Context, phraseId: Long) {
            filesOf(c, phraseId).forEach { it.delete() }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var rec: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var alive = false

    private var outFile: File? = null

    /** 实时归一振幅 0..1，主线程回调 */
    var onLevel: ((Float) -> Unit)? = null

    /** 已录毫秒，主线程回调 */
    var onTick: ((Long) -> Unit)? = null

    /** 说完自动收尾（静音够久且确实说过话）。骑车腾不出手点按钮，靠它兜底 */
    var onSpeechEnd: (() -> Unit)? = null

    val running: Boolean get() = alive

    private fun dir(): File = File(c.filesDir, "follow").apply { if (!exists()) mkdirs() }

    /** 每次跟读一个新文件，这样「上一次」才留得住；旧文件由 [prune] 按条数收掉。 */
    private fun newFile(phraseId: Long): File =
        File(dir(), "f${phraseId}_${System.currentTimeMillis()}.wav")

    /**
     * 开始录。返回 false 表示麦克风被占用或没权限——调用方要给出人话提示，
     * 不能让界面停在「正在听」却什么都没发生。
     */
    fun start(phraseId: Long): Boolean {
        if (alive) stop()
        // 必须清掉上一句的结果：start 失败时 stop() 会把它原样返回，
        // 面板就会把上一句的时长/占比当成这一句显示出来
        result = null
        val f = newFile(phraseId)
        outFile = f

        val frameSamples = (SR * FRAME_MS / 1000L).toInt()
        val minBytes = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBytes, frameSamples * 2 * 4)

        val r = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .build()
        } catch (e: Exception) {
            outFile = null
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            outFile = null
            return false
        }

        rec = r
        alive = true
        worker = Thread({ loop(r, frameSamples, f) }, "follow-record").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        return true
    }

    private fun loop(r: AudioRecord, frameSamples: Int, f: File) {
        var raf: RandomAccessFile? = null
        var samples: Long = 0
        var voicedMs = 0L
        var lastVoiceAt = 0L
        var speechEnded = false
        var dbSum = 0f
        var dbCount = 0
        var peakDb = -96f

        try {
            raf = RandomAccessFile(f, "rw")
            raf.setLength(0)
            writeHeader(raf, 0) // 先占位，停止时回填真实长度

            r.startRecording()
            val buf = ShortArray(frameSamples)
            val bytes = ByteArray(frameSamples * 2)

            while (alive) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue

                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    sum += (s * s).toDouble()
                }
                val rms = sqrt(sum / n)
                val db = if (rms <= 0.0) -96f else (20 * log10(rms / 32768.0)).toFloat()
                if (db > peakDb) peakDb = db
                dbSum += db
                dbCount++

                samples += n
                val elapsed = samples * 1000L / SR

                if (db > VOICE_DB) {
                    voicedMs += FRAME_MS
                    lastVoiceAt = elapsed
                } else if (!speechEnded &&
                    voicedMs >= MIN_VOICE_MS &&
                    elapsed - lastVoiceAt >= END_SILENCE_MS
                ) {
                    speechEnded = true
                    main.post { onSpeechEnd?.invoke() }
                }

                // WAV 里存小端
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                raf.write(bytes, 0, n * 2)

                val level = ((db + 55f) / 55f).coerceIn(0f, 1f)
                val total = elapsed
                main.post {
                    onLevel?.invoke(level)
                    onTick?.invoke(total)
                }
            }
        } catch (ignored: Exception) {
            // 麦克风被抢、录音被系统停掉都会走到这里，stop() 仍然会把已录的部分返回
        } finally {
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
            rec = null

            val dataLen = (samples * 2).toInt()
            if (raf != null) {
                runCatching {
                    raf.seek(0)
                    writeHeader(raf, dataLen)
                }
                runCatching { raf.close() }
            }
            result = Result(
                ms = samples * 1000L / SR,
                voicedMs = voicedMs,
                avgDb = if (dbCount > 0) dbSum / dbCount else -96f,
                peakDb = peakDb,
                file = if (dataLen > 44) f else null
            )
        }
    }

    @Volatile
    private var result: Result? = null

    /** 停止并拿到结果。没在录就返回上一次的结果（重入安全）。 */
    fun stop(): Result? {
        if (!alive) return result
        alive = false
        val t = worker
        worker = null
        runCatching { t?.join(1500) }
        return result
    }

    /** 丢弃，不产生结果（用户点「取消」）。 */
    fun cancel() {
        alive = false
        val t = worker
        worker = null
        runCatching { t?.join(800) }
        result = null
    }

    fun release() {
        cancel()
        main.removeCallbacksAndMessages(null)
    }

    /** 44 字节标准 WAV 头。采样率固定 16k / 单声道 / 16bit，所以只算长度就够。 */
    private fun writeHeader(raf: RandomAccessFile, dataLen: Int) {
        val byteRate = SR * 2
        val h = ByteArray(44)
        fun putAscii(off: Int, s: String) {
            for (i in s.indices) h[off + i] = s[i].code.toByte()
        }
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + dataLen)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)          // PCM
        putShort(22, 1)          // mono
        putInt(24, SR)
        putInt(28, byteRate)
        putShort(32, 2)          // block align
        putShort(34, 16)         // bits
        putAscii(36, "data")
        putInt(40, dataLen)
        raf.write(h, 0, h.size)
    }
}
