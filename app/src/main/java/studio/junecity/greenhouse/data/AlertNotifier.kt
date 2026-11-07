package studio.junecity.greenhouse.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.R

class AlertNotifier(private val context:Context){
    private val manager=context.getSystemService(NotificationManager::class.java)
    init { manager.createNotificationChannel(NotificationChannel("greenhouse_alerts","Greenhouse alerts",NotificationManager.IMPORTANCE_HIGH)) }
    fun evaluate(sensor:GoveeSensor){
        val temperature=sensor.temperatureF ?: return; val humidity=sensor.humidityPercent ?: return
        val problem=when{temperature<45->"Temperature is low: %.1f°F".format(temperature);temperature>95->"Temperature is high: %.1f°F".format(temperature);humidity<30->"Humidity is low: %.0f%%".format(humidity);humidity>85->"Humidity is high: %.0f%%".format(humidity);else->null} ?: return
        val notification=NotificationCompat.Builder(context,"greenhouse_alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(sensor.name).setContentText(problem).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        runCatching{manager.notify(sensor.id.hashCode(),notification)}
    }
}
