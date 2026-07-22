package com.appathy.mendan

import android.content.Context

/**
 * 保存は SharedPreferences のみ。
 * キーワードは人が編集しやすいテキスト形式で保持する。
 *   名称: 表記1, 表記2, 表記3
 * 名称のみの行は、名称自身を表記として扱う。
 * # で始まる行はコメント。
 */
object Store {

    private const val PREF = "mendan"
    private const val K_ITEMS = "items"
    private const val K_TREE = "tree"
    private const val K_LIMIT = "limit"

    const val DEFAULT_LIMIT = 1500

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

    fun tree(ctx: Context): String? = p(ctx).getString(K_TREE, null)

    fun saveTree(ctx: Context, uri: String) {
        p(ctx).edit().putString(K_TREE, uri).apply()
    }

    fun limit(ctx: Context): Int = p(ctx).getInt(K_LIMIT, DEFAULT_LIMIT)

    fun saveLimit(ctx: Context, v: Int) {
        p(ctx).edit().putInt(K_LIMIT, v).apply()
    }

    fun parseItems(raw: String): List<Splitter.Item> {
        val out = ArrayList<Splitter.Item>()
        for (line0 in raw.split("\n")) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = line.indexOf(':').let { if (it >= 0) it else line.indexOf('：') }
            if (i < 0) {
                out.add(Splitter.Item(line, listOf(line)))
                continue
            }
            val name = line.substring(0, i).trim()
            if (name.isEmpty()) continue
            val rest = line.substring(i + 1)
            val forms = rest.split(',', '、', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            out.add(Splitter.Item(name, if (forms.isEmpty()) listOf(name) else forms))
        }
        return out
    }
}
