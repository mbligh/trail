package net.pocketmap
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import android.view.Surface

/** Heading of the displayed screen's top edge, corrected to true north. */
class PhoneHeading(context: Context, private val screenRotation: () -> Int, private val location: () -> Location?, private val onHeading: (Float?) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val rotation = FloatArray(9)
    private val screen = FloatArray(9)
    private var filtered: Float? = null
    private var lastTime = 0L
    private var declinationTime = 0L
    private var declination = 0f
    fun start() { reset(); sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stop() { manager.unregisterListener(this); reset() }
    private fun reset() { filtered = null; lastTime = 0L; onHeading(null) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) reset() }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { reset(); return }
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val axes = when (screenRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(rotation, axes.first, axes.second, screen)) { reset(); return }
        val magnetic = HeadingMath.azimuth(screen[1], screen[4])
        if (magnetic == null || !magnetic.isFinite()) { reset(); return }
        val now = SystemClock.elapsedRealtime()
        location()?.let { fix ->
            if (declinationTime == 0L || now - declinationTime > 60_000L) {
                declination = GeomagneticField(fix.latitude.toFloat(), fix.longitude.toFloat(), (if (fix.hasAltitude()) fix.altitude else 0.0).toFloat(), System.currentTimeMillis()).declination
                declinationTime = now
            }
        }
        val target = HeadingMath.normalize(magnetic + declination)
        val amount = (1.0 - kotlin.math.exp(-(now - lastTime).toDouble() / 180.0)).toFloat()
        filtered = filtered?.takeIf { now - lastTime < 5_000L }?.let { HeadingMath.smooth(it, target, amount) } ?: target
        lastTime = now
        onHeading(filtered)
    }
}
