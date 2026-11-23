package fi.bibbi88.borawatch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {

    // ---------- modes ----------
    private val modeNames = arrayOf("Baro only", "Baro + acc", "Baro + acc + gyro")
    private val modeColors = intArrayOf(0xFFF0A24A.toInt(), 0xFFC48BFF.toInt(), 0xFF4FB3FF.toInt())
    @Volatile private var mode = 2
    // XCTrack test: pressure frozen, vario field = fake ±2 m/s sine (20 s period)
    @Volatile private var xcTest = false
    private var testPa = Double.NaN
    private var testT0 = 0L

    // ---------- filters (touched only on the sensor thread) ----------
    private val baro = BaroKF()
    private val fAcc = FusedKF()
    private val fGyro = FusedKF()
    private val accOnly = AccelOnly()
    private val rot = FloatArray(9)
    private var haveRot = false
    private var vAcc = Double.NaN
    private var vGyro = Double.NaN
    @Volatile private var lastPa = Double.NaN
    @Volatile private var lastAlt = Double.NaN

    // ---------- rates ----------
    private var nP = 0; private var nA = 0; private var nR = 0
    @Volatile private var hzP = 0; @Volatile private var hzA = 0; @Volatile private var hzR = 0
    private var rateT0 = 0L

    // ---------- infrastructure ----------
    private lateinit var sm: SensorManager
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private val ui = Handler(Looper.getMainLooper())
    private var ble: BleUart? = null
    @Volatile private var bleStatus = "BLE: off"
    private val beeper = Beeper { climb(mode) }
    private var beepOn = false
    private var wakeLock: PowerManager.WakeLock? = null

    // ---------- views ----------
    private lateinit var tvMain: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvAll: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvBle: TextView
    private lateinit var btnMode: Button
    private lateinit var btnBeep: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null) mode = 1

        sensorThread = HandlerThread("sensors").apply { start() }
        sensorHandler = Handler(sensorThread.looper)
        startSensors()

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoraWatch:vario").apply { acquire() }

        if (Build.VERSION.SDK_INT >= 31) {
            val need = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isEmpty()) startBle() else requestPermissions(need.toTypedArray(), 1)
        } else startBle()

        sensorHandler.postDelayed(sendTask, 100)
        ui.post(uiTask)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBle()
        else bleStatus = "BLE: permission denied"
    }

    private fun startBle() {
        ble = BleUart(this) { s -> bleStatus = s }.also { it.start() }
    }

    // ---------- sensors ----------
    private fun startSensors() {
        val p = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (p == null) { bleStatus = "No barometer!"; return }
        sm.registerListener(this, p, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 10_000, sensorHandler) }
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, 10_000, sensorHandler) }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp / 1e9
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                nP++
                val pa = e.values[0] * 100.0
                if (pa < 30000 || pa > 120000) return
                val h = altFromPa(pa)
                lastPa = pa; lastAlt = h
                baro.update(t, h)
                fAcc.predict(t, vAcc); if (fAcc.init || !vAcc.isNaN()) fAcc.update(t, h)
                fGyro.predict(t, vGyro); if (fGyro.init || !vGyro.isNaN()) fGyro.update(t, h)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                nR++
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                haveRot = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                nA++
                val ax = e.values[0].toDouble(); val ay = e.values[1].toDouble(); val az = e.values[2].toDouble()
                vAcc = accOnly.vertical(ax, ay, az)
                // rot maps device → world (world z = up): third row gives the vertical component
                vGyro = if (haveRot) rot[6] * ax + rot[7] * ay + rot[8] * az - G else Double.NaN
                fAcc.predict(t, vAcc)
                fGyro.predict(t, vGyro)
            }
        }
        val now = System.currentTimeMillis()
        if (rateT0 == 0L) rateT0 = now
        if (now - rateT0 >= 2000) {
            val s = (now - rateT0) / 1000.0
            hzP = (nP / s).roundToInt(); hzA = (nA / s).roundToInt(); hzR = (nR / s).roundToInt()
            nP = 0; nA = 0; nR = 0; rateT0 = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun climb(m: Int): Double = when (m) {
        0 -> if (baro.init) baro.v else Double.NaN
        1 -> if (fAcc.init) fAcc.v else Double.NaN
        else -> if (fGyro.init) fGyro.v else Double.NaN
    }

    // ---------- $LK8EX1 at 10 Hz, same as Bora ----------
    private val sendTask = object : Runnable {
        override fun run() {
            var pa = lastPa
            if (xcTest && !testPa.isNaN()) pa = testPa
            if (!pa.isNaN()) {
                val v = if (xcTest) 2.0 * Math.sin(2 * Math.PI * (System.currentTimeMillis() - testT0) / 20000.0) else climb(mode)
                val vario = if (v.isNaN()) "9999" else (v * 100).roundToInt().toString()
                val bat = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val body = "LK8EX1,${pa.roundToInt()},${lastAlt.roundToInt()},$vario,99,${1000 + bat.coerceIn(0, 100)},"
                var cs = 0; for (c in body) cs = cs xor c.code
                ble?.send("\$$body*${String.format(Locale.US, "%02X", cs)}\r\n")
            }
            sensorHandler.postDelayed(this, 100)
        }
    }

    // ---------- UI ----------
    private fun fmt(v: Double) = if (v.isNaN()) "–.–" else String.format(Locale.US, "%+.1f", v)

    private val uiTask = object : Runnable {
        override fun run() {
            tvMain.text = fmt(climb(mode))
            tvMain.setTextColor(modeColors[mode])
            tvMode.text = if (xcTest) "XCTRACK TEST" else modeNames[mode]
            if (xcTest) {
                val f = 2.0 * Math.sin(2 * Math.PI * (System.currentTimeMillis() - testT0) / 20000.0)
                tvMain.text = fmt(f); tvMain.setTextColor(Color.RED)
            }
            tvAll.text = "B ${fmt(climb(0))}  A ${fmt(climb(1))}  G ${fmt(climb(2))}"
            val alt = if (lastAlt.isNaN()) "–" else "${lastAlt.roundToInt()} m"
            tvInfo.text = "$alt · P $hzP Hz · IMU $hzA Hz"
            tvBle.text = bleStatus
            ui.postDelayed(this, 200)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val pad = (resources.displayMetrics.widthPixels * 0.12).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        fun text(size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
            textSize = size; gravity = Gravity.CENTER; setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        tvMode = text(12f, color = 0xFF8B97A5.toInt())
        tvMain = text(46f, bold = true)
        tvAll = text(11f, color = 0xFFCCCCCC.toInt())
        tvInfo = text(11f, color = 0xFF8B97A5.toInt())
        tvBle = text(11f, color = 0xFF5FD38D.toInt())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false; minHeight = 0; minimumHeight = 0
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 6, 4, 0) }
        }
        btnMode = button("Mode") { if (xcTest) xcTest = false else mode = (mode + 1) % 3 }
        btnMode.setOnLongClickListener {
            xcTest = !xcTest
            if (xcTest) { testPa = lastPa; testT0 = System.currentTimeMillis() }
            true
        }
        btnBeep = button("Beep: off") {
            beepOn = !beepOn
            if (beepOn) beeper.start() else beeper.stop()
            btnBeep.text = if (beepOn) "Beep: on" else "Beep: off"
        }
        row.addView(btnMode); row.addView(btnBeep)

        for (v in listOf<View>(tvMode, tvMain, tvAll, tvInfo, tvBle, row)) root.addView(v)
        setContentView(root)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        sensorHandler.removeCallbacksAndMessages(null)
        sm.unregisterListener(this)
        beeper.stop()
        ble?.stop()
        sensorThread.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
