package studio.junecity.greenhouse.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import java.time.Instant
import java.time.ZoneOffset

class EveningColdWatchTest {
    @Test fun observedDimmingPostsOnceAndRespectsSnoozeAndGreenhouse() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val scope = "light-watch-test"
        val key = "$scope/main"
        val id = "light/$key".hashCode()
        val cache = ctx.getSharedPreferences("heating_station_weather", Context.MODE_PRIVATE)
        val sent = ctx.getSharedPreferences("light_advisories", Context.MODE_PRIVATE)
        val settings = AppPreferences(zipCode = "fixture-light", notifications = NotificationPreferences(lowTemperatureF = 57.0))
        val now = System.currentTimeMillis()
        val transition = now - 6 * LIGHT_MINUTE
        val utcHour = Instant.ofEpochMilli(transition).atZone(ZoneOffset.UTC).hour
        val zone = ZoneOffset.ofHours((18 - utcHour + 36) % 24 - 12)
        val source = GoveeSensor("display","SBHT-103C","Door",64.0,70.0,true,now,lightLevel = "Dark")
        val bench = source.copy(id="bench",name="Bench",sku="H5179",lightLevel=null,temperatureF=59.0)
        val station = JSONArray()
        val rows = (0..3).flatMap { days ->
            val t = transition - days * DAY_MS
            val hour = t / HEATING_HOUR * HEATING_HOUR
            station.put(JSONArray().put(hour).put(35.0).put(hour))
            (-10..(if (days == 0) 6 else 60) step 1).flatMap { m ->
                listOf(HistoryReading(source.id,source.name,64.0,70.0,t + m * LIGHT_MINUTE, if (m < 0) "Bright" else "Dark"),
                    HistoryReading(bench.id,bench.name,60.0 - m.coerceAtLeast(0)/10.0,70.0,t + m * LIGHT_MINUTE))
            }
        }
        val suffix = "_light_advisory_test"
        try {
            manager.cancel(id)
            sent.edit().remove(key).commit()
            cache.edit().putString(settings.zipCode, JSONObject().put("zone",zone.id).put("hours",station).toString()).commit()
            HistoryStore(ctx,suffix).use { db ->
                db.importReadings(rows)
                fun check(p: AppPreferences, probes: List<GoveeSensor>) = EveningColdWatch(ctx).check(db, probes, p, scope)
                check(settings.copy(notifications = settings.notifications.copy(snoozedUntilEpochMs = now + DAY_MS)), listOf(source,bench))
                check(settings,listOf(source,bench.copy(greenhouseId="other")))
                assertFalse(sent.contains(key))
                check(settings,listOf(source,bench))
                assertTrue(sent.contains(key))
                assertEquals("Main Greenhouse · Evening cold watch",manager.activeNotifications.single { it.id == id }
                    .notification.extras.getString(android.app.Notification.EXTRA_TITLE))
                manager.cancel(id)
                check(settings,listOf(source,bench))
                assertFalse(manager.activeNotifications.any { it.id == id })
            }
        } finally {
            manager.cancel(id)
            sent.edit().remove(key).commit()
            cache.edit().remove(settings.zipCode).commit()
            ctx.deleteDatabase("greenhouse_history$suffix.db")
        }
    }
}
