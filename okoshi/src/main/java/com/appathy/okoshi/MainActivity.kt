package com.appathy.okoshi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import kotlin.concurrent.thread

/**
 * v0.4
 *
 * v0.2 の問題:
 *   RUN_COMMAND_BACKGROUND=false だと Termux が Activity を起動するため
 *   「他のアプリの上に重ねて表示」が必要になる。
 *   提供元不明のアプリでは Android の「制限付き設定」に阻まれて付与できない。
 *
 * 対策:
 *   バックグラウンド実行に切り替え、進行状況はアプリ側で出力フォルダを
 *   監視して把握する。オーバーレイ権限は不要になる。
 */
class MainActivity : Activity() {

    companion object {
        const val PERM_TERMUX = "com.termux.permission.RUN_COMMAND"
        const val OKOSHI = "/data/data/com.termux/files/home/bin/okoshi"
        private const val REQ_BASE = 1
        private const val REQ_TERMUX = 2
        private const val REQ_TREE = 3
        private const val POLL_MS = 5000L
        private const val TIMEOUT_MIN = 40
    }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var timeView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var recBtn: Button
    private lateinit var folderBtn: Button
    private lateinit var runBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var log: TextView

    private var lastPath: String? = null
    private var ticking = false
    private var polling = false
    private var resultText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (d * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "面談録音  v0.4"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "最長5分 / 16kHzモノラル / 文字起こしはTermux"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, pad)
        })

        timeView = TextView(this).apply {
            text = "0:00"
            textSize = 46f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        root.addView(timeView)

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(0, 0, 0, pad)
        }
        root.addView(levelBar, LinearLayout.LayoutParams(MATCH_PARENT, (d * 12).toInt()))

        recBtn = Button(this).apply {
            text = "録音開始"
            setOnClickListener { toggle() }
        }
        root.addView(recBtn, LinearLayout.LayoutParams(MATCH_PARENT, (d * 64).toInt()))

        folderBtn = Button(this).apply {
            text = "出力フォルダを選ぶ"
            setOnClickListener { pickTree() }
        }
        root.addView(folderBtn)

        runBtn = Button(this).apply {
            text = "文字起こし開始"
            isEnabled = false
            setOnClickListener { transcribe() }
        }
        root.addView(runBtn)

        copyBtn = Button(this).apply {
            text = "コマンドをコピー"
            isEnabled = false
            setOnClickListener { copyCommand() }
        }
        root.addView(copyBtn)

        shareBtn = Button(this).apply {
            text = "MendanAppへ送る"
            isEnabled = false
            setOnClickListener { share() }
        }
        root.addView(shareBtn)

        log = TextView(this).apply {
            setPadding(0, pad, 0, 0)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(log)

        setContentView(ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        ask()
        showFolder()
        restoreLast()
    }

    private fun restoreLast() {
        val f = RecService.lastFile
        if (f != null && !f.startsWith("ERROR:")) {
            lastPath = f
            runBtn.isEnabled = true
            copyBtn.isEnabled = true
        }
    }

    // ---------- 権限 ----------

    private fun ask() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (termuxInstalled() && !hasTermuxPerm()) need.add(PERM_TERMUX)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_BASE)
    }

    private fun hasTermuxPerm() =
        checkSelfPermission(PERM_TERMUX) == PackageManager.PERMISSION_GRANTED

    private fun termuxInstalled(): Boolean = try {
        packageManager.getPackageInfo("com.termux", 0); true
    } catch (e: Exception) {
        false
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_TERMUX) {
            if (hasTermuxPerm()) transcribe()
            else log.text = "Termuxの実行権限が許可されませんでした。\n" +
                    "「コマンドをコピー」で手動実行してください。"
        }
    }

    // ---------- 出力フォルダ ----------

    private fun pickTree() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || req != REQ_TREE) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        Prefs.saveTree(this, uri.toString())
        showFolder()
    }

    private fun showFolder() {
        folderBtn.text =
            if (Prefs.tree(this) == null) "出力フォルダを選ぶ（未設定）"
            else "出力フォルダを選び直す"
    }

    // ---------- 録音 ----------

    private fun toggle() {
        if (RecService.running) {
            startService(Intent(this, RecService::class.java).setAction(RecService.ACTION_STOP))
            recBtn.isEnabled = false
            log.text = "保存中..."
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ask(); return
            }
            lastPath = null
            resultText = null
            runBtn.isEnabled = false
            copyBtn.isEnabled = false
            shareBtn.isEnabled = false
            log.text = ""
            val i = Intent(this, RecService::class.java).setAction(RecService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            recBtn.text = "停止"
            startTicking()
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        ui.post(object : Runnable {
            override fun run() {
                val sec = RecService.elapsed
                timeView.text = String.format("%d:%02d", sec / 60, sec % 60)
                levelBar.progress = (RecService.level * 300).toInt().coerceIn(0, 100)
                timeView.setTextColor(
                    if (sec >= RecService.MAX_SECONDS - 30 && RecService.running) Color.RED
                    else Color.BLACK
                )
                if (!RecService.running) {
                    ticking = false
                    levelBar.progress = 0
                    recBtn.text = "録音開始"
                    recBtn.isEnabled = true
                    val f = RecService.lastFile
                    if (f != null) {
                        if (f.startsWith("ERROR:")) {
                            log.text = "録音失敗: ${f.removePrefix("ERROR:")}"
                        } else {
                            lastPath = f
                            runBtn.isEnabled = true
                            copyBtn.isEnabled = true
                            log.text = "保存しました\n$f"
                        }
                    }
                    return
                }
                ui.postDelayed(this, 200)
            }
        })
    }

    // ---------- 文字起こし ----------

    private fun baseName(): String {
        val p = lastPath ?: return ""
        return p.substringAfterLast('/').substringBeforeLast('.')
    }

    private fun command(): String {
        val p = lastPath ?: return ""
        return "termux-wake-lock; okoshi \"$p\"; termux-wake-unlock"
    }

    private fun copyCommand() {
        if (lastPath == null) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("okoshi", command()))
        log.text = "コピーしました。Termuxに貼り付けて実行してください。\n\n" + command()
        if (Prefs.tree(this) != null) startPolling()
    }

    private fun transcribe() {
        val path = lastPath ?: return

        if (!termuxInstalled()) {
            log.text = "Termuxが見つかりません。"; return
        }
        if (!hasTermuxPerm()) {
            requestPermissions(arrayOf(PERM_TERMUX), REQ_TERMUX); return
        }
        if (Prefs.tree(this) == null) {
            log.text = "先に「出力フォルダを選ぶ」で\n" +
                    "Music/okoshi を指定してください。\n" +
                    "完了検出に必要です。"
            return
        }

        try {
            val i = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", OKOSHI)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(path))
                // true にすることで Termux が画面を開かない。
                // オーバーレイ権限が不要になる。
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            startPolling()
        } catch (e: Exception) {
            log.text = "Termux起動に失敗しました。\n${e.message}\n\n" +
                    "「コマンドをコピー」で手動実行してください。"
        }
    }

    /** 出力フォルダを一定間隔で見て、入力ファイル名を含む .txt の出現を待つ。 */
    private fun startPolling() {
        if (polling) return
        polling = true
        runBtn.isEnabled = false
        val started = System.currentTimeMillis()
        val base = baseName()

        ui.post(object : Runnable {
            override fun run() {
                if (!polling) return
                val min = ((System.currentTimeMillis() - started) / 60000).toInt()
                val sec = ((System.currentTimeMillis() - started) / 1000 % 60).toInt()

                thread {
                    val found = findResult(base)
                    ui.post {
                        if (!polling) return@post
                        if (found != null) {
                            polling = false
                            resultText = found
                            runBtn.isEnabled = true
                            shareBtn.isEnabled = true
                            val head = found.take(400)
                            log.text = "完了（${min}分${sec}秒）\n" +
                                    "${found.length}字\n\n" +
                                    "---- 冒頭 ----\n$head" +
                                    if (found.length > 400) "\n..." else ""
                        } else if (min >= TIMEOUT_MIN) {
                            polling = false
                            runBtn.isEnabled = true
                            log.text = "${TIMEOUT_MIN}分待っても出力がありません。\n" +
                                    "Termuxを開いて状況を確認してください。\n" +
                                    "メモリ不足で落ちた場合は Killed と表示されます。"
                        } else {
                            log.text = "文字起こし中... ${min}分${sec}秒経過\n" +
                                    "medium は5〜15分かかります。\n" +
                                    "画面を閉じても処理は続きます。"
                            ui.postDelayed(this, POLL_MS)
                        }
                    }
                }
            }
        })
    }

    /** SAFで選んだフォルダから、base を含む .txt を探して読む。 */
    private fun findResult(base: String): String? {
        val t = Prefs.tree(this) ?: return null
        return try {
            val tree = Uri.parse(t)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".txt") || !name.contains(base)) continue
                    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    val s = contentResolver.openInputStream(doc)!!.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                    if (s.isNotBlank()) return s
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun share() {
        val t = resultText ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, t)
                }, "送り先を選択"
            )
        )
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }
}
