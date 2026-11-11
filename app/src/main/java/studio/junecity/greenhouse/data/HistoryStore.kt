package studio.junecity.greenhouse.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import studio.junecity.greenhouse.model.GoveeSensor
import studio.junecity.greenhouse.model.HistoryReading

class HistoryStore(context: Context, private val suffix: String = "") : SQLiteOpenHelper(context, "greenhouse_history$suffix.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT, sensor_id TEXT NOT NULL, sensor_name TEXT NOT NULL, temperature REAL NOT NULL, humidity REAL NOT NULL, recorded_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX readings_time ON readings(recorded_at DESC)")
        db.execSQL("CREATE UNIQUE INDEX readings_sensor_time ON readings(sensor_id, recorded_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2){
            db.execSQL("DELETE FROM readings WHERE id NOT IN (SELECT MIN(id) FROM readings GROUP BY sensor_id, recorded_at)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS readings_sensor_time ON readings(sensor_id, recorded_at)")
        }
    }

    fun insert(sensor: GoveeSensor) {
        val temperature=sensor.temperatureF ?: return; val humidity=sensor.humidityPercent ?: return
        writableDatabase.insertWithOnConflict("readings",null,ContentValues().apply{put("sensor_id",sensor.id);put("sensor_name",sensor.name);put("temperature",temperature);put("humidity",humidity);put("recorded_at",sensor.updatedAtEpochMs)},SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.delete("readings","recorded_at < ?",arrayOf((System.currentTimeMillis()-30L*24*60*60*1000).toString()))
    }
    fun recent(limit:Int=200):List<HistoryReading>{
        val result=mutableListOf<HistoryReading>()
        val cursor=readableDatabase.query("readings",arrayOf("sensor_id","sensor_name","temperature","humidity","recorded_at"),null,null,null,null,"recorded_at DESC",limit.toString())
        try { while(cursor.moveToNext()) result+=HistoryReading(cursor.getString(0),cursor.getString(1),cursor.getDouble(2),cursor.getDouble(3),cursor.getLong(4)) } finally { cursor.close() }
        return result
    }
    fun importReadings(readings:List<HistoryReading>){
        val db=writableDatabase
        db.beginTransaction()
        try{
            db.delete("readings",null,null)
            readings.forEach{reading->
                db.insertWithOnConflict("readings",null,ContentValues().apply{put("sensor_id",reading.sensorId);put("sensor_name",reading.sensorName);put("temperature",reading.temperatureF);put("humidity",reading.humidityPercent);put("recorded_at",reading.timestampEpochMs)},SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun clear(){writableDatabase.delete("readings",null,null)}
}
