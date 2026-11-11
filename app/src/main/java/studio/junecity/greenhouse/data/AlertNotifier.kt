package studio.junecity.greenhouse.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.AppPreferencesStore
import kotlin.math.roundToInt

class AlertNotifier(private val context:Context,private val suffix:String=""){
    private val manager=context.getSystemService(NotificationManager::class.java);private val state=context.getSharedPreferences("alert_state$suffix",Context.MODE_PRIVATE)
    init{manager.createNotificationChannel(NotificationChannel("greenhouse_quiet","Greenhouse status",NotificationManager.IMPORTANCE_LOW));manager.createNotificationChannel(NotificationChannel("greenhouse_alerts","Greenhouse alerts",NotificationManager.IMPORTANCE_HIGH))}
    fun evaluate(sensor:GoveeSensor){
        val p=AppPreferencesStore(context,suffix).load().notifications;val now=System.currentTimeMillis();if(!p.masterEnabled||now<p.snoozedUntilEpochMs)return
        val condition=condition(sensor,p);val key="condition_${sensor.id}";val previous=state.getString(key,null);if(condition==null){if(previous!=null)state.edit().remove(key).apply();return}
        val last=state.getLong("sent_${sensor.id}",0);val due=previous!=condition||p.repeatedAlerts&&now-last>=p.repeatMinutes*60_000L
        state.edit().putString(key,condition).apply();if(!due)return
        val channel=if(p.popupsEnabled)"greenhouse_alerts" else "greenhouse_quiet";val builder=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(sensor.name).setContentText(condition).setAutoCancel(true).setOnlyAlertOnce(!p.repeatedAlerts)
        if(p.soundEnabled)builder.setDefaults(NotificationCompat.DEFAULT_SOUND);if(p.vibrationEnabled)builder.setVibrate(longArrayOf(0,250,150,250)) else builder.setVibrate(longArrayOf(0))
        runCatching{manager.notify(sensor.id.hashCode(),builder.build());state.edit().putLong("sent_${sensor.id}",now).apply()}
    }
    private fun condition(s:GoveeSensor,p:NotificationPreferences):String?{if(!s.online&&p.offlineAlerts)return "Sensor offline";val t=s.temperatureF;val h=s.humidityPercent;return when{p.temperatureAlerts&&t!=null&&t<p.lowTemperatureF->"Temperature low: ${t.roundToInt()}°F";p.temperatureAlerts&&t!=null&&t>p.highTemperatureF->"Temperature high: ${t.roundToInt()}°F";p.humidityAlerts&&h!=null&&h<p.lowHumidity->"Humidity low: ${h.roundToInt()}%";p.humidityAlerts&&h!=null&&h>p.highHumidity->"Humidity high: ${h.roundToInt()}%";else->null}}
}
