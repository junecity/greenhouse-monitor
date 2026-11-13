package studio.junecity.greenhouse.data

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.*
import java.time.Instant
import java.time.ZoneOffset

class ZoneClimateTest {
    private val now=Instant.parse("2026-11-13T01:00:00Z").toEpochMilli()
    private val a=GoveeSensor("a","H5179","Lower bench",60.0,90.0,true,now)
    private val b=a.copy(id="b",name="Rafter",temperatureF=76.0,humidityPercent=70.0)
    private val g=GreenhouseConfig(shape=GreenhouseShape.TShape,heightFeet=8.0,
        sensorPositions=mapOf("a" to SensorPosition(8.0,4.0,1.0),"b" to SensorPosition(8.0,4.0,7.0)))
    private val state=GreenhouseUiState(sensors=listOf(a,b),preferences=AppPreferences(greenhouses=listOf(g)))
    @Test fun volumeHonorsHeightFootprintAndIndependentMetrics(){
        val probes=ZoneClimate.probes(state,now)
        val t=ZoneClimate.field(g,probes,Metric.Temperature)
        val rh=ZoneClimate.field(g,probes,Metric.Humidity)
        assertTrue(t.isNotEmpty() && t.all { g.containsPoint(it.x,it.y) })
        assertTrue(t.none{it.y>16})
        assertTrue(t.filter{it.z<2}.map{it.value}.average()<t.filter{it.z>6}.map{it.value}.average())
        assertTrue(rh.filter{it.z<2}.map{it.value}.average()>rh.filter{it.z>6}.map{it.value}.average())
    }
    @Test fun expiredWrongHouseAndUnmeasuredHeightCannotColorVolume(){
        val missing=ZoneClimate.probes(state.copy(sensors=listOf(a.copy(updatedAtEpochMs=now-601000),b.copy(greenhouseId="other"))),now)
        assertTrue(missing.isEmpty())
        assertTrue(ZoneClimate.field(g,listOf(ZoneProbe(a,SensorPosition(8.0,4.0))),Metric.Temperature).isEmpty())
    }
    @Test fun insightPrioritizesActualLowAndRejectsStaleForecast(){
        val weather=WeatherReading("06382","Local",44.0,"Clear",now,stale=true,hourlyOutdoorF=mapOf(now+3600000 to 30.0))
        val probes=ZoneClimate.probes(state,now)
        assertTrue(ZoneClimate.insight(state.copy(weather=weather),probes,now).contains("16°F cooler"))
        assertTrue(ZoneClimate.insight(state, listOf(probes[0].copy(sensor=a.copy(temperatureF=39.0))),now).contains("below your"))
        assertTrue(ZoneClimate.insight(state,listOf(probes[0]),now).contains("Try a sensor"))
    }
    @Test fun outdoorAdviceNeedsThreeSimilarDaysForIndoorRange(){
        val hour=3600000L; val target=now+hour
        val past=(1..3).map{target-it*24*hour}
        val weather=WeatherReading("06382","Local",47.0,"Clear",now,zone=ZoneOffset.UTC,
            hourlyOutdoorF=(past+target).associateWith{34.0})
        val history=past.flatMapIndexed{i,time->listOf(time,time+1800000).map{HistoryReading("a","Bench",57.0+i,80.0,it)}}
        val probes=ZoneClimate.probes(state,now)
        fun note(rows:List<HistoryReading>)=ZoneClimate.insight(state.copy(weather=weather,history=rows),probes,now)
        assertTrue(note(history).contains("57–59°F"))
        assertTrue(note(history.take(4)).contains("Watch Lower bench"))
        assertTrue(note(history.map{it.copy(sensorId="other")}).contains("Watch Lower bench"))
    }
}
