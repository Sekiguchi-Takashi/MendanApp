package com.appathy.okoshi

import android.Manifest
import android.app.Activity
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
 * v0.1
 * 録音（最長5分・16kHzモノラルWAV直書き） → Termux の okoshi を起動して
 * whisper.cpp medium-q5_0 で文字起こし。
 *
 * モデルはアプリに載せない。540MB を Android のアプリプロセスに抱えると
 * LMK に落とされる可能性が高いため、推論は Termux 側に置く。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var timeView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var recBtn: Button
    private lateinit var runBtn: Button
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
            text = "面談録音  v0.1"
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
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
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

                if (sec >= RecService.MAX_SECONDS - 30 && RecService.running) {
                    timeView.setTextColor(Color.RED)
                } else {
                    timeView.setTextColor(Color.BLACK)
                }

                if (!RecService.running) {
                    ticking = false
                    levelBar.progress = 0
                    recBtn.text = "録音開始"
                    recBtn.isEnabled = true
                    finish@ run {
                        val f = RecService.lastFile
                        if (f == null) return@run
                        if (f.startsWith("ERROR:")) {
                            log.text = "録音失敗: ${f.removePrefix("ERROR:")}"
                        } else {
                            lastPath = f
                            runBtn.isEnabled = true
                            log.text = "保存しました\n$f\n\n" +
                                    "この長さなら medium で 5〜15分ほどかかります。"
                        }
                    }
                    return
                }
                ui.postDelayed(this, 200)
            }
        })
    }

    /** Termux の ~/bin/okoshi を叩く。推論もモデルもすべて Termux 側。 */
    private fun transcribe() {
        val path = lastPath ?: return
        try {
            val i = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra(
                    "com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/home/bin/okoshi"
                )
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(path))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            log.text = "Termuxへ送りました。\n" +
                    "進行状況はTermuxの画面で確認できます。\n\n" +
                    "完了後の出力先:\n/sdcard/Download/okoshi/"
        } catch (e: Exception) {
            log.text = "Termux起動に失敗しました。\n" +
                    "${e.message}\n\n" +
                    "確認事項:\n" +
                    "1) ~/.termux/termux.properties に\n" +
                    "   allow-external-apps=true\n" +
                    "2) termux-reload-settings を実行\n" +
                    "3) ~/bin/okoshi に実行権限"
        }
    }
}
