package com.haojay.leapmotor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一条采样快照。`signalTime` 是车机上报时间，是去重键。 */
data class Sample(
    val signalTime: Long,
    val collectTime: Long,
    val soc: Double?,
    val range: Double?,
    val odo: Double?,
    val speed: Double?,
    val gear: Int?,
    val charging: Boolean,
    val state: String,          // parked / driving / charging
    val lat: Double?,
    val lon: Double?,
    val locked: Boolean?,
)

/** 一段行程（由状态翻转切出来的）。 */
data class Trip(
    val id: Long,
    val startMs: Long,
    val endMs: Long?,
    val startOdo: Double?,
    val endOdo: Double?,
    val startSoc: Double?,
    val endSoc: Double?,
    val distance: Double,
    val socUsed: Double,
)

/**
 * 本地库：采样 + 行程。
 *
 * 为什么不用 Room：只需要两张表、几个查询，加 Room 会引入注解处理器和
 * 一次完整的依赖解析；这里直接用 SQLiteOpenHelper 更轻、编译更快。
 */
class LpDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "leapmotor.db"
        private const val VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                signal_time INTEGER NOT NULL,
                collect_time INTEGER NOT NULL,
                soc REAL, range_km REAL, odo REAL, speed REAL, gear INTEGER,
                charging INTEGER NOT NULL, state TEXT NOT NULL,
                lat REAL, lon REAL, locked INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX idx_samples_signal ON samples(signal_time)")
        db.execSQL(
            """
            CREATE TABLE trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER,
                start_odo REAL, end_odo REAL,
                start_soc REAL, end_soc REAL,
                distance REAL NOT NULL DEFAULT 0,
                soc_used REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // 目前只有 v1，未来加字段时在这里做迁移
    }

    /** 已入库的最新车机上报时间，用于去重。 */
    fun latestSignalTime(): Long {
        readableDatabase.rawQuery("SELECT MAX(signal_time) FROM samples", null).use {
            return if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }

    /** 插入采样；同一 signal_time 已存在则忽略。返回是否真的写入。 */
    fun insertSample(s: Sample): Boolean {
        val cv = ContentValues().apply {
            put("signal_time", s.signalTime)
            put("collect_time", s.collectTime)
            s.soc?.let { put("soc", it) }
            s.range?.let { put("range_km", it) }
            s.odo?.let { put("odo", it) }
            s.speed?.let { put("speed", it) }
            s.gear?.let { put("gear", it) }
            put("charging", if (s.charging) 1 else 0)
            put("state", s.state)
            s.lat?.let { put("lat", it) }
            s.lon?.let { put("lon", it) }
            s.locked?.let { put("locked", if (it) 1 else 0) }
        }
        return writableDatabase.insertWithOnConflict(
            "samples", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun sampleCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** 开一段行程。 */
    fun openTrip(s: Sample): Long {
        val cv = ContentValues().apply {
            put("start_ms", s.signalTime)
            put("start_odo", s.odo)
            put("start_soc", s.soc)
        }
        return writableDatabase.insert("trips", null, cv)
    }

    /** 收尾最近一条未结束的行程。 */
    fun closeTrip(s: Sample): Boolean {
        val db = writableDatabase
        db.rawQuery(
            "SELECT id, start_odo, start_soc FROM trips WHERE end_ms IS NULL ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            if (!c.moveToFirst()) return false
            val id = c.getLong(0)
            val startOdo = if (c.isNull(1)) null else c.getDouble(1)
            val startSoc = if (c.isNull(2)) null else c.getDouble(2)
            val dist = if (startOdo != null && s.odo != null) (s.odo - startOdo).coerceAtLeast(0.0) else 0.0
            val used = if (startSoc != null && s.soc != null) (startSoc - s.soc).coerceAtLeast(0.0) else 0.0
            val cv = ContentValues().apply {
                put("end_ms", s.signalTime)
                put("end_odo", s.odo)
                put("end_soc", s.soc)
                put("distance", dist)
                put("soc_used", used)
            }
            return db.update("trips", cv, "id = ?", arrayOf(id.toString())) > 0
        }
    }

    fun recentTrips(limit: Int = 30): List<Trip> {
        val out = mutableListOf<Trip>()
        readableDatabase.rawQuery(
            "SELECT id,start_ms,end_ms,start_odo,end_odo,start_soc,end_soc,distance,soc_used " +
                "FROM trips WHERE end_ms IS NOT NULL ORDER BY start_ms DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Trip(
                        id = c.getLong(0),
                        startMs = c.getLong(1),
                        endMs = if (c.isNull(2)) null else c.getLong(2),
                        startOdo = if (c.isNull(3)) null else c.getDouble(3),
                        endOdo = if (c.isNull(4)) null else c.getDouble(4),
                        startSoc = if (c.isNull(5)) null else c.getDouble(5),
                        endSoc = if (c.isNull(6)) null else c.getDouble(6),
                        distance = c.getDouble(7),
                        socUsed = c.getDouble(8),
                    )
                )
            }
        }
        return out
    }
}
