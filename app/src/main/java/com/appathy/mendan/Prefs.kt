package com.appathy.mendan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 保持するのは作業フォルダだけ。
 *
 * 作業フォルダは共有ストレージ上の1箇所（例: Download/okoshi）。
 * アプリは SAF でここを読み書きし、Termux は同じ実パスを読み書きする。
 * こうすることで、毎回フォルダを選ぶ手間なく両者が同じ場所を共有できる。
 */
object Prefs {
    private const val PREF = "mendan"
    private const val K_TREE = "work_tree"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun tree(ctx: Context): String? = p(ctx).getString(K_TREE, null)

    fun saveTree(ctx: Context, uri: String) {
        p(ctx).edit().putString(K_TREE, uri).apply()
    }

    /**
     * SAF ツリー URI を Termux が読める実パスへ変換する。
     * primary ボリューム限定。docId "primary:Download/okoshi" を
     * "/storage/emulated/0/Download/okoshi" に写像する。
     * SD カードなど非 primary は対象外（null を返す）。
     */
    fun fsPath(ctx: Context): String? {
        val t = tree(ctx) ?: return null
        return try {
            val docId = DocumentsContract.getTreeDocumentId(Uri.parse(t))
            val parts = docId.split(':', limit = 2)
            if (parts.size != 2 || parts[0] != "primary") return null
            "/storage/emulated/0/" + parts[1]
        } catch (e: Exception) {
            null
        }
    }

    /** Termux ホームから見た同じ場所（~/storage/downloads/... 等）。表示用。 */
    fun termuxHint(ctx: Context): String? {
        val fs = fsPath(ctx) ?: return null
        return fs.replace("/storage/emulated/0/", "~/storage/shared/")
    }
}
