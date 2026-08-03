package com.appathy.mendan

/** 検索用の正規化のみ。全角英数→半角 / 全角空白→半角 / カタカナ→ひらがな / 小文字化。 */
object Splitter {
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
}
