package com.appathy.okoshi

import android.content.Context

/** SAF で選んだ出力フォルダの URI を保持するだけ。 */
object Prefs {
    private const val PREF = "okoshi"
    private const val K_TREE = "tree"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun tree(ctx: Context): String? = p(ctx).getString(K_TREE, null)

    fun saveTree(ctx: Context, uri: String) {
        p(ctx).edit().putString(K_TREE, uri).apply()
    }
}
