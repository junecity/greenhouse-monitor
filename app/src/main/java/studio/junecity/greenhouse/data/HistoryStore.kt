package studio.junecity.greenhouse.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import studio.junecity.greenhouse.model.CURRENT_READING_MAX_AGE_MS
import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.HistoryReading
import studio.junecity.greenhouse.model.DAY_MS
import studio.junecity.greenhouse.model.HISTORY_DAYS

class HistoryStore(context: Context, private val suffix: String = "") : SQLiteOpenHelper(context, "greenhouse_history$suffix.db", null, 4) {
    companion object {
        internal val changes = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT, sensor_id TEXT NOT NULL, sensor_name TEXT NOT NULL, temperature REAL NOT NULL, humidity REAL NOT NULL, recorded_at INTEGER NOT NULL, light TEXT)")
        db.execSQL("CREATE INDEX readings_time ON readings(recorded_at DESC)")
        db.execSQL("CREATE UNIQUE INDEX readings_sensor_time ON readings(sensor_id, recorded_at)")
        createDailyIndex(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) db.execSQL("ALTER TABLE readings ADD COLUMN light TEXT")
        if(oldVersion<2){
            db.execSQL("DELETE FROM readings WHERE id NOT IN (SELECT MIN(id) FROM readings GROUP BY sensor_id, recorded_at)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS readings_sensor_time ON readings(sensor_id, recorded_at)")
        }
        if (oldVersion < 3) {
            createDailyIndex(db)
            db.execSQL("""INSERT INTO daily_last(day,sensor_id,recorded_at)
                SELECT recorded_at / $DAY_MS, sensor_id, MAX(recorded_at) FROM readings GROUP BY 1,2""")
        }
    }

    // The index points to an actual observation. Rebuild it on upgrade without changing raw rows.
    private fun createDailyIndex(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE daily_last(day INTEGER NOT NULL, sensor_id TEXT NOT NULL,
            recorded_at INTEGER NOT NULL, PRIMARY KEY(day,sensor_id))""")
        db.execSQL("""CREATE TRIGGER index_daily_reading AFTER INSERT ON readings BEGIN
            INSERT OR IGNORE INTO daily_last(day,sensor_id,recorded_at)
                VALUES(NEW.recorded_at / $DAY_MS, NEW.sensor_id, NEW.recorded_at);
            UPDATE daily_last SET recorded_at=NEW.recorded_at
                WHERE day=NEW.recorded_at / $DAY_MS AND sensor_id=NEW.sensor_id AND recorded_at<NEW.recorded_at;
            END""")
    }

    private fun prune(db: SQLiteDatabase) {
        val firstDay = System.currentTimeMillis() / DAY_MS - HISTORY_DAYS + 1
        db.delete("readings","recorded_at < ?", arrayOf((firstDay * DAY_MS).toString()))
        db.delete("daily_last","day < ?", arrayOf(firstDay.toString()))
    }

    fun dailyReadings(): List<HistoryReading> {
        val firstDay = System.currentTimeMillis() / DAY_MS - HISTORY_DAYS + 1
        return readableDatabase.rawQuery("""SELECT r.sensor_id,r.sensor_name,r.temperature,r.humidity,r.recorded_at,r.light
            FROM daily_last d JOIN readings r ON r.sensor_id=d.sensor_id AND r.recorded_at=d.recorded_at
            WHERE d.day BETWEEN ? AND ? ORDER BY d.day,d.sensor_id""",
            arrayOf(firstDay.toString(), (firstDay + HISTORY_DAYS - 1).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HistoryReading(cursor.getString(0), cursor.getString(1),
                cursor.getDouble(2), cursor.getDouble(3), cursor.getLong(4), cursor.getString(5))) }
        }
    }

    fun insert(sensor: GoveeSensor) {
        val temperature=sensor.temperatureF ?: return; val humidity=sensor.humidityPercent ?: return
        val inserted = writableDatabase.insertWithOnConflict("readings",null,ContentValues().apply{put("sensor_id",sensor.id);put("sensor_name",sensor.name);put("temperature",temperature);put("humidity",humidity);put("light",sensor.lightLevel);put("recorded_at",sensor.updatedAtEpochMs)},SQLiteDatabase.CONFLICT_IGNORE)
        prune(writableDatabase)
        if (inserted != -1L) changes.tryEmit(suffix)
    }
    fun recent(limit:Int=200):List<HistoryReading> = readRows(null, null, limit.toString())

    /** Preserve the old chart window and include the entire locally retained night for dense sensor fleets. */
    fun dashboardReadings():List<HistoryReading> {
        val since = NightWindow.at(System.currentTimeMillis()).start - READING_LIFETIME
        val selection = "recorded_at >= min(CAST(? AS INTEGER), COALESCE((SELECT recorded_at FROM readings ORDER BY recorded_at DESC LIMIT 1 OFFSET 1999), 0))"
        return readRows(selection, arrayOf(since.toString()), null)
    }

    fun dayReadings(day: Long): List<HistoryReading> =
        readRows("recorded_at >= ? AND recorded_at < ?", arrayOf((day * DAY_MS).toString(), ((day + 1) * DAY_MS).toString()), null)

    internal fun sensorReadingsSince(id: String, since: Long) =
        readRows("sensor_id = ? AND recorded_at >= ?", arrayOf(id, since.toString()), null).asReversed()

    internal fun widgetTrend(sensors: List<GoveeSensor>, house: GreenhouseConfig, now: Long): List<ClimateBucket> {
        val ids = sensors.filter { it.greenhouseId == house.id }.map { it.id }.distinct()
        val history = mutableListOf<HistoryReading>()
        // Seed the left edge with readings still within the Dashboard's freshness window.
        val start = now - WIDGET_WINDOW_MS - CURRENT_READING_MAX_AGE_MS
        for (batch in ids.chunked(500)) {
            val placeholders = batch.joinToString(",") { "?" }
            // SQLite's single MAX picks the other columns from that same row. The
            // (sensor_id, recorded_at) unique index resolves the timestamp per sensor.
            val query = """SELECT sensor_id, MAX(recorded_at), temperature, humidity FROM readings
                WHERE sensor_id IN ($placeholders) AND recorded_at BETWEEN ? AND ?
                AND humidity BETWEEN 0 AND 100 AND abs(temperature) <= 1.7976931348623157e308
                GROUP BY sensor_id, recorded_at / $WIDGET_BUCKET_MS"""
            val args = (batch + listOf(start.toString(), now.toString())).toTypedArray()
            readableDatabase.rawQuery(query, args).use { cursor ->
                while (cursor.moveToNext()) {
                    history += HistoryReading(cursor.getString(0), "", cursor.getDouble(2), cursor.getDouble(3), cursor.getLong(1))
                }
            }
        }
        return climateTrend(sensors, house, history, now)
    }

    fun weekReadings():List<HistoryReading> = readRows("recorded_at >= ?",
        arrayOf((System.currentTimeMillis() - 7L * 24 * 3_600_000).toString()), null)

    private fun readRows(selection:String?, args:Array<String>?, limit:String?):List<HistoryReading>{
        val result=mutableListOf<HistoryReading>()
        val cursor=readableDatabase.query("readings",arrayOf("sensor_id","sensor_name","temperature","humidity","recorded_at","light"),selection,args,null,null,"recorded_at DESC",limit)
        try { while(cursor.moveToNext()) result+=HistoryReading(cursor.getString(0),cursor.getString(1),cursor.getDouble(2),cursor.getDouble(3),cursor.getLong(4),cursor.getString(5)) } finally { cursor.close() }
        return result
    }
    fun importReadings(readings:List<HistoryReading>, merge: Boolean = false){
        val db=writableDatabase
        db.beginTransaction()
        try{
            if (!merge) {
                db.delete("daily_last",null,null)
                db.delete("readings",null,null)
            }
            writeRows(db) { insert -> readings.forEach(insert) }
            if (merge) prune(db)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        changes.tryEmit(suffix)
    }
    fun visitReadings(visit: (HistoryReading) -> Unit) {
        readableDatabase.rawQuery("SELECT sensor_id,sensor_name,temperature,humidity,recorded_at,light FROM readings ORDER BY id", null).use { rows ->
            while (rows.moveToNext()) visit(HistoryReading(rows.getString(0), rows.getString(1), rows.getDouble(2), rows.getDouble(3), rows.getLong(4),rows.getString(5)))
        }
    }

    private fun writeRows(db: SQLiteDatabase, read: ((HistoryReading) -> Unit) -> Unit) {
        db.compileStatement("""INSERT OR IGNORE INTO readings(sensor_id,sensor_name,temperature,humidity,recorded_at,light)
            VALUES(?,?,?,?,?,?)""").use { insert ->
            read { row ->
                insert.bindString(1, row.sensorId)
                insert.bindString(2, row.sensorName)
                insert.bindDouble(3, row.temperatureF)
                insert.bindDouble(4, row.humidityPercent)
                insert.bindLong(5, row.timestampEpochMs)
                if (row.lightLevel == null) insert.bindNull(6) else insert.bindString(6, row.lightLevel)
                if (insert.executeInsert() == -1L && row.lightLevel != null) {
                    db.execSQL("UPDATE readings SET light=? WHERE sensor_id=? AND recorded_at=? AND light IS NULL",
                        arrayOf<Any>(row.lightLevel, row.sensorId, row.timestampEpochMs))
                }
            }
        }
    }

    // Commit the settings journal with the history. An interrupted apply can then be replayed.
    internal fun mergeBackup(metadata: String, read: ((HistoryReading) -> Unit) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeRows(db, read)
            db.execSQL("CREATE TABLE IF NOT EXISTS backup_restore(id INTEGER PRIMARY KEY, metadata TEXT NOT NULL)")
            db.execSQL("INSERT OR REPLACE INTO backup_restore VALUES(1, ?)", arrayOf(metadata))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.tryEmit(suffix)
    }

    internal fun pendingRestore(): String? {
        val db = readableDatabase
        val exists = db.rawQuery("SELECT name FROM sqlite_master WHERE name='backup_restore'", null).use { it.moveToFirst() }
        if (!exists) return null
        return db.rawQuery("SELECT metadata FROM backup_restore WHERE id=1", null).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    internal fun finishRestore() { writableDatabase.execSQL("DROP TABLE IF EXISTS backup_restore") }

    fun clear(){
        writableDatabase.delete("daily_last",null,null)
        writableDatabase.delete("readings",null,null)
        finishRestore()
        changes.tryEmit(suffix)
    }
}
