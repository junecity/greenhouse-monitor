package studio.junecity.greenhouse.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.WeatherReading
import java.time.LocalDate

internal data class SavedEnergyNight(
    val date: LocalDate, val sunrise: Long, val first: Double?, val estimate: Double,
    val finished: Boolean, val stationHours: Double, val fuel: String, val firstFuel: String?
)

// Separate from raw sensor history: one small record per local sunset date survives raw-reading retention.
internal class EnergyArchive(context: Context) : SQLiteOpenHelper(context, "heating_costs.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE costs (
            scope TEXT NOT NULL, zip TEXT NOT NULL, night TEXT NOT NULL, sunrise INTEGER NOT NULL,
            first REAL, firstFuel TEXT, estimate REAL NOT NULL, finished INTEGER NOT NULL,
            stationHours REAL NOT NULL, fuel TEXT NOT NULL, target REAL NOT NULL,
            rate REAL NOT NULL, coefficient REAL NOT NULL, priceArea TEXT NOT NULL, pricePeriod TEXT NOT NULL,
            updated INTEGER NOT NULL, PRIMARY KEY(scope, zip, night))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported energy archive upgrade")

    fun nights(scope: String, zip: String): List<SavedEnergyNight> = readableDatabase.rawQuery(
        "SELECT night,sunrise,first,estimate,finished,stationHours,fuel,firstFuel FROM costs WHERE scope=? AND zip=? ORDER BY night DESC",
        arrayOf(scope, zip)
    ).use { c -> buildList {
        while (c.moveToNext()) add(SavedEnergyNight(LocalDate.parse(c.getString(0)), c.getLong(1),
            if (c.isNull(2)) null else c.getDouble(2), c.getDouble(3), c.getInt(4) == 1,
            c.getDouble(5), c.getString(6), c.getString(7)))
    } }

    fun record(scope: String, house: GreenhouseConfig, weather: WeatherReading, points: List<ThermalPoint>,
               unit: EnergyUnit, quote: FuelPrice, now: Long) {
        if (weather.stale || weather.updatedAtEpochMs <= 0 || weather.solarDays.isEmpty()) return
        val db = writableDatabase
        val currentTarget = learnedTemperature(points)
        val coefficient = heatingCoefficient(house, unit).nominal
        val dates = weather.solarDays.keys.sorted()
        var changed = false
        db.beginTransaction()
        try {
            for (date in dates) {
                if (weather.solarDays[date]?.sunsetMs == null) continue
                val sunrise = weather.solarDays[date.plusDays(1)]?.sunriseMs ?: continue
                val key = arrayOf(scope, weather.zipCode, date.toString())
                val where = "scope=? AND zip=? AND night=?"
                val completed = now >= sunrise
                var target = currentTarget?.fahrenheit
                var costPerDegree = quote.dollars * coefficient
                val values = ContentValues()
                val existing = db.query("costs", arrayOf("target","rate","coefficient","updated","stationHours","finished","estimate","fuel","pricePeriod","priceArea"),
                    where, key, null, null, null).use { c ->
                    if (!c.moveToFirst()) null else EnergyBasis(c.getDouble(0),c.getDouble(1)*c.getDouble(2),
                        c.getLong(3),c.getDouble(4),c.getInt(5)==1,c.getDouble(6),c.getString(7),c.getString(8),c.getString(9))
                }
                if (existing != null && existing.updated > now) continue
                // Freeze the last available price and construction assumptions for completed nights.
                if (existing != null && completed) {
                    target = if (existing.finished) existing.target else
                        learnedTemperature(points.filter { it.date == date })?.fahrenheit ?: existing.target
                    costPerDegree = existing.costPerDegree
                } else {
                    if (completed) {
                        // Backfill only nights supported by actual stable indoor history for that night.
                        target = learnedTemperature(points.filter { it.date == date })?.fahrenheit
                    }
                    values.put("rate", quote.dollars)
                    values.put("coefficient", coefficient)
                    values.put("priceArea", quote.area)
                    values.put("pricePeriod", quote.period)
                    values.put("fuel", unit.fuel)
                }
                val nightTarget = target ?: continue
                val night = NightHeating.night(weather, nightTarget, date, now)
                val estimate = night.degreeHours?.times(costPerDegree) ?: continue
                val observed = night.hours.filter { it.observed }.sumOf { it.hours }
                if (existing != null && existing.finished && observed <= existing.observed) continue
                if (existing != null && observed < existing.observed) continue
                if (existing != null && existing.finished == completed && existing.observed == observed &&
                    existing.estimate == estimate && existing.target == nightTarget &&
                    existing.costPerDegree == costPerDegree && existing.fuel == unit.fuel &&
                    existing.period == quote.period && existing.area == quote.area) continue
                values.put("target", nightTarget)
                values.put("estimate", estimate)
                values.put("stationHours", observed)
                values.put("finished", completed)
                values.put("sunrise", sunrise)
                values.put("updated", now)
                if (existing == null) {
                    values.put("scope", scope)
                    values.put("zip", weather.zipCode)
                    values.put("night", date.toString())
                    if (!completed) {
                        values.put("first", estimate)
                        values.put("firstFuel", unit.fuel)
                    }
                    db.insertOrThrow("costs", null, values)
                } else db.update("costs", values, where, key)
                changed = true
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) revision.value = System.nanoTime()
    }

    private data class EnergyBasis(val target: Double, val costPerDegree: Double, val updated: Long,
                                   val observed: Double, val finished: Boolean, val estimate: Double,
                                   val fuel: String, val period: String, val area: String)
    companion object { val revision = MutableStateFlow(0L) }
}
