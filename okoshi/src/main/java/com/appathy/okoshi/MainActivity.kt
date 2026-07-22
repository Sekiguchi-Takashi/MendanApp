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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/**
 * v0.2
 * 録音（最長5分・16kHzモノラルWAV直書き） → Termux の okoshi を起動して
 * whisper.cpp medium-q5_0 で文字起こし。
 *
 * v0.1 の不具合:
 *   com.termux.permission.RUN_COMMAND は protectionLevel="dangerous" のため、
 *   マニフェスト宣言だけでは付与されない。実行時要求が必須。
 *   → ask() と transcribe() で要求するよう修正。
 *   → 拒否された場合の逃げ道としてコマンドのクリップボードコピーを追加。
 */
class MainActivity : Activity() {

    companion object {
        const val PERM_TERMUX = "com.termux.permission.RUN_COMMAND"
        const val OKOSHI = "/data/data/com.termux/files/home/bin/okoshi"
        private const val REQ_BASE = 1
        private const val REQ_TERMUX = 2
    }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var timeView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var recBtn: Button
    private lateinit var runBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var log: TextView

    private var lastPath: String? = null
    private var ticking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (d * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "面談録音  v0.2"
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

        runBtn = Button(this).apply {
            text = "Termuxで文字起こし"
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
        restoreLast()
    }

    /** 前回の録音が残っていれば復帰させる（権限許可後の再入場を想定）。 */
    private fun restoreLast() {
        val f = RecService.lastFile
        if (f != null && !f.startsWith("ERROR:")) {
            lastPath = f
            runBtn.isEnabled = true
            copyBtn.isEnabled = true
        }
    }

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
        // Termux が定義する dangerous 権限。実行時要求が必須。
        if (termuxInstalled() && !hasTermuxPerm()) {
            need.add(PERM_TERMUX)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_BASE)
    }

    private fun hasTermuxPerm() =
        checkSelfPermission(PERM_TERMUX) == PackageManager.PERMISSION_GRANTED

    private fun termuxInstalled(): Boolean = try {
        packageManager.getPackageInfo("com.termux", 0)
        true
    } catch (e: Exception) {
        false
    }

    override fun onRequestPermissionsResult(
        req: Int, perms: Array<out String>, res: IntArray
    ) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_TERMUX) {
            if (hasTermuxPerm()) transcribe()
            else log.text = "Termuxの実行権限が許可されませんでした。\n" +
                    "「コマンドをコピー」でTermuxに貼り付けて実行してください。"
        }
    }

    private fun toggle() {
        if (RecService.running) {
            startService(Intent(this, RecService::class.java).setAction(RecService.ACTION_STOP))
            recBtn.isEnabled = false
            log.text = "保存中..."
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ask()
                return
            }
            lastPath = null
            runBtn.isEnabled = false
            copyBtn.isEnabled = false
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
                            log.text = "保存しました\n$f\n\n" +
                                    "medium で 5〜15分ほどかかります。"
                        }
                    }
                    return
                }
                ui.postDelayed(this, 200)
            }
        })
    }

    private fun command(): String {
        val p = lastPath ?: return ""
        return "termux-wake-lock; okoshi \"$p\"; termux-wake-unlock"
    }

    private fun copyCommand() {
        if (lastPath == null) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("okoshi", command()))
        log.text = "コピーしました。Termuxを開いて貼り付け、実行してください。\n\n" + command()
    }

    /** Termux の ~/bin/okoshi を叩く。推論もモデルもすべて Termux 側。 */
    private fun transcribe() {
        val path = lastPath ?: return

        if (!termuxInstalled()) {
            log.text = "Termuxが見つかりません。"
            return
        }
        if (!hasTermuxPerm()) {
            // dangerous 権限なので実行時に要求する。これが v0.1 で抜けていた。
            requestPermissions(arrayOf(PERM_TERMUX), REQ_TERMUX)
            return
        }

        try {
            val i = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", OKOSHI)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(path))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            log.text = "Termuxへ送りました。\n" +
                    "進行状況はTermuxの画面で確認できます。\n\n" +
                    "出力先: /sdcard/Download/okoshi/"
        } catch (e: Exception) {
            log.text = "Termux起動に失敗しました。\n${e.message}\n\n" +
                    "「コマンドをコピー」で手動実行してください。\n\n" +
                    "確認事項:\n" +
                    "1) ~/.termux/termux.properties に\n" +
                    "   allow-external-apps=true\n" +
                    "2) termux-reload-settings を実行\n" +
                    "3) ~/bin/okoshi に実行権限"
        }
    }
}
