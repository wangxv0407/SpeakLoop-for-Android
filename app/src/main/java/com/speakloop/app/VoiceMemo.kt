package com.speakloop.app

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 跟读录音：每条短语一个 m4a，存在 App 私有目录里，覆盖式保存（只留最近一遍）。
 *
 * 不做历史录音列表，因为要管文件、管清理、管占用空间，收益却只是「听上周的自己」。
 * 覆盖式保存已经能满足「我刚才这句读得对不对」这个最常用的场景。
 */
class VoiceMemo(private val c: Context) {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    private fun dir(): File = File(c.filesDir, "memo").apply { if (!exists()) mkdirs() }

    fun fileOf(phraseId: Long): File = File(dir(), "p$phraseId.m4a")

    fun has(phraseId: Long): Boolean = fileOf(phraseId).let { it.exists() && it.length() > 0 }

    val recording: Boolean get() = recorder != null

    fun start(phraseId: Long): Boolean {
        stopRecording()
        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(c)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(fileOf(phraseId).absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (e: Exception) {
            recorder = null
            false
        }
    }

    fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (ignored: Exception) {
            // 太短就 stop 会抛，文件留个空壳也没事，播放前会检查长度
        }
        r.release()
    }

    fun play(phraseId: Long): Boolean {
        val f = fileOf(phraseId)
        if (!f.exists() || f.length() <= 0L) return false
        stopPlayer()
        return try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stopPlayer() }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            player = null
            false
        }
    }

    private fun stopPlayer() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (ignored: Exception) {
        }
        p.release()
    }

    fun release() {
        stopRecording()
        stopPlayer()
    }
}
