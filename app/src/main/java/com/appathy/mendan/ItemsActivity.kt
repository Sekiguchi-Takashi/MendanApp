package com.appathy.mendan

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/** 確認項目の編集。テキスト1枚で完結させる。 */
class ItemsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "確認項目"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "名称: 表記1, 表記2 ...\n表記ゆれ（カタカナ/ひらがな/全角半角）は自動で吸収します。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, pad / 2)
        })

        val edit = EditText(this).apply {
            setText(Store.rawItems(this@ItemsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = android.view.Gravity.TOP
            typeface = Typeface.MONOSPACE
            textSize = 13f
            minLines = 14
            setHorizontallyScrolling(false)
        }
        root.addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val info = TextView(this).apply {
            setPadding(0, pad / 2, 0, 0)
            textSize = 12f
            setTextColor(Color.GRAY)
        }
        root.addView(info)

        root.addView(Button(this).apply {
            text = "保存して戻る"
            setOnClickListener {
                val raw = edit.text.toString()
                val n = Store.parseItems(raw).size
                if (n == 0) {
                    info.text = "項目が0件です。保存できません。"
                    return@setOnClickListener
                }
                Store.saveItems(this@ItemsActivity, raw)
                setResult(RESULT_OK)
                finish()
            }
        })

        setContentView(ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }
}
