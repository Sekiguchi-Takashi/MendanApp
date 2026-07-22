package com.appathy.mendan

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

/**
 * v0.2
 *
 * 変更点
 *  - 出力先フォルダの選択（SAF）を廃止。アプリ内部に保存する。
 *  - 分割後、先頭ファイルを画面下の編集欄にそのまま開く。
 *  - 編集欄に「1を付ける」「2で囲む」を用意し、手打ちを不要にする。
 *  - 分割上限を 500字 に変更。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_TEXT = 1001
        private const val REQ_ITEMS = 1003
    }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var srcView: TextView
    private lateinit var limitInput: EditText
    private lateinit var runBtn: Button
    private lateinit var pageLabel: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var openBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var mark1Btn: Button
    private lateinit var mark2Btn: Button
    private lateinit var editor: EditText
    private lateinit var status: TextView

    private var text: String? = null
    private var srcName: String = "-"
    private var files: List<File> = emptyList()
    private var page = 0
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (d * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "面談チェック  v0.2"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })

        // 1
        root.addView(Button(this).apply {
            text = "1. 文字起こしを選ぶ"
            setOnClickListener { pickText() }
        })
        srcView = TextView(this).apply {
            text = "未選択"
            setTextColor(Color.DKGRAY)
            textSize = 12f
        }
        root.addView(srcView)

        // 2
        root.addView(Button(this).apply {
            text = "2. 確認項目を編集"
            setOnClickListener {
                startActivityForResult(Intent(this@MainActivity, ItemsActivity::class.java), REQ_ITEMS)
            }
        })

        // 3
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "1ファイル "
                textSize = 12f
            })
            limitInput = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(Store.limit(this@MainActivity).toString())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            addView(limitInput)
            addView(TextView(this@MainActivity).apply {
                text = " 字"
                textSize = 12f
            })
        })
        runBtn = Button(this).apply {
            text = "3. 分割して保存"
            isEnabled = false
            setOnClickListener { runSplit() }
        }
        root.addView(runBtn)

        // 4
        root.addView(Button(this).apply {
            text = "4. 分析する"
            setOnClickListener { analyze() }
        })

        status = TextView(this).apply {
            setPadding(0, pad / 2, 0, pad / 2)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        root.addView(status)

        // ---- 編集エリア ----
        root.addView(View(this).apply { setBackgroundColor(Color.LTGRAY) },
            LinearLayout.LayoutParams(MATCH_PARENT, (d * 1).toInt()))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            prevBtn = Button(this@MainActivity).apply {
                text = "◀"
                setOnClickListener { move(-1) }
            }
            addView(prevBtn)
            pageLabel = TextView(this@MainActivity).apply {
                text = "-"
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            addView(pageLabel)
            nextBtn = Button(this@MainActivity).apply {
                text = "▶"
                setOnClickListener { move(1) }
            }
            addView(nextBtn)
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            openBtn = Button(this@MainActivity).apply {
                text = "開く"
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnClickListener { open(page) }
            }
            addView(openBtn)
            saveBtn = Button(this@MainActivity).apply {
                text = "保存"
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnClickListener { save() }
            }
            addView(saveBtn)
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            mark1Btn = Button(this@MainActivity).apply {
                text = "1を付ける"
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnClickListener { addDigit('1') }
            }
            addView(mark1Btn)
            mark2Btn = Button(this@MainActivity).apply {
                text = "2で囲む"
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnClickListener { wrapTwo() }
            }
            addView(mark2Btn)
        })

        root.addView(TextView(this).apply {
            text = "【1…】質問者の発話＝評価対象外  /  【2…】要判断として抽出"
            textSize = 11f
            setTextColor(Color.GRAY)
        })

        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            textSize = 14f
            minLines = 16
            setHorizontallyScrolling(false)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { dirty = true; refreshPage() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        restore()
        handleSend(intent)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        handleSend(i)
    }

    private fun handleSend(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val s = i.getStringExtra(Intent.EXTRA_TEXT)
            if (s != null) { setText(s, "共有されたテキスト"); return }
            i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { readUri(it) }
        }
    }

    private fun restore() {
        val id = Store.session(this) ?: return
        files = Store.files(this, id)
        if (files.isNotEmpty()) {
            page = 0
            open(0)
            status.text = "前回のセッション $id（${files.size}ファイル）"
        }
    }

    private fun pickText() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*", "*/*"))
            }, REQ_TEXT
        )
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        if (req == REQ_TEXT) data?.data?.let { readUri(it) }
    }

    private fun readUri(u: Uri) {
        thread {
            try {
                val s = contentResolver.openInputStream(u)!!.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val name = (u.lastPathSegment ?: "text").substringAfterLast('/')
                ui.post { setText(s, name) }
            } catch (e: Exception) {
                ui.post { status.text = "読込失敗: ${e.message}" }
            }
        }
    }

    private fun setText(s: String, name: String) {
        text = s
        srcName = name
        srcView.text = "$name  ${s.length}字（約${s.length / 250}分）"
        runBtn.isEnabled = true
    }

    // ---------- 3. 分割 ----------

    private fun runSplit() {
        val src = text ?: return
        val limit = limitInput.text.toString().toIntOrNull()?.coerceIn(100, 20000)
            ?: Store.DEFAULT_LIMIT
        Store.saveLimit(this, limit)

        val items = Store.parseItems(Store.rawItems(this))
        if (items.isEmpty()) { status.text = "確認項目が空です"; return }

        runBtn.isEnabled = false
        status.text = "分割中..."

        thread {
            try {
                val chunks = Splitter.split(src, limit)
                val id = Store.newSessionId()
                val dir = Store.sessionDir(this, id)
                dir.listFiles()?.forEach { it.delete() }

                for (c in chunks) {
                    File(dir, String.format("%03d.txt", c.index))
                        .writeText(Splitter.render(c, chunks.size, items), Charsets.UTF_8)
                }
                Store.saveSession(this, id)

                ui.post {
                    runBtn.isEnabled = true
                    files = Store.files(this, id)
                    page = 0
                    open(0)
                    status.text = "保存しました  ${files.size}ファイル\n$id"
                }
            } catch (e: Exception) {
                ui.post { runBtn.isEnabled = true; status.text = "失敗: ${e.message}" }
            }
        }
    }

    // ---------- 編集 ----------

    private fun open(i: Int) {
        if (files.isEmpty()) { status.text = "先に分割してください"; return }
        page = i.coerceIn(0, files.size - 1)
        editor.setText(files[page].readText(Charsets.UTF_8))
        dirty = false
        refreshPage()
    }

    private fun save() {
        if (files.isEmpty()) return
        try {
            files[page].writeText(editor.text.toString(), Charsets.UTF_8)
            dirty = false
            refreshPage()
            status.text = "保存しました  ${files[page].name}"
        } catch (e: Exception) {
            status.text = "保存失敗: ${e.message}"
        }
    }

    private fun move(d: Int) {
        if (files.isEmpty()) return
        if (dirty) save()
        open(page + d)
    }

    private fun refreshPage() {
        if (files.isEmpty()) { pageLabel.text = "-"; return }
        pageLabel.text = "${page + 1} / ${files.size}" + if (dirty) "  *未保存" else ""
    }

    /** カーソル位置を含む【】の中身の先頭に数字を入れる。 */
    private fun addDigit(digit: Char) {
        val s = editor.text.toString()
        val pos = editor.selectionStart.coerceIn(0, s.length)
        val open = s.lastIndexOf('【', (pos - 1).coerceAtLeast(0))
        if (open < 0) { status.text = "【】の中にカーソルを置いてください"; return }
        val close = s.indexOf('】', open + 1)
        if (close < 0 || close < pos - 1) { status.text = "【】の中にカーソルを置いてください"; return }

        val inner = s.substring(open + 1, close)
        val cleaned = if (inner.isNotEmpty() && inner[0] in "12１２") inner.substring(1) else inner
        val next = s.substring(0, open + 1) + digit + cleaned + s.substring(close)
        editor.setText(next)
        editor.setSelection((open + 2).coerceAtMost(next.length))
        status.text = "【$digit$cleaned】にしました"
    }

    /** 選択範囲を【2…】で囲む。 */
    private fun wrapTwo() {
        val s = editor.text.toString()
        var a = editor.selectionStart
        var b = editor.selectionEnd
        if (a > b) { val t = a; a = b; b = t }
        if (a == b) { status.text = "囲みたい範囲を選択してください"; return }
        val next = s.substring(0, a) + "【2" + s.substring(a, b) + "】" + s.substring(b)
        editor.setText(next)
        editor.setSelection((b + 3).coerceAtMost(next.length))
        status.text = "【2…】で囲みました"
    }

    // ---------- 4. 分析 ----------

    private fun analyze() {
        if (dirty) save()
        val id = Store.session(this)
        if (id == null || Store.files(this, id).isEmpty()) {
            status.text = "先に分割してください"
            return
        }
        startActivity(Intent(this, AnalyzeActivity::class.java))
    }

    override fun onPause() {
        if (dirty) save()
        super.onPause()
    }
}
