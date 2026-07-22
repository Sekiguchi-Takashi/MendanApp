package com.appathy.mendan

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * v0.1  フェーズ2まで。
 * 文字起こしテキストを取り込み → キーワードを【】でマーキング → 行境界で分割 →
 * 出力フォルダへ 00_サマリ.txt + 連番テキストを書き出す。
 * AI推論は一切行わない。完全に決定的な処理。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_TEXT = 1001
        private const val REQ_TREE = 1002
        private const val REQ_ITEMS = 1003
    }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var srcView: TextView
    private lateinit var treeView: TextView
    private lateinit var limitInput: EditText
    private lateinit var runBtn: Button
    private lateinit var log: TextView

    private var text: String? = null
    private var srcName: String = "-"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "面談チェック  v0.1"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "フェーズ2: キーワード強調 + 分割"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, pad)
        })

        root.addView(Button(this).apply {
            text = "1. 文字起こしテキストを選ぶ"
            setOnClickListener { pickText() }
        })
        srcView = TextView(this).apply {
            text = "未選択"
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(srcView)

        root.addView(Button(this).apply {
            text = "2. 確認項目を編集"
            setOnClickListener {
                startActivityForResult(Intent(this@MainActivity, ItemsActivity::class.java), REQ_ITEMS)
            }
        })

        root.addView(Button(this).apply {
            text = "3. 出力先フォルダを選ぶ"
            setOnClickListener { pickTree() }
        })
        treeView = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(treeView)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = "1ファイルの文字数  " })
            limitInput = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(Store.limit(this@MainActivity).toString())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            addView(limitInput)
        })

        runBtn = Button(this).apply {
            text = "4. 分割して書き出す"
            isEnabled = false
            setOnClickListener { run() }
        }
        root.addView(runBtn)

        log = TextView(this).apply {
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        root.addView(log)

        setContentView(ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        showTree()
        handleSend(intent)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        handleSend(i)
    }

    /** 文字起こしアプリからの「共有」を受ける。 */
    private fun handleSend(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val s = i.getStringExtra(Intent.EXTRA_TEXT)
            if (s != null) {
                setText(s, "共有されたテキスト")
                return
            }
            val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) readUri(u)
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

    private fun pickTree() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        when (req) {
            REQ_TEXT -> data?.data?.let { readUri(it) }
            REQ_TREE -> data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                Store.saveTree(this, uri.toString())
                showTree()
            }
        }
    }

    private fun readUri(u: Uri) {
        thread {
            try {
                val s = contentResolver.openInputStream(u)!!.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                var name = u.lastPathSegment ?: "text"
                name = name.substringAfterLast('/')
                ui.post { setText(s, name) }
            } catch (e: Exception) {
                ui.post { log.text = "読込失敗: ${e.message}" }
            }
        }
    }

    private fun setText(s: String, name: String) {
        text = s
        srcName = name
        srcView.text = "$name  ${s.length}字"
        runBtn.isEnabled = true
        val est = s.length / 250
        log.text = "目安: 約${est}分ぶんの発話量です。\n" +
                "（会話の文字起こしは概ね毎分200〜300字）"
    }

    private fun showTree() {
        val t = Store.tree(this)
        treeView.text = if (t == null) "未選択" else "設定済み"
    }

    private fun run() {
        val src = text
        if (src == null) {
            log.text = "テキストが未選択です"
            return
        }
        val treeStr = Store.tree(this)
        if (treeStr == null) {
            log.text = "出力先フォルダが未選択です"
            return
        }
        val limit = limitInput.text.toString().toIntOrNull()?.coerceIn(200, 20000)
            ?: Store.DEFAULT_LIMIT
        Store.saveLimit(this, limit)

        val items = Store.parseItems(Store.rawItems(this))
        if (items.isEmpty()) {
            log.text = "確認項目が空です"
            return
        }

        runBtn.isEnabled = false
        log.text = "処理中..."

        thread {
            try {
                val chunks = Splitter.split(src, limit, items)
                val tree = Uri.parse(treeStr)
                val dirId = DocumentsContract.getTreeDocumentId(tree)
                val dir = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)

                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date())

                write(dir, "${stamp}_00_サマリ.txt",
                    Splitter.summary(chunks, items, limit, srcName))

                for ((i, c) in chunks.withIndex()) {
                    val prev = if (i == 0) emptyList()
                    else chunks[i - 1].lines.takeLast(3)
                    val name = String.format(Locale.JAPAN, "%s_%02d.txt", stamp, c.index)
                    write(dir, name, Splitter.render(c, chunks.size, prev, items))
                }

                val hitCount = chunks.count { it.items.isNotEmpty() }
                val missing = items.map { it.name }
                    .filter { n -> chunks.none { it.items.contains(n) } }

                ui.post {
                    runBtn.isEnabled = true
                    log.text = buildString {
                        append("完了\n")
                        append("ファイル数: ").append(chunks.size).append(" + サマリ1\n")
                        append("該当ありのファイル: ").append(hitCount).append("\n")
                        append("未着手の項目: ")
                        append(if (missing.isEmpty()) "なし" else missing.joinToString(", "))
                        append("\n\n全ファイルを通読し、【】の行に発話者を割り当ててください。")
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    runBtn.isEnabled = true
                    log.text = "失敗: ${e.message}"
                }
            }
        }
    }

    private fun write(dir: Uri, name: String, body: String) {
        val f = DocumentsContract.createDocument(contentResolver, dir, "text/plain", name)
            ?: throw IllegalStateException("ファイル作成に失敗: $name")
        contentResolver.openOutputStream(f)!!.use {
            it.write(body.toByteArray(Charsets.UTF_8))
        }
    }
}
