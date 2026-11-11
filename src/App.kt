package com.example.tgchat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }
        fun field(h: String, key: String): EditText = EditText(this).apply {
            setHint(h)
            setText(prefs.getString(key, ""))
            isSingleLine = true
            col.addView(this)
        }

        col.addView(TextView(this).apply {
            text = "Игровой чат для Telegram"
            textSize = 22f
        })
        col.addView(TextView(this).apply {
            text = "1. @BotFather → /newbot → скопируй токен.\n" +
                "2. В BotFather: /setprivacy → выбери бота → Disable (иначе бот не видит сообщения группы).\n" +
                "3. Добавь бота в группу. ID группы начинается с -100…\n"
            setPadding(0, dp(12), 0, dp(12))
        })

        val token = field("Токен бота", "token")
        val chat = field("ID группы (например -1001234567890)", "chat")
        val nick = field("Твой ник в чате", "nick")

        col.addView(Button(this).apply {
            text = "1. Разрешить поверх других окон"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        })
        col.addView(Button(this).apply {
            text = "2. Запустить чат"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(context, "Сначала разреши показ поверх окон", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val t = token.text.toString().trim()
                val c = chat.text.toString().trim()
                val n = nick.text.toString().trim().ifEmpty { "Я" }
                if (t.isEmpty() || c.isEmpty()) {
                    Toast.makeText(context, "Заполни токен и ID группы", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("token", t).putString("chat", c).putString("nick", n).apply()
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                moveTaskToBack(true)
            }
        })
        col.addView(Button(this).apply {
            text = "Остановить чат"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }
}

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var imm: InputMethodManager
    private lateinit var root: FrameLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var built = false
    private var unread = 0
    private var token = ""
    private var chatId = ""
    private var nick = "Я"

    private val palette = intArrayOf(
        Color.rgb(255, 107, 107), Color.rgb(78, 205, 196), Color.rgb(255, 217, 61),
        Color.rgb(107, 203, 119), Color.rgb(77, 150, 255), Color.rgb(199, 125, 255),
        Color.rgb(255, 159, 69)
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (built) return START_STICKY

        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        token = p.getString("token", "") ?: ""
        chatId = p.getString("chat", "") ?: ""
        nick = p.getString("nick", "Я") ?: "Я"

        buildUi()
        built = true
        running = true
        Thread { poll() }.start()
        return START_STICKY
    }

    private fun startAsForeground() {
        val ch = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "Игровой чат", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Игровой чат работает")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun bg(alpha: Int, radius: Int) = GradientDrawable().apply {
        setColor(Color.argb(alpha, 0, 0, 0))
        cornerRadius = dp(radius).toFloat()
    }

    private fun buildUi() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    unfocus()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }

        bubble = TextView(this).apply {
            text = "💬"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg(160, 24)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        makeDraggable(bubble) { expand() }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(90, 10)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(140, 10)
            setPadding(dp(10), dp(4), dp(6), dp(4))
        }
        val title = TextView(this).apply {
            text = "Чат"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        makeDraggable(title, null)
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "—"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { collapse() }
        })
        panel.addView(header)

        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(log) }
        panel.addView(scroll, LinearLayout.LayoutParams(dp(290), dp(170)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(140, 10)
        }
        input = EditText(this).apply {
            hint = "Нажми, чтобы написать…"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    setWindowFocusable(true)
                    ui.postDelayed({
                        input.requestFocus()
                        imm.showSoftInput(input, 0)
                    }, 120)
                }
                false
            }
            setOnEditorActionListener { _, a, _ ->
                if (a == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "➤"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { doSend() }
        })
        panel.addView(row)

        root.addView(panel)
        root.addView(bubble)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(48)
        }
        wm.addView(root, lp)
        addMessage("Система", "Чат подключён. Пиши!")
    }

    private fun makeDraggable(v: View, onTap: (() -> Unit)?) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = sx + dx
                        lp.y = sy + dy
                        wm.updateViewLayout(root, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                }
            }
            true
        }
    }

    private fun setWindowFocusable(on: Boolean) {
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, lp)
    }

    private fun unfocus() {
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        input.clearFocus()
        setWindowFocusable(false)
    }

    private fun collapse() {
        unfocus()
        panel.visibility = View.GONE
        bubble.visibility = View.VISIBLE
    }

    private fun expand() {
        unread = 0
        bubble.text = "💬"
        bubble.visibility = View.GONE
        panel.visibility = View.VISIBLE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun colorFor(name: String) = palette[(name.hashCode() and 0x7fffffff) % palette.size]

    private fun addMessage(name: String, text: String) {
        val s = SpannableStringBuilder()
        s.append("$name: ")
        s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(colorFor(name)), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append(text)
        val tv = TextView(this).apply {
            this.text = s
            setTextColor(Color.WHITE)
            textSize = 13f
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        log.addView(tv)
        while (log.childCount > 60) log.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (panel.visibility != View.VISIBLE) {
            unread++
            bubble.text = "💬 $unread"
        }
    }

    private fun doSend() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        addMessage(nick, t)
        unfocus()
        Thread {
            try {
                val data = "chat_id=" + URLEncoder.encode(chatId, "UTF-8") +
                    "&text=" + URLEncoder.encode("$nick: $t", "UTF-8")
                val c = URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.outputStream.use { it.write(data.toByteArray()) }
                val code = c.responseCode
                if (code != 200) ui.post { addMessage("⚠", "Не отправилось (код $code)") }
                c.disconnect()
            } catch (e: Exception) {
                ui.post { addMessage("⚠", "Нет связи") }
            }
        }.start()
    }

    private fun poll() {
        var offset = 0L
        var first = true
        var errShown = false
        while (running) {
            try {
                val skip = first
                val url = "https://api.telegram.org/bot$token/getUpdates?timeout=" +
                    (if (skip) 0 else 25) + "&offset=" + (if (skip) -1 else offset) +
                    "&allowed_updates=%5B%22message%22%5D"
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 40000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                errShown = false
                first = false
                val arr = JSONObject(body).getJSONArray("result")
                for (i in 0 until arr.length()) {
                    val up = arr.getJSONObject(i)
                    offset = up.getLong("update_id") + 1
                    if (skip) continue
                    val m = up.optJSONObject("message") ?: continue
                    if (m.getJSONObject("chat").getLong("id").toString() != chatId) continue
                    val text = m.optString("text", "")
                    if (text.isEmpty()) continue
                    val name = m.optJSONObject("from")?.optString("first_name") ?: "?"
                    ui.post { addMessage(name, text) }
                }
            } catch (e: Exception) {
                if (!running) break
                if (!errShown) {
                    errShown = true
                    ui.post { addMessage("⚠", "Нет связи или неверный токен") }
                }
                try {
                    Thread.sleep(3000)
                } catch (e2: InterruptedException) {
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        if (built) {
            try {
                wm.removeView(root)
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }
}
