package app.aaps.plugins.aps.openAPSSMB

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

object PhoneMovementDetector : SensorEventListener {

    private var lastUpdateTimestamp: Long = 0
    //private const val movementThreshold: Double = 10.0 // Adjust this threshold according to your needs
    private const val movementThreshold: Double = 1.05

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(sensorEvent: SensorEvent?) {
        sensorEvent ?: return

        val x = sensorEvent.values[0]
        val y = sensorEvent.values[1]
        val z = sensorEvent.values[2]

        //val acceleration = sqrt((x * x + y * y + z * z).toDouble())
        val acceleration = (x * x + y * y + z * z) / (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH)
        if (acceleration > movementThreshold) {
            lastUpdateTimestamp = System.currentTimeMillis()
        }
    }

    fun phoneMoved() : Boolean {
        return (lastUpdateTimestamp + 15 * 60000) > System.currentTimeMillis()
    }

}
