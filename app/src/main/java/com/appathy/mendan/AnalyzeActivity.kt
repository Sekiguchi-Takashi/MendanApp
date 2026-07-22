package com.appathy.mendan

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/**
 * フェーズ4。
 * 全ファイルを読み込み、抽出だけを行う。判定は人が下す。
 *
 *   【1…】 除外（質問者の発話）
 *   【…】  完全一致。項目ごとに並べる
 *   【2…】 要判断として別枠に並べる
 *
 * 自動で確定させないのは、支援者の復唱など機械では切り分けられない
 * ケースが必ず残るため。アプリは材料を揃えるところまでを担う。
 */
class AnalyzeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (d * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val items = Store.parseItems(Store.rawItems(this))
        val id = Store.session(this)
        val files = if (id == null) emptyList() else Store.files(this, id)

        val marks = ArrayList<Splitter.Mark>()
        for ((i, f) in files.withIndex()) {
            try {
                marks.addAll(Splitter.parse(f.readText(Charsets.UTF_8), i + 1, items))
            } catch (_: Exception) {
            }
        }
        val r = Splitter.analyze(marks, items)

        root.addView(TextView(this).apply {
            text = "分析結果"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "${files.size}ファイル / 除外(【1】) ${r.excluded}件"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, pad)
        })

        // ---- 完全一致 ----
        root.addView(section("完全一致"))
        var any = false
        for ((name, list) in r.confirmed) {
            if (list.isEmpty()) continue
            any = true
            root.addView(TextView(this).apply {
                text = "■ $name  (${list.size}件)"
                setTypeface(null, Typeface.BOLD)
                setPadding(0, pad / 2, 0, 0)
            })
            for (m in list) root.addView(quote(m))
        }
        if (!any) root.addView(note("（なし）"))

        // ---- 要判断 ----
        root.addView(section("要判断（【2】）"))
        if (r.review.isEmpty()) root.addView(note("（なし）"))
        else for (m in r.review) root.addView(quote(m))

        // ---- 未着手 ----
        root.addView(section("未着手"))
        if (r.untouched.isEmpty()) root.addView(note("（なし）"))
        else root.addView(TextView(this).apply {
            text = r.untouched.joinToString("\n") { "■ $it" }
            setPadding(0, 0, 0, pad)
        })

        root.addView(TextView(this).apply {
            text = "\n完全一致は「話題に出た」までを示す。\n" +
                    "質問者の発話は【1】で除外済み。\n" +
                    "最終判断は各引用を読んで行うこと。"
            textSize = 11f
            setTextColor(Color.GRAY)
        })

        root.addView(Button(this).apply {
            text = "戻る"
            setOnClickListener { finish() }
        })

        setContentView(ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setBackgroundColor(Color.parseColor("#EEEEEE"))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12, 12, 12, 12)
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.GRAY)
        textSize = 12f
    }

    private fun quote(m: Splitter.Mark) = TextView(this).apply {
        text = "  ${m.file}-${m.lineNo}  ${m.line.trim()}"
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, 4, 0, 4)
    }
}
