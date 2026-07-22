package com.appathy.okoshi

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 録音は必ずフォアグラウンドサービスで行う。
 * Activity 内で録音すると画面を離れた瞬間に切られる。
 *
 * 上限5分。到達したら自動停止する。
 */
class RecService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val MAX_SECONDS = 300
        private const val CH = "rec"
        private const val NOTI = 1

        @Volatile var running = false; private set
        @Volatile var elapsed = 0; private set
        @Volatile var level = 0f; private set
        @Volatile var lastFile: String? = null; private set
    }

    private var stopFlag = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFlag = true
                return START_NOT_STICKY
            }
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        if (running) return
        createChannel()
        startForeground(NOTI, noti("録音中 0:00"))
        running = true
        stopFlag = false
        elapsed = 0

        thread {
            var writer: WavWriter? = null
            var rec: AudioRecord? = null
            val tmp = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
            try {
                val min = AudioRecord.getMinBufferSize(
                    WavWriter.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(min, WavWriter.SAMPLE_RATE / 2)

                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WavWriter.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord の初期化に失敗")
                }

                writer = WavWriter(tmp)
                rec.startRecording()

                val buf = ByteArray(bufSize)
                var lastNoti = -1

                while (!stopFlag) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    writer.write(buf, n)
                    level = rms(buf, n)
                    elapsed = writer.seconds()
                    if (elapsed != lastNoti) {
                        lastNoti = elapsed
                        notify(noti("録音中 ${fmt(elapsed)}"))
                    }
                    if (elapsed >= MAX_SECONDS) break
                }

                rec.stop()
                writer.close()
                writer = null

                val dest = Storage.publish(this, tmp)
                lastFile = dest
            } catch (e: Exception) {
                lastFile = "ERROR:${e.message}"
            } finally {
                try { writer?.close() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
                tmp.delete()
                running = false
                level = 0f
                stopSelfSafely()
            }
        }
    }

    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        var c = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += (v * v).toDouble()
            i += 2
            c++
        }
        if (c == 0) return 0f
        return (sqrt(sum / c) / 32768.0).toFloat()
    }

    private fun fmt(s: Int) = String.format("%d:%02d", s / 60, s % 60)

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "録音", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun noti(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH) else Notification.Builder(this)
        return b.setContentTitle("面談録音")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI, n)
    }
}
