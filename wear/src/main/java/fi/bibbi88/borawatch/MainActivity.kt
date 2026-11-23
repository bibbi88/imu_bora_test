package fi.bibbi88.borawatch

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Display and controls only. The vario runs in VarioService / Engine. */
class MainActivity : Activity() {

    private val modeColors = intArrayOf(0xFFF0A24A.toInt(), 0xFFC48BFF.toInt(), 0xFF4FB3FF.toInt())
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvMain: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvAll: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvBle: TextView
    private lateinit var btnMode: Button
    private lateinit var btnBeep: Button
    private lateinit var btnRun: Button
    private var permDenied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_ADVERTISE
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) VarioService.start(this) else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Bluetooth is required; the notification permission is optional (the service runs anyway)
        val btOk = Build.VERSION.SDK_INT < 31 ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
             checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        if (btOk) VarioService.start(this) else permDenied = true
    }

    override fun onResume() { super.onResume(); ui.post(uiTask) }
    override fun onPause() { ui.removeCallbacks(uiTask); super.onPause() }

    private fun fmt(v: Double) = if (v.isNaN()) "–.–" else String.format(Locale.US, "%+.1f", v)

    private val uiTask = object : Runnable {
        override fun run() {
            val e = Engine
            if (e.xcTest) {
                tvMode.text = "XCTRACK TEST"
                tvMain.text = fmt(e.testValue()); tvMain.setTextColor(Color.RED)
            } else {
                tvMode.text = Engine.modeNames[e.mode]
                tvMain.text = fmt(e.climb(e.mode)); tvMain.setTextColor(modeColors[e.mode])
            }
            tvAll.text = "B ${fmt(e.climb(0))}  A ${fmt(e.climb(1))}  G ${fmt(e.climb(2))}"
            val alt = if (e.lastAlt.isNaN()) "–" else "${e.lastAlt.roundToInt()} m"
            tvInfo.text = "$alt · P ${e.hzP} Hz · IMU ${e.hzA} Hz"
            tvBle.text = when {
                permDenied -> "Bluetooth permission denied"
                !e.running -> "Stopped"
                else -> e.bleStatus
            }
            btnBeep.text = if (e.beepOn) "Beep: on" else "Beep: off"
            btnRun.text = if (e.running) "Stop" else "Start"
            ui.postDelayed(this, 200)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val pad = (resources.displayMetrics.widthPixels * 0.12).toInt()
            setPadding(pad, pad / 3, pad, pad / 3)
        }
        fun text(size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
            textSize = size; gravity = Gravity.CENTER; setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        tvMode = text(12f, color = 0xFF8B97A5.toInt())
        tvMain = text(44f, bold = true)
        tvAll = text(11f, color = 0xFFCCCCCC.toInt())
        tvInfo = text(11f, color = 0xFF8B97A5.toInt())
        tvBle = text(11f, color = 0xFF5FD38D.toInt())

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 4, 4, 0) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnMode = button("Mode") { Engine.nextMode() }
        btnMode.setOnLongClickListener { Engine.toggleTest(); true }
        btnBeep = button("Beep: off") { Engine.toggleBeep() }
        row.addView(btnMode); row.addView(btnBeep)

        btnRun = button("Stop") {
            if (Engine.running) VarioService.stop(this) else VarioService.start(this)
        }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRun.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        row2.addView(btnRun)

        for (v in listOf<View>(tvMode, tvMain, tvAll, tvInfo, tvBle, row, row2)) root.addView(v)
        setContentView(root)
    }
}
