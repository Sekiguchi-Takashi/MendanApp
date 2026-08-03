package com.appathy.mendan

import org.json.JSONObject

/**
 * whisper.cpp の JSON 出力（-oj）を扱う。外部依存は Android 同梱の org.json のみ。
 *
 * 期待する構造:
 *   { "transcription": [ { "offsets": {"from": ms, "to": ms},
 *                          "text": "..." }, ... ] }
 * offsets はミリ秒。古いビルドは "timestamps" しか持たない場合があるので両対応。
 */
object Transcript {

    data class Segment(val fromMs: Int, val toMs: Int, val text: String)

    fun parse(json: String): List<Segment> {
        val out = ArrayList<Segment>()
        val root = JSONObject(json)
        val arr = root.optJSONArray("transcription") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text", "").trim()
            if (text.isEmpty()) continue

            var from = -1
            var to = -1
            val off = o.optJSONObject("offsets")
            if (off != null) {
                from = off.optInt("from", -1)
                to = off.optInt("to", -1)
            }
            if (from < 0) {
                // timestamps 形式 "00:00:01,200" を ms へ
                val ts = o.optJSONObject("timestamps")
                if (ts != null) {
                    from = hmsToMs(ts.optString("from"))
                    to = hmsToMs(ts.optString("to"))
                }
            }
            out.add(Segment(from.coerceAtLeast(0), to.coerceAtLeast(0), text))
        }
        return out
    }

    private fun hmsToMs(s: String): Int {
        // "HH:MM:SS,mmm"
        return try {
            val (hms, milli) = if (s.contains(',')) s.split(',') else listOf(s, "0")
            val p = hms.split(':')
            val h = p.getOrElse(0) { "0" }.toInt()
            val m = p.getOrElse(1) { "0" }.toInt()
            val sec = p.getOrElse(2) { "0" }.toInt()
            ((h * 3600 + m * 60 + sec) * 1000) + milli.padEnd(3, '0').take(3).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /** 全文（保存・検索の対象）。 */
    fun plain(segs: List<Segment>): String =
        segs.joinToString("\n") { it.text }

    /** 検索。前後の空白と大小・全半角を無視して部分一致した行番号を返す。 */
    fun search(segs: List<Segment>, query: String): List<Int> {
        val q = Splitter.norm(query.trim())
        if (q.isEmpty()) return emptyList()
        val res = ArrayList<Int>()
        for (i in segs.indices) {
            if (Splitter.norm(segs[i].text).contains(q)) res.add(i)
        }
        return res
    }

    fun fmt(ms: Int): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }
}
