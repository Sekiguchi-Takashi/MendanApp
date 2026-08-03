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
 * 面談文字起こし v2.0
 *
 * v1.0 の3機能（開く・再生 / 文字起こし / 削除）に加え、
 * whisper の JSON 出力を使って次を追加:
 *   ① テキスト↔音声のジャンプ再生（行タップでその時刻から再生）
 *   ② 全文検索（ヒット行へスクロール＆その時刻へジャンプ）
 *   ③ 発話タイムライン（各発話区間と沈黙を可視化）
 *
 * 文字起こし結果は .json で受け取り、時刻付きセグメントとして保持する。
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

    private lateinit var searchInput: EditText
    private lateinit var searchBtn: Button
    private lateinit var timelineBtn: Button
    private lateinit var segList: LinearLayout   // 時刻付き行の描画先

    private var audioUri: Uri? = null
    private var audioName: String = "-"
    private var player: MediaPlayer? = null
    private var prepared = false
    private var polling = false

    private var segments: List<Transcript.Segment> = emptyList()
    private val rowViews = ArrayList<TextView>()
    private var activeRow = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (d * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "面談文字起こし  v2.0"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })

        workBtn = Button(this).apply { setOnClickListener { pickTree() } }
        root.addView(workBtn)

        root.addView(divider(d))

        // ---- 開く・再生 ----
        openBtn = Button(this).apply {
            text = "音源を開く（AAC / m4a）"
            setOnClickListener { pickAudio() }
        }
        root.addView(openBtn)
        nameView = TextView(this).apply {
            text = "未選択"; setTextColor(Color.DKGRAY); textSize = 12f
        }
        root.addView(nameView)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            playBtn = Button(this@MainActivity).apply {
                text = "再生"; isEnabled = false
                setOnClickListener { togglePlay() }
            }
            addView(playBtn)
            timeView = TextView(this@MainActivity).apply {
                text = "0:00 / 0:00"; textSize = 12f; typeface = Typeface.MONOSPACE
                setPadding((d * 12).toInt(), 0, 0, 0)
            }
            addView(timeView)
        })
        seek = SeekBar(this).apply {
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { if (u) player?.seekTo(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seek)

        root.addView(divider(d))

        // ---- 文字起こし ----
        transBtn = Button(this).apply {
            text = "文字起こし（Termux / medium）"; isEnabled = false
            setOnClickListener { transcribe() }
        }
        root.addView(transBtn)
        status = TextView(this).apply {
            setPadding(0, pad / 2, 0, pad / 2); textSize = 12f; typeface = Typeface.MONOSPACE
        }
        root.addView(status)

        // ---- ② 検索 ＋ ③ タイムライン ----
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            searchInput = EditText(this@MainActivity).apply {
                hint = "全文検索"
                inputType = InputType.TYPE_CLASS_TEXT
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            addView(searchInput)
            searchBtn = Button(this@MainActivity).apply {
                text = "検索"; isEnabled = false
                setOnClickListener { doSearch() }
            }
            addView(searchBtn)
        })
        timelineBtn = Button(this).apply {
            text = "発話タイムライン"; isEnabled = false
            setOnClickListener { showTimeline() }
        }
        root.addView(timelineBtn)

        root.addView(TextView(this).apply {
            text = "行をタップするとその位置から再生します"
            textSize = 11f; setTextColor(Color.GRAY)
            setPadding(0, pad / 2, 0, 0)
        })

        // ---- 時刻付き本文 ----
        segList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(segList)

        root.addView(divider(d))

        // ---- 保存・削除 ----
        saveBtn = Button(this).apply {
            text = "テキストを保存"; isEnabled = false
            setOnClickListener { saveText() }
        }
        root.addView(saveBtn)
        delBtn = Button(this).apply {
            text = "この音源を削除"; isEnabled = false
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
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                Prefs.saveTree(this, uri.toString())
                showWork()
                if (Prefs.fsPath(this) == null)
                    status.text = "本体ストレージのフォルダを選んでください。"
            }
            REQ_OPEN -> data?.data?.let { setAudio(it) }
            REQ_SAVE -> data?.data?.let { writeTextTo(it) }
        }
    }

    private fun setAudio(uri: Uri) {
        releasePlayer()
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        audioUri = uri
        audioName = queryName(uri)
        nameView.text = audioName
        playBtn.isEnabled = true
        transBtn.isEnabled = true
        delBtn.isEnabled = true
        seek.isEnabled = true
        status.text = ""
        clearSegments()
        preparePlayer(uri)
    }

    private fun queryName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "audio"
    } catch (e: Exception) { uri.lastPathSegment ?: "audio" }

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
        if (p.isPlaying) { p.pause(); playBtn.text = "再生" }
        else { p.start(); playBtn.text = "一時停止"; trackProgress() }
    }

    /** 指定ミリ秒から再生。行タップ・検索ジャンプの共通処理。 */
    private fun playFrom(ms: Int) {
        val p = player ?: return
        if (!prepared) { status.text = "再生準備中です"; return }
        p.seekTo(ms)
        p.start()
        playBtn.text = "一時停止"
        seek.progress = ms
        trackProgress()
    }

    private fun trackProgress() {
        ui.post(object : Runnable {
            override fun run() {
                val p = player ?: return
                if (!prepared) return
                if (p.isPlaying) {
                    val pos = p.currentPosition
                    seek.progress = pos
                    updateTime(pos, p.duration)
                    highlightRowAt(pos)
                    ui.postDelayed(this, 300)
                }
            }
        })
    }

    private fun updateTime(pos: Int, dur: Int) { timeView.text = "${Transcript.fmt(pos)} / ${Transcript.fmt(dur)}" }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null; prepared = false
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
            status.text = "先に作業フォルダ（本体ストレージ）を選んでください。"
            return
        }

        transBtn.isEnabled = false
        status.text = "音源をコピー中..."

        thread {
            try {
                val stamp = System.currentTimeMillis()
                val inName = "in_${stamp}.m4a"
                val treeUri = Uri.parse(tree)
                val dir = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val doc = DocumentsContract.createDocument(contentResolver, dir, "audio/mp4", inName)
                    ?: throw IllegalStateException("作業フォルダに書き込めません")
                contentResolver.openOutputStream(doc)!!.use { out ->
                    contentResolver.openInputStream(uri)!!.use { it.copyTo(out) }
                }

                val inPath = "$fsDir/$inName"
                val base = "in_$stamp"

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
                ui.post { transBtn.isEnabled = true; status.text = "失敗: ${e.message}" }
            }
        }
    }

    private fun startPolling(base: String) {
        if (polling) return
        polling = true
        val started = System.currentTimeMillis()

        ui.post(object : Runnable {
            override fun run() {
                if (!polling) return
                val min = ((System.currentTimeMillis() - started) / 60000).toInt()
                thread {
                    val found = findJson(base)
                    ui.post {
                        if (!polling) return@post
                        when {
                            found != null -> {
                                polling = false
                                transBtn.isEnabled = true
                                loadTranscript(found, min)
                            }
                            min >= TIMEOUT_MIN -> {
                                polling = false
                                transBtn.isEnabled = true
                                status.text = "${TIMEOUT_MIN}分待っても出力がありません。\nTermuxで状況を確認してください。"
                            }
                            else -> {
                                status.text = "文字起こし中... ${min}分経過\n1時間の音源は1〜2時間かかります。\n画面を閉じても処理は続きます。"
                                ui.postDelayed(this, POLL_MS)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun findJson(base: String): String? {
        val t = Prefs.tree(this) ?: return null
        return try {
            val tree = Uri.parse(t)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".json") || !name.contains(base)) continue
                    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    val s = contentResolver.openInputStream(doc)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (s.isNotBlank()) return s
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun loadTranscript(json: String, min: Int) {
        try {
            segments = Transcript.parse(json)
            renderSegments()
            val hasTime = segments.any { it.toMs > 0 }
            status.text = "完了（${min}分）  ${segments.size}区間" +
                    if (!hasTime) "\n※時刻情報なし。ジャンプ再生は使えません" else ""
            saveBtn.isEnabled = segments.isNotEmpty()
            searchBtn.isEnabled = segments.isNotEmpty()
            timelineBtn.isEnabled = hasTime && segments.isNotEmpty()
        } catch (e: Exception) {
            status.text = "JSON解析に失敗: ${e.message}\nokoshiが -oj で出力しているか確認してください。"
        }
    }

    // ---------- ① 時刻付き本文とジャンプ再生 ----------

    private fun clearSegments() {
        segments = emptyList()
        rowViews.clear()
        activeRow = -1
        segList.removeAllViews()
        saveBtn.isEnabled = false
        searchBtn.isEnabled = false
        timelineBtn.isEnabled = false
    }

    private fun renderSegments() {
        segList.removeAllViews()
        rowViews.clear()
        activeRow = -1
        val d = resources.displayMetrics.density
        for ((idx, s) in segments.withIndex()) {
            val row = TextView(this).apply {
                val stamp = if (s.toMs > 0) "[${Transcript.fmt(s.fromMs)}] " else ""
                text = stamp + s.text
                textSize = 14f
                setPadding(0, (d * 6).toInt(), 0, (d * 6).toInt())
                setTextIsSelectable(false)
                if (s.toMs > 0) setOnClickListener { playFrom(s.fromMs) }
            }
            rowViews.add(row)
            segList.addView(row)
            if (idx < segments.size - 1) {
                segList.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
                })
            }
        }
    }

    /** 再生位置に対応する行を太字で示す。 */
    private fun highlightRowAt(posMs: Int) {
        if (segments.isEmpty()) return
        var target = -1
        for (i in segments.indices) {
            val s = segments[i]
            if (s.toMs > 0 && posMs >= s.fromMs && posMs < s.toMs) { target = i; break }
        }
        if (target == activeRow) return
        if (activeRow in rowViews.indices) rowViews[activeRow].apply {
            setTypeface(null, Typeface.NORMAL); setBackgroundColor(Color.TRANSPARENT)
        }
        if (target in rowViews.indices) rowViews[target].apply {
            setTypeface(null, Typeface.BOLD); setBackgroundColor(Color.parseColor("#FFF6DA"))
        }
        activeRow = target
    }

    // ---------- ② 全文検索 ----------

    private fun doSearch() {
        val q = searchInput.text.toString()
        val hits = Transcript.search(segments, q)
        if (hits.isEmpty()) { status.text = "「$q」は見つかりません"; return }
        status.text = "「$q」  ${hits.size}件"
        // 全行の強調をリセットし、ヒット行を着色
        for (v in rowViews) v.setBackgroundColor(Color.TRANSPARENT)
        for (i in hits) if (i in rowViews.indices)
            rowViews[i].setBackgroundColor(Color.parseColor("#DDEBFF"))
        // 先頭ヒットへスクロール＆その時刻から再生
        val first = hits.first()
        rowViews.getOrNull(first)?.let { row ->
            row.post { scrollToRow(row) }
        }
        val seg = segments.getOrNull(first)
        if (seg != null && seg.toMs > 0) playFrom(seg.fromMs)
    }

    private fun scrollToRow(row: View) {
        var sv: ScrollView? = null
        var p = row.parent
        while (p != null) { if (p is ScrollView) { sv = p; break }; p = (p as? View)?.parent }
        val target = sv ?: return
        // ScrollView からの相対 Y を累積で求める
        var y = 0
        var v: View? = row
        while (v != null && v !== target) { y += v.top; v = v.parent as? View }
        val fy = y
        target.post { target.smoothScrollTo(0, fy) }
    }

    // ---------- ③ 発話タイムライン ----------

    private fun showTimeline() {
        if (segments.isEmpty()) return
        val total = segments.lastOrNull { it.toMs > 0 }?.toMs ?: 0
        if (total <= 0) { status.text = "時刻情報がありません"; return }

        val sb = StringBuilder()
        var speech = 0
        var prevEnd = 0
        var gaps = 0
        for (s in segments) {
            if (s.toMs <= 0) continue
            val gap = s.fromMs - prevEnd
            if (gap >= 2000 && prevEnd > 0) {
                gaps++
                sb.append("  — 沈黙 ${gap / 1000}秒 —\n")
            }
            val bars = ((s.toMs - s.fromMs) / 1000).coerceAtLeast(1).coerceAtMost(40)
            sb.append(Transcript.fmt(s.fromMs)).append("  ")
                .append("█".repeat(bars)).append("\n")
            speech += (s.toMs - s.fromMs)
            prevEnd = s.toMs
        }
        val ratio = if (total > 0) speech * 100 / total else 0

        val header = "総時間 ${Transcript.fmt(total)}  発話 ${Transcript.fmt(speech)}" +
                "（${ratio}%）  沈黙区間 ${gaps}回\n" +
                "2秒以上の間を沈黙として表示\n\n"

        val tv = TextView(this).apply {
            text = header + sb.toString()
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("発話タイムライン")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("閉じる", null)
            .show()
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
            val body = buildString {
                for (s in segments) {
                    if (s.toMs > 0) append("[").append(Transcript.fmt(s.fromMs)).append("] ")
                    append(s.text).append("\n")
                }
            }
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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
                audioUri = null; audioName = "-"; nameView.text = "未選択"
                playBtn.isEnabled = false; transBtn.isEnabled = false; delBtn.isEnabled = false
                seek.isEnabled = false; seek.progress = 0; updateTime(0, 0)
                clearSegments()
                status.text = "削除しました"
            } else status.text = "削除できませんでした（この場所は削除に対応していません）"
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
