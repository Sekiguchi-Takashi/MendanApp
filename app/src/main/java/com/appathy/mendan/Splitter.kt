package com.appathy.mendan

/**
 * フェーズ2＋4の中核。外部依存ゼロ。
 *
 * マーカー記法
 *   【介護】        アプリが検出した完全一致。未判定。
 *   【1介護】       質問者（説明者）の発話。評価対象外。
 *   【2任意の文】   完全一致ではないが重要。要判断として抽出。
 */
object Splitter {

    data class Item(val name: String, val forms: List<String>)

    data class Hit(val start: Int, val end: Int, val item: String)

    data class Chunk(
        val index: Int,
        val startLine: Int,
        val endLine: Int,
        val lines: List<String>,
        val chars: Int
    )

    /** 編集後のファイルから拾い出した1件のマーカー。 */
    data class Mark(
        val digit: Char?,     // null / '1' / '2'
        val body: String,     // 【】の中身（数字を除く）
        val item: String?,    // 確認項目名。該当なしなら null
        val line: String,     // その行の全文
        val file: Int,
        val lineNo: Int
    )

    /**
     * 正規化。1文字→1文字を厳守すること。
     * 長さが変わるとマッチ位置を元テキストへ戻せず、【】の挿入位置がずれる。
     * 既知の制限: 半角カタカナ未対応（濁点が別文字になり1:1にできない）。
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
        for (h in all) if (h.start >= last) { res.add(h); last = h.end }
        return res
    }

    fun mark(line: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return line
        val sb = StringBuilder(line)
        for (h in hits.sortedByDescending { it.start }) {
            sb.insert(h.end, '】')
            sb.insert(h.start, '【')
        }
        return sb.toString()
    }

    /** 行境界で分割。発話の途中では絶対に切らない。 */
    fun split(text: String, limit: Int): List<Chunk> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val chunks = ArrayList<Chunk>()
        var cur = ArrayList<String>()
        var curStart = 0
        var count = 0
        var idx = 0

        fun flush(endExclusive: Int) {
            if (cur.isEmpty()) return
            idx++
            chunks.add(Chunk(idx, curStart + 1, endExclusive, ArrayList(cur), count))
            cur = ArrayList(); count = 0
        }

        for (i in lines.indices) {
            val l = lines[i]
            if (cur.isNotEmpty() && count + l.length > limit) { flush(i); curStart = i }
            if (cur.isEmpty()) curStart = i
            cur.add(l); count += l.length
        }
        flush(lines.size)
        return chunks
    }

    /** ファイル本文。1行目のヘッダは解析時に読み飛ばす。 */
    fun render(c: Chunk, total: Int, items: List<Item>): String {
        val sb = StringBuilder()
        sb.append("# ").append(c.index).append("/").append(total)
            .append("  行 ").append(c.startLine).append("-").append(c.endLine)
            .append("  ").append(c.chars).append("字\n")
        for (l in c.lines) sb.append(mark(l, findHits(l, items))).append("\n")
        return sb.toString()
    }

    // ---------- 編集後の解析 ----------

    /** 1ファイル分の本文からマーカーを拾う。 */
    fun parse(body: String, fileIndex: Int, items: List<Item>): List<Mark> {
        val out = ArrayList<Mark>()
        val lines = body.split("\n")
        for ((li, line) in lines.withIndex()) {
            if (line.startsWith("#")) continue
            var i = 0
            while (true) {
                val s = line.indexOf('【', i)
                if (s < 0) break
                val e = line.indexOf('】', s + 1)
                if (e < 0) break
                var inner = line.substring(s + 1, e)
                var digit: Char? = null
                if (inner.isNotEmpty()) {
                    val c0 = inner[0]
                    if (c0 == '1' || c0 == '１') { digit = '1'; inner = inner.substring(1) }
                    else if (c0 == '2' || c0 == '２') { digit = '2'; inner = inner.substring(1) }
                }
                out.add(Mark(digit, inner, matchItem(inner, items), line, fileIndex, li + 1))
                i = e + 1
            }
        }
        return out
    }

    private fun matchItem(body: String, items: List<Item>): String? {
        val n = norm(body)
        if (n.isEmpty()) return null
        for (item in items) {
            for (f in item.forms) {
                val nf = norm(f)
                if (nf.isNotEmpty() && n.contains(nf)) return item.name
            }
        }
        return null
    }

    data class Report(
        val confirmed: LinkedHashMap<String, MutableList<Mark>>, // 完全一致（1以外）
        val review: MutableList<Mark>,                           // 【2】要判断
        val excluded: Int,                                       // 【1】除外件数
        val untouched: List<String>                              // 未着手の項目
    )

    fun analyze(marks: List<Mark>, items: List<Item>): Report {
        val conf = LinkedHashMap<String, MutableList<Mark>>()
        for (item in items) conf[item.name] = ArrayList()
        val review = ArrayList<Mark>()
        var excluded = 0

        for (m in marks) {
            when (m.digit) {
                '1' -> excluded++
                '2' -> review.add(m)
                else -> m.item?.let { conf[it]?.add(m) }
            }
        }
        val untouched = items.map { it.name }.filter { conf[it].isNullOrEmpty() }
        return Report(conf, review, excluded, untouched)
    }
}
