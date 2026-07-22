package com.appathy.okoshi

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Termux から読める場所へ置くのが唯一の要件。
 * アプリ専用領域 (/Android/data/...) は Android 11 以降 Termux から読めないため、
 * 共有ストレージの Download/okoshi_in へ公開する。
 *
 * Termux 側のパス:  ~/storage/downloads/okoshi_in/
 */
object Storage {

    const val SUBDIR = "okoshi_in"
    const val TERMUX_DIR = "/storage/emulated/0/Download/$SUBDIR"

    fun name(): String =
        "mendan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".wav"

    /** 一時ファイルを共有ストレージへコピーし、Termux から見える絶対パスを返す。 */
    fun publish(ctx: Context, tmp: File): String {
        val fileName = name()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("MediaStore への登録に失敗")
            cr.openOutputStream(uri)!!.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            cr.update(uri, cv, null, null)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUBDIR
            )
            dir.mkdirs()
            tmp.copyTo(File(dir, fileName), overwrite = true)
        }

        return "$TERMUX_DIR/$fileName"
    }
}
