package com.appathy.mendan

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 出力先の選択（SAF）を廃止し、アプリ内部に保存する。
 * 毎回フォルダを指定する手間と、外部エディタでの開閉が不要になる。
 *
 *   filesDir/sessions/<セッションID>/001.txt, 002.txt ...
 *
 * 1ファイルだけの場合もセッションフォルダに入れる（構造を単純に保つため）。
 */
object Store {

    private const val PREF = "mendan"
    private const val K_ITEMS = "items"
    private const val K_LIMIT = "limit"
    private const val K_SESSION = "session"

    const val DEFAULT_LIMIT = 500

    private const val SAMPLE = """# 名称: 表記1, 表記2 ... の形式で記入
# 行頭の # はコメント。表記を省くと名称自身を検索します。
介護状況: 介護, ヘルパー, デイサービス, 訪問看護, ケアマネ
家族構成: 家族, 息子, 娘, 配偶者, 妻, 夫, 同居, 独居, 一人暮らし
経済状況: 収入, 年金, 生活費, 家計, 貯金, 生活保護
住環境: 住まい, 自宅, アパート, 段差, 手すり, 階段
健康状態: 通院, 服薬, 薬, 主治医, 病院, 持病
本人の意向: 希望, 望み, したい, 困って, 不安
"""

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun rawItems(ctx: Context): String = p(ctx).getString(K_ITEMS, SAMPLE) ?: SAMPLE

    fun saveItems(ctx: Context, text: String) {
        p(ctx).edit().putString(K_ITEMS, text).apply()
    }

    fun limit(ctx: Context): Int = p(ctx).getInt(K_LIMIT, DEFAULT_LIMIT)

    fun saveLimit(ctx: Context, v: Int) {
        p(ctx).edit().putInt(K_LIMIT, v).apply()
    }

    fun session(ctx: Context): String? = p(ctx).getString(K_SESSION, null)

    fun saveSession(ctx: Context, id: String) {
        p(ctx).edit().putString(K_SESSION, id).apply()
    }

    fun root(ctx: Context): File = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun newSessionId(): String =
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date())

    fun sessionDir(ctx: Context, id: String): File =
        File(root(ctx), id).apply { mkdirs() }

    /** 001.txt, 002.txt ... を順に返す。 */
    fun files(ctx: Context, id: String): List<File> =
        sessionDir(ctx, id).listFiles { f -> f.name.endsWith(".txt") }
            ?.sortedBy { it.name } ?: emptyList()

    fun parseItems(raw: String): List<Splitter.Item> {
        val out = ArrayList<Splitter.Item>()
        for (line0 in raw.split("\n")) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var i = line.indexOf(':')
            if (i < 0) i = line.indexOf('：')
            if (i < 0) { out.add(Splitter.Item(line, listOf(line))); continue }
            val name = line.substring(0, i).trim()
            if (name.isEmpty()) continue
            val forms = line.substring(i + 1).split(',', '、', '，')
                .map { it.trim() }.filter { it.isNotEmpty() }
            out.add(Splitter.Item(name, if (forms.isEmpty()) listOf(name) else forms))
        }
        return out
    }
}
