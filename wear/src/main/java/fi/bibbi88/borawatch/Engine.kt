package fi.bibbi88.borawatch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The vario itself: sensors → filters → $LK8EX1 over BLE (+ optional beep).
 * Runs inside VarioService so it keeps working with the screen off;
 * the activity only reads its state and flips its switches.
 */
object Engine : SensorEventListener {

    val modeNames = arrayOf("Baro only", "Baro + acc", "Baro + acc + gyro")

    // ---------- switches (set from the UI) ----------
    @Volatile var mode = 2
    @Volatile var xcTest = false; private set
    @Volatile var beepOn = false; private set
    @Volatile var running = false; private set

    // ---------- readouts ----------
    @Volatile var lastPa = Double.NaN; private set
    @Volatile var lastAlt = Double.NaN; private set
    @Volatile var hzP = 0; private set
    @Volatile var hzA = 0; private set
    @Volatile var bleStatus = "BLE: off"; private set

    // ---------- filters (touched only on the sensor thread) ----------
    private var baro = BaroKF()
    private var fAcc = FusedKF()
    private var fGyro = FusedKF()
    private var accOnly = AccelOnly()
    private val rot = FloatArray(9)
    private var haveRot = false
    private var vAcc = Double.NaN
    private var vGyro = Double.NaN
    private var nP = 0; private var nA = 0
    private var rateT0 = 0L

    // XCTrack test: pressure frozen, vario field = fake ±2 m/s sine (20 s period)
    private var testPa = Double.NaN
    private var testT0 = 0L

    // ---------- infrastructure ----------
    private lateinit var app: Context
    private var sm: SensorManager? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var ble: BleUart? = null
    private val beeper = Beeper { climb(mode) }

    fun start(ctx: Context) {
        if (running) return
        app = ctx.applicationContext
        baro = BaroKF(); fAcc = FusedKF(); fGyro = FusedKF(); accOnly = AccelOnly()
        haveRot = false; vAcc = Double.NaN; vGyro = Double.NaN; lastPa = Double.NaN; lastAlt = Double.NaN

        val s = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = s
        if (s.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null && mode == 2) mode = 1

        val t = HandlerThread("vario").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val p = s.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (p == null) bleStatus = "No barometer!"
        else {
            s.registerListener(this, p, SensorManager.SENSOR_DELAY_FASTEST, h)
            s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { s.registerListener(this, it, 10_000, h) }
            s.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { s.registerListener(this, it, 10_000, h) }
        }

        ble = BleUart(app) { msg -> bleStatus = msg }.also { it.start() }
        if (beepOn) beeper.start()
        h.postDelayed(sendTask, 100)
        running = true
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        sm?.unregisterListener(this)
        beeper.stop()
        ble?.stop(); ble = null
        thread?.quitSafely(); thread = null; handler = null
        bleStatus = "Stopped"
    }

    fun nextMode() { if (xcTest) xcTest = false else mode = (mode + 1) % 3 }

    fun toggleTest() {
        xcTest = !xcTest
        if (xcTest) { testPa = lastPa; testT0 = System.currentTimeMillis() }
    }

    fun testValue(): Double = 2.0 * Math.sin(2 * Math.PI * (System.currentTimeMillis() - testT0) / 20000.0)

    fun toggleBeep() {
        beepOn = !beepOn
        if (beepOn && running) beeper.start() else beeper.stop()
    }

    fun climb(m: Int): Double = when (m) {
        0 -> if (baro.init) baro.v else Double.NaN
        1 -> if (fAcc.init) fAcc.v else Double.NaN
        else -> if (fGyro.init) fGyro.v else Double.NaN
    }

    // ---------- sensors ----------
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
            hzP = (nP / s).roundToInt(); hzA = (nA / s).roundToInt()
            nP = 0; nA = 0; rateT0 = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- $LK8EX1 at 10 Hz, same as Bora ----------
    private val sendTask = object : Runnable {
        override fun run() {
            var pa = lastPa
            var alt = lastAlt
            // XCTrack computes climb from the pressure field and ignores the vario field,
            // so in the IMU modes send the pressure that matches the fused altitude.
            val hFused = when {
                xcTest -> Double.NaN
                mode == 1 && fAcc.init -> fAcc.h
                mode == 2 && fGyro.init -> fGyro.h
                else -> Double.NaN
            }
            if (!hFused.isNaN()) { pa = paFromAlt(hFused); alt = hFused }
            if (xcTest && !testPa.isNaN()) pa = testPa
            if (!pa.isNaN()) {
                val v = if (xcTest) testValue() else climb(mode)
                val vario = if (v.isNaN()) "9999" else (v * 100).roundToInt().toString()
                val bat = (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val body = "LK8EX1,${pa.roundToInt()},${alt.roundToInt()},$vario,99,${1000 + bat.coerceIn(0, 100)},"
                var cs = 0; for (c in body) cs = cs xor c.code
                ble?.send("\$$body*${String.format(Locale.US, "%02X", cs)}\r\n")
            }
            handler?.postDelayed(this, 100)
        }
    }
}
