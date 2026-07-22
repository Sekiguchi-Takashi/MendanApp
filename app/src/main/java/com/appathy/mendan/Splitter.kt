package com.appathy.mendan

/**
 * フェーズ2の中核。
 * ・表記ゆれを吸収した完全一致検索（長さ保存の正規化なので位置がずれない）
 * ・行境界での分割
 * ・【】マーキング
 * 外部依存ゼロ。
 */
object Splitter {

    data class Item(val name: String, val forms: List<String>)

    data class Hit(val start: Int, val end: Int, val item: String)

    data class Chunk(
        val index: Int,
        val startLine: Int,
        val endLine: Int,
        val lines: List<String>,
        val items: List<String>,
        val chars: Int
    )

    /**
     * 正規化。1文字→1文字を厳守すること。
     * 長さが変わるとマッチ位置を元テキストへ戻せなくなり、
     * マーカー挿入がずれる。
     * 全角英数→半角 / 全角空白→半角 / カタカナ→ひらがな / 小文字化
     * 既知の制限: 半角カタカナ（濁点が別文字になり1:1にできない）は未対応。
     */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            var ch = c
            if (ch.code in 0xFF01..0xFF5E) ch = (ch.code - 0xFEE0).toChar()
            if (ch == '\u3000') ch = ' '
            if (ch.code in 0x30A1..0x30F6) ch = (ch.code - 0x60).toChar()
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** 1行から重複しないヒットを返す。重なった場合は長い表記を優先。 */
    fun findHits(line: String, items: List<Item>): List<Hit> {
        if (items.isEmpty() || line.isEmpty()) return emptyList()
        val n = norm(line)
        val all = ArrayList<Hit>()
        for (item in items) {
            for (f in item.forms) {
                val nf = norm(f)
                if (nf.isEmpty()) continue
                var i = n.indexOf(nf)
                while (i >= 0) {
                    all.add(Hit(i, i + nf.length, item.name))
                    i = n.indexOf(nf, i + 1)
                }
            }
        }
        if (all.isEmpty()) return emptyList()
        all.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val res = ArrayList<Hit>()
        var last = 0
        for (h in all) {
            if (h.start >= last) {
                res.add(h)
                last = h.end
            }
        }
        return res
    }

    /** ヒット箇所を【】で囲む。後ろから挿入して位置ずれを防ぐ。 */
    fun mark(line: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return line
        val sb = StringBuilder(line)
        for (h in hits.sortedByDescending { it.start }) {
            sb.insert(h.end, '】')
            sb.insert(h.start, '【')
        }
        return sb.toString()
    }

    /**
     * 行境界で分割する。発話の途中では絶対に切らない。
     * 1行が limit を超える場合はその行を丸ごと1ファイルにする。
     */
    fun split(text: String, limit: Int, items: List<Item>): List<Chunk> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val chunks = ArrayList<Chunk>()
        var cur = ArrayList<String>()
        var curStart = 0
        var count = 0
        var idx = 0

        fun flush(endLineExclusive: Int) {
            if (cur.isEmpty()) return
            idx++
            val its = LinkedHashSet<String>()
            for (l in cur) for (h in findHits(l, items)) its.add(h.item)
            chunks.add(
                Chunk(idx, curStart + 1, endLineExclusive, ArrayList(cur), its.toList(), count)
            )
            cur = ArrayList()
            count = 0
        }

        for (i in lines.indices) {
            val l = lines[i]
            if (cur.isNotEmpty() && count + l.length > limit) {
                flush(i)
                curStart = i
            }
            if (cur.isEmpty()) curStart = i
            cur.add(l)
            count += l.length
        }
        flush(lines.size)
        return chunks
    }

    /** 1ファイル分の本文を組み立てる。prev は直前チャンク末尾の文脈（集計対象外）。 */
    fun render(c: Chunk, total: Int, prev: List<String>, items: List<Item>): String {
        val sb = StringBuilder()
        sb.append("======================================\n")
        sb.append("ファイル ").append(c.index).append(" / ").append(total).append("\n")
        sb.append("行 ").append(c.startLine).append("-").append(c.endLine)
        sb.append("  文字数 ").append(c.chars).append("\n")
        sb.append("該当項目: ")
            .append(if (c.items.isEmpty()) "なし" else c.items.joinToString(", "))
            .append("\n")
        sb.append("======================================\n\n")
        if (prev.isNotEmpty()) {
            sb.append("---- 直前の文脈（集計対象外・読み飛ばし可） ----\n")
            for (l in prev) sb.append(l).append("\n")
            sb.append("---- ここから本編 ----\n\n")
        }
        for (l in c.lines) sb.append(mark(l, findHits(l, items))).append("\n")
        return sb.toString()
    }

    /** 00_サマリ.txt。どの項目がどのファイルに出たか＋未着手一覧。 */
    fun summary(chunks: List<Chunk>, items: List<Item>, limit: Int, srcName: String): String {
        val map = LinkedHashMap<String, MutableList<Int>>()
        for (item in items) map[item.name] = ArrayList()
        for (c in chunks) for (name in c.items) map[name]?.add(c.index)

        val sb = StringBuilder()
        sb.append("面談チェック サマリ\n")
        sb.append("元データ: ").append(srcName).append("\n")
        sb.append("総文字数: ").append(chunks.sumOf { it.chars }).append("\n")
        sb.append("分割単位: ").append(limit).append("字\n")
        sb.append("ファイル数: ").append(chunks.size).append("\n\n")

        sb.append("---- 出現あり ----\n")
        var any = false
        for ((name, files) in map) {
            if (files.isEmpty()) continue
            any = true
            sb.append("[").append(name).append("]  ファイル ")
                .append(files.joinToString(", ")).append("\n")
        }
        if (!any) sb.append("（なし）\n")

        sb.append("\n---- 未着手（一度も出現せず） ----\n")
        var none = true
        for ((name, files) in map) {
            if (files.isNotEmpty()) continue
            none = false
            sb.append("[").append(name).append("]\n")
        }
        if (none) sb.append("（なし）\n")

        sb.append("\n注意: 出現ありは「話題に出た」までを示す。\n")
        sb.append("発話者の割当と確定/言及の判定は各ファイルを通読して行うこと。\n")
        return sb.toString()
    }
}
