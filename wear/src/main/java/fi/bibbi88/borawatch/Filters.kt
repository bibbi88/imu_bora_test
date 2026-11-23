package fi.bibbi88.borawatch

import kotlin.math.pow

const val G = 9.80665

fun altFromPa(p: Double): Double = 44330.0 * (1.0 - (p / 101325.0).pow(1.0 / 5.255))

/** Tuning, same defaults as the web lab. */
object Cfg {
    var baroSigma = 0.30      // m
    var baroQ = 0.50          // baro-only process noise (smoothing)
    var accSigma = 0.35       // m/s²
    var biasDrift = 0.02      // m/s²/√s
}

/** Baro only: state [h, v], white-noise acceleration model. */
class BaroKF {
    var init = false; private set
    var h = 0.0; private set
    var v = 0.0; private set
    private var t = 0.0
    private var p00 = 1.0; private var p01 = 0.0; private var p11 = 1.0

    fun update(time: Double, z: Double) {
        if (!init) { h = z; v = 0.0; t = time; p00 = 1.0; p01 = 0.0; p11 = 1.0; init = true; return }
        val dt = (time - t).coerceIn(1e-3, 1.0); t = time
        h += v * dt
        val q = Cfg.baroQ
        val a00 = p00 + 2 * dt * p01 + dt * dt * p11 + q * dt * dt * dt / 3
        val a01 = p01 + dt * p11 + q * dt * dt / 2
        val a11 = p11 + q * dt
        val s = a00 + Cfg.baroSigma * Cfg.baroSigma
        val k0 = a00 / s; val k1 = a01 / s; val y = z - h
        h += k0 * y; v += k1 * y
        p00 = (1 - k0) * a00; p01 = (1 - k0) * a01; p11 = a11 - k1 * a01
    }
}

/** Baro + IMU: state [h, v, accel bias]. Vertical acceleration drives prediction, baro corrects. */
class FusedKF {
    var init = false; private set
    var h = 0.0; private set
    var v = 0.0; private set
    var b = 0.0; private set
    private var t = 0.0
    private var p = Array(3) { DoubleArray(3) }

    fun predict(time: Double, a: Double) {
        if (!init || a.isNaN()) return
        val dt = time - t
        if (dt <= 0) return
        t = time
        if (dt > 0.5) return
        val u = a - b; val h2 = 0.5 * dt * dt
        h += v * dt + h2 * u; v += u * dt
        val f = arrayOf(doubleArrayOf(1.0, dt, -h2), doubleArrayOf(0.0, 1.0, -dt), doubleArrayOf(0.0, 0.0, 1.0))
        val n = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) for (l in 0..2) s += f[i][k] * p[k][l] * f[j][l]
            n[i][j] = s
        }
        val g = doubleArrayOf(h2, dt, 0.0); val sa2 = Cfg.accSigma * Cfg.accSigma
        for (i in 0..2) for (j in 0..2) n[i][j] += sa2 * g[i] * g[j]
        n[2][2] += Cfg.biasDrift * Cfg.biasDrift * dt
        p = n
    }

    fun update(time: Double, z: Double) {
        if (!init) {
            h = z; v = 0.0; b = 0.0; t = time
            p = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 0.25))
            init = true; return
        }
        val s = p[0][0] + Cfg.baroSigma * Cfg.baroSigma
        val k = doubleArrayOf(p[0][0] / s, p[1][0] / s, p[2][0] / s)
        val y = z - h
        h += k[0] * y; v += k[1] * y; b += k[2] * y
        val r0 = p[0].copyOf()
        for (i in 0..2) for (j in 0..2) p[i][j] -= k[i] * r0[j]
    }
}

/** Baro + acc: gravity direction from a slow low-pass of the accelerometer (no gyro). */
class AccelOnly {
    private var g: DoubleArray? = null
    fun vertical(ax: Double, ay: Double, az: Double): Double {
        val gg = g ?: doubleArrayOf(ax, ay, az).also { g = it }
        val k = 0.02
        gg[0] += k * (ax - gg[0]); gg[1] += k * (ay - gg[1]); gg[2] += k * (az - gg[2])
        val n = Math.sqrt(gg[0] * gg[0] + gg[1] * gg[1] + gg[2] * gg[2]).takeIf { it > 0 } ?: return Double.NaN
        return (ax * gg[0] + ay * gg[1] + az * gg[2]) / n - G
    }
}
