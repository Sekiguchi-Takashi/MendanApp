package com.appathy.mendan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import kotlin.concurrent.thread

/**
 * 面談文字起こし v1.0（ゼロベース再構築 / 単一アプリ）
 *
 * 機能
 *  1. AAC音源を開く・再生する
 *  2. 文字起こし（最長1時間） → 画面表示 → 保存
 *  3. 音源ファイルの削除
 *
 * 文字起こしは Termux の whisper.cpp(medium) に投げる（従来の踏襲）。
 * アプリと Termux が同じ作業フォルダを共有し、SAF と実パスの両面から
 * 同じ場所を読み書きする。作業フォルダの選択は初回一度きり。
 */
class MainActivity : Activity() {

    companion object {
        private const val PERM_TERMUX = "com.termux.permission.RUN_COMMAND"
        private const val OKOSHI = "/data/data/com.termux/files/home/bin/okoshi"

        private const val REQ_OPEN = 1
        private const val REQ_TREE = 2
        private const val REQ_SAVE = 3
        private const val REQ_TERMUX = 4

        private const val POLL_MS = 10000L
        private const val TIMEOUT_MIN = 180
    }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var workBtn: Button
    private lateinit var openBtn: Button
    private lateinit var nameView: TextView
    private lateinit var playBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var timeView: TextView
    private lateinit var transBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var delBtn: Button
    private lateinit var status: TextView
    private lateinit var editor: EditText

    private var audioUri: Uri? = null
    private var audioName: String = "-"
    private var player: MediaPlayer? = null
    private var prepared = false
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (d * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "面談文字起こし  v1.0"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })

        workBtn = Button(this).apply {
            setOnClickListener { pickTree() }
        }
        root.addView(workBtn)

        root.addView(divider(d))

        // ---- 1. 開く・再生 ----
        openBtn = Button(this).apply {
            text = "音源を開く（AAC / m4a）"
            setOnClickListener { pickAudio() }
        }
        root.addView(openBtn)

        nameView = TextView(this).apply {
            text = "未選択"
            setTextColor(Color.DKGRAY)
            textSize = 12f
        }
        root.addView(nameView)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            playBtn = Button(this@MainActivity).apply {
                text = "再生"
                isEnabled = false
                setOnClickListener { togglePlay() }
            }
            addView(playBtn)
            timeView = TextView(this@MainActivity).apply {
                text = "0:00 / 0:00"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding((d * 12).toInt(), 0, 0, 0)
            }
            addView(timeView)
        })

        seek = SeekBar(this).apply {
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) player?.seekTo(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seek)

        root.addView(divider(d))

        // ---- 2. 文字起こし ----
        transBtn = Button(this).apply {
            text = "文字起こし（Termux / medium）"
            isEnabled = false
            setOnClickListener { transcribe() }
        }
        root.addView(transBtn)

        status = TextView(this).apply {
            setPadding(0, pad / 2, 0, pad / 2)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        root.addView(status)

        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            textSize = 14f
            minLines = 12
            hint = "文字起こし結果がここに表示されます"
            setHorizontallyScrolling(false)
        }
        root.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        saveBtn = Button(this).apply {
            text = "テキストを保存"
            isEnabled = false
            setOnClickListener { saveText() }
        }
        root.addView(saveBtn)

        root.addView(divider(d))

        // ---- 3. 削除 ----
        delBtn = Button(this).apply {
            text = "この音源を削除"
            isEnabled = false
            setOnClickListener { confirmDelete() }
        }
        root.addView(delBtn)

        setContentView(ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        ask()
        showWork()
    }

    private fun divider(d: Float) = View(this).apply {
        setBackgroundColor(Color.LTGRAY)
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, (d * 1).toInt())
        lp.topMargin = (d * 12).toInt(); lp.bottomMargin = (d * 12).toInt()
        layoutParams = lp
    }

    // ---------- 権限 ----------

    private fun ask() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (termuxInstalled() && !hasTermuxPerm()) need.add(PERM_TERMUX)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 99)
    }

    private fun hasTermuxPerm() =
        checkSelfPermission(PERM_TERMUX) == PackageManager.PERMISSION_GRANTED

    private fun termuxInstalled(): Boolean = try {
        packageManager.getPackageInfo("com.termux", 0); true
    } catch (e: Exception) { false }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_TERMUX && hasTermuxPerm()) transcribe()
    }

    // ---------- 作業フォルダ ----------

    private fun pickTree() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    private fun showWork() {
        val fs = Prefs.fsPath(this)
        workBtn.text = if (fs == null) "作業フォルダを選ぶ（未設定）"
        else "作業フォルダ: ${fs.substringAfter("/storage/emulated/0/")}"
    }

    // ---------- 開く・再生 ----------

    private fun pickAudio() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/aac", "audio/mp4", "audio/x-m4a", "audio/*"))
            }, REQ_OPEN
        )
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        when (req) {
            REQ_TREE -> data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                Prefs.saveTree(this, uri.toString())
                showWork()
                if (Prefs.fsPath(this) == null) {
                    status.text = "本体ストレージのフォルダを選んでください。\n" +
                            "（SDカード等は Termux と共有できません）"
                }
            }
            REQ_OPEN -> data?.data?.let { setAudio(it) }
            REQ_SAVE -> data?.data?.let { writeTextTo(it) }
        }
    }

    private fun setAudio(uri: Uri) {
        releasePlayer()
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        audioUri = uri
        audioName = queryName(uri)
        nameView.text = audioName
        playBtn.isEnabled = true
        transBtn.isEnabled = true
        delBtn.isEnabled = true
        seek.isEnabled = true
        status.text = ""
        preparePlayer(uri)
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment ?: "audio"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "audio"
        }
    }

    private fun preparePlayer(uri: Uri) {
        prepared = false
        player = MediaPlayer().apply {
            try {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener {
                    prepared = true
                    seek.max = duration
                    updateTime(0, duration)
                }
                setOnCompletionListener {
                    playBtn.text = "再生"
                    seek.progress = 0
                }
                prepareAsync()
            } catch (e: Exception) {
                status.text = "再生準備に失敗: ${e.message}"
            }
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) {
            p.pause()
            playBtn.text = "再生"
        } else {
            p.start()
            playBtn.text = "一時停止"
            trackProgress()
        }
    }

    private fun trackProgress() {
        ui.post(object : Runnable {
            override fun run() {
                val p = player ?: return
                if (!prepared) return
                if (p.isPlaying) {
                    seek.progress = p.currentPosition
                    updateTime(p.currentPosition, p.duration)
                    ui.postDelayed(this, 500)
                }
            }
        })
    }

    private fun updateTime(pos: Int, dur: Int) {
        timeView.text = "${fmt(pos)} / ${fmt(dur)}"
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        prepared = false
        if (::playBtn.isInitialized) playBtn.text = "再生"
    }

    // ---------- 文字起こし ----------

    private fun transcribe() {
        val uri = audioUri ?: return

        if (!termuxInstalled()) { status.text = "Termuxが見つかりません。"; return }
        if (!hasTermuxPerm()) { requestPermissions(arrayOf(PERM_TERMUX), REQ_TERMUX); return }

        val fsDir = Prefs.fsPath(this)
        val tree = Prefs.tree(this)
        if (fsDir == null || tree == null) {
            status.text = "先に「作業フォルダを選ぶ」で\n本体ストレージのフォルダを指定してください。"
            return
        }

        transBtn.isEnabled = false
        status.text = "音源を作業フォルダへコピー中..."

        thread {
            try {
                // 一意な名前で作業フォルダへコピー（Termux が実パスで読めるように）
                val stamp = System.currentTimeMillis()
                val inName = "in_${stamp}.m4a"
                val treeUri = Uri.parse(tree)
                val dir = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val doc = DocumentsContract.createDocument(
                    contentResolver, dir, "audio/mp4", inName
                ) ?: throw IllegalStateException("作業フォルダに書き込めません")
                contentResolver.openOutputStream(doc)!!.use { out ->
                    contentResolver.openInputStream(uri)!!.use { it.copyTo(out) }
                }

                val inPath = "$fsDir/$inName"
                val base = "in_$stamp"   // okoshi 出力は STAMP_base.txt を含む

                ui.post { status.text = "Termuxへ送信..." }

                val i = Intent().apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    action = "com.termux.RUN_COMMAND"
                    putExtra("com.termux.RUN_COMMAND_PATH", OKOSHI)
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(inPath))
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)

                ui.post { startPolling(base) }
            } catch (e: Exception) {
                ui.post {
                    transBtn.isEnabled = true
                    status.text = "失敗: ${e.message}"
                }
            }
        }
    }

    /** 作業フォルダ(SAF)を監視し、base を含む .txt の出現を待つ。 */
    private fun startPolling(base: String) {
        if (polling) return
        polling = true
        val started = System.currentTimeMillis()

        ui.post(object : Runnable {
            override fun run() {
                if (!polling) return
                val min = ((System.currentTimeMillis() - started) / 60000).toInt()

                thread {
                    val found = findTxt(base)
                    ui.post {
                        if (!polling) return@post
                        when {
                            found != null -> {
                                polling = false
                                transBtn.isEnabled = true
                                editor.setText(found)
                                saveBtn.isEnabled = true
                                status.text = "完了（${min}分）  ${found.length}字"
                            }
                            min >= TIMEOUT_MIN -> {
                                polling = false
                                transBtn.isEnabled = true
                                status.text = "${TIMEOUT_MIN}分待っても出力がありません。\n" +
                                        "Termuxで状況を確認してください。"
                            }
                            else -> {
                                status.text = "文字起こし中... ${min}分経過\n" +
                                        "1時間の音源は1〜2時間かかります。\n" +
                                        "画面を閉じても処理は続きます。"
                                ui.postDelayed(this, POLL_MS)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun findTxt(base: String): String? {
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

    // ---------- 保存 ----------

    private fun saveText() {
        val suggested = audioName.substringBeforeLast('.') + ".txt"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, suggested)
            }, REQ_SAVE
        )
    }

    private fun writeTextTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(editor.text.toString().toByteArray(Charsets.UTF_8))
            }
            status.text = "保存しました"
        } catch (e: Exception) {
            status.text = "保存失敗: ${e.message}"
        }
    }

    // ---------- 削除 ----------

    private fun confirmDelete() {
        val uri = audioUri ?: return
        AlertDialog.Builder(this)
            .setTitle("削除の確認")
            .setMessage("$audioName を削除します。元に戻せません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ -> doDelete(uri) }
            .show()
    }

    private fun doDelete(uri: Uri) {
        releasePlayer()
        try {
            val ok = DocumentsContract.deleteDocument(contentResolver, uri)
            if (ok) {
                audioUri = null
                audioName = "-"
                nameView.text = "未選択"
                playBtn.isEnabled = false
                transBtn.isEnabled = false
                delBtn.isEnabled = false
                seek.isEnabled = false
                seek.progress = 0
                updateTime(0, 0)
                status.text = "削除しました"
            } else {
                status.text = "削除できませんでした（この場所は削除に対応していません）"
            }
        } catch (e: Exception) {
            status.text = "削除失敗: ${e.message}"
        }
    }

    override fun onPause() {
        try { if (player?.isPlaying == true) { player?.pause(); playBtn.text = "再生" } } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        releasePlayer()
        super.onDestroy()
    }
}
