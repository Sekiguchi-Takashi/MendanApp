package com.appathy.okoshi

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * whisper.cpp が要求する 16kHz / モノラル / PCM16 で直接書き出す。
 * こうしておくと Termux 側の ffmpeg 変換が実質パススルーになり、
 * 5分の音声で数十秒ぶんの処理時間を節約できる。
 *
 * サイズは 16000 * 2 byte = 32KB/秒。5分で約9.6MB。
 */
class WavWriter(private val file: File) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BITS = 16
        private const val HEADER = 44
    }

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER)) // 後でサイズを埋める
    }

    fun write(buf: ByteArray, len: Int) {
        raf.write(buf, 0, len)
        dataBytes += len
    }

    /** ヘッダを確定させて閉じる。落ちた場合でも finalize で復旧できるようにする。 */
    fun close() {
        try {
            raf.seek(0)
            raf.write(header(dataBytes))
        } finally {
            raf.close()
        }
    }

    fun seconds(): Int = (dataBytes / (SAMPLE_RATE * CHANNELS * (BITS / 8))).toInt()

    private fun header(data: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + data).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)                       // PCM
        b.putShort(CHANNELS.toShort())
        b.putInt(SAMPLE_RATE)
        b.putInt(byteRate)
        b.putShort((CHANNELS * BITS / 8).toShort())
        b.putShort(BITS.toShort())
        b.put("data".toByteArray())
        b.putInt(data.toInt())
        return b.array()
    }
}
