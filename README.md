# IMU Bora test

Single-file web app for testing whether an IMU improves the Bora_vario variometer.

It connects to [Bora_vario](https://github.com/bibbi88/Bora_vario) over Web Bluetooth (Nordic UART, `$LK8EX1`) and runs four varios side by side on the same data:

- **Baro only** – Bora pressure alone
- **Baro + acc** – Kalman fusion with the phone accelerometer (gravity by low-pass)
- **Baro + acc + gyro** – own Mahony attitude filter on raw gyro + accel, i.e. what Bora would compute with a 6-axis IMU
- **Baro + OS fusion** – Android's built-in orientation sensor, as reference

Includes a turning-flight simulator, live lag/jitter comparison, audio beeper per variant, and CSV record/replay for retuning.

## Use

Open via GitHub Pages (HTTPS required) in **Chrome on Android**. iOS Safari has no Web Bluetooth (IMU and simulator still work; Bluefy browser can connect to Bora).

Disconnect Bora from XCTrack/FlyGineer first, and tape Bora to the phone so both feel the same motion.
