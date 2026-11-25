package com.doomscroller.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

data class ReelRow(
    val startTs: Long,
    val endTs: Long,
    val sessionStart: Long,
    val commented: Boolean,
    val liked: Boolean,
    val shared: Boolean,
    val author: String?,
) {
    val durationMs: Long get() = (endTs - startTs).coerceAtLeast(0)
}

data class SessionRow(val id: Long, val startTs: Long, val endTs: Long, val reelCount: Int) {
    val durationMs: Long get() = (endTs - startTs).coerceAtLeast(0)
}

data class SurfaceRow(val startTs: Long, val endTs: Long, val surface: String) {
    val durationMs: Long get() = (endTs - startTs).coerceAtLeast(0)
}

/** Plain SQLite. One writer thread; reads happen off the main thread from the UI. */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "doomscroller.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE reels(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER NOT NULL,
                session_start INTEGER NOT NULL,
                commented INTEGER NOT NULL DEFAULT 0,
                liked INTEGER NOT NULL DEFAULT 0,
                shared INTEGER NOT NULL DEFAULT 0,
                author TEXT)"""
        )
        db.execSQL("CREATE INDEX reels_start ON reels(start_ts)")
        db.execSQL(
            """CREATE TABLE sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER NOT NULL,
                reel_count INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX sessions_start ON sessions(start_ts)")
        db.execSQL("CREATE TABLE app_opens(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX opens_ts ON app_opens(ts)")
        createSurfaces(db)
    }

    /** Time on each scrollable Instagram surface: reels, feed (Home), explore. */
    private fun createSurfaces(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE surface_time(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER NOT NULL,
                surface TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX surface_start ON surface_time(start_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSurfaces(db)
    }

    // ---------- writes (call via write { }) ----------

    fun write(block: Db.() -> Unit) = writer.execute {
        runCatching { block() }
        _changes.value = _changes.value + 1
    }

    fun insertReel(r: ReelRow) {
        writableDatabase.insert("reels", null, ContentValues().apply {
            put("start_ts", r.startTs)
            put("end_ts", r.endTs)
            put("session_start", r.sessionStart)
            put("commented", if (r.commented) 1 else 0)
            put("liked", if (r.liked) 1 else 0)
            put("shared", if (r.shared) 1 else 0)
            put("author", r.author)
        })
    }

    /** Inserts when id <= 0, otherwise updates. Returns the row id. */
    fun upsertSession(id: Long, start: Long, end: Long, reels: Int): Long {
        val cv = ContentValues().apply {
            put("start_ts", start); put("end_ts", end); put("reel_count", reels)
        }
        return if (id > 0) {
            writableDatabase.update("sessions", cv, "id=?", arrayOf(id.toString())); id
        } else writableDatabase.insert("sessions", null, cv)
    }

    fun insertOpen(ts: Long) {
        writableDatabase.insert("app_opens", null, ContentValues().apply { put("ts", ts) })
    }

    fun insertSurface(start: Long, end: Long, surface: String) {
        if (end - start < 1000) return
        writableDatabase.insert("surface_time", null, ContentValues().apply {
            put("start_ts", start); put("end_ts", end); put("surface", surface)
        })
    }

    fun wipe() {
        writableDatabase.execSQL("DELETE FROM surface_time")
        writableDatabase.execSQL("DELETE FROM reels")
        writableDatabase.execSQL("DELETE FROM sessions")
        writableDatabase.execSQL("DELETE FROM app_opens")
    }

    // ---------- reads ----------

    fun reelsSince(from: Long): List<ReelRow> {
        val out = ArrayList<ReelRow>()
        readableDatabase.rawQuery(
            "SELECT start_ts,end_ts,session_start,commented,liked,shared,author FROM reels WHERE start_ts>=? ORDER BY start_ts",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) out += ReelRow(
                c.getLong(0), c.getLong(1), c.getLong(2),
                c.getInt(3) == 1, c.getInt(4) == 1, c.getInt(5) == 1,
                if (c.isNull(6)) null else c.getString(6)
            )
        }
        return out
    }

    fun sessionsSince(from: Long): List<SessionRow> {
        val out = ArrayList<SessionRow>()
        readableDatabase.rawQuery(
            "SELECT id,start_ts,end_ts,reel_count FROM sessions WHERE start_ts>=? ORDER BY start_ts",
            arrayOf(from.toString())
        ).use { c -> while (c.moveToNext()) out += SessionRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3)) }
        return out
    }

    fun opensSince(from: Long): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT ts FROM app_opens WHERE ts>=? ORDER BY ts", arrayOf(from.toString()))
            .use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    fun surfacesSince(from: Long): List<SurfaceRow> {
        val out = ArrayList<SurfaceRow>()
        readableDatabase.rawQuery(
            "SELECT start_ts,end_ts,surface FROM surface_time WHERE start_ts>=? ORDER BY start_ts",
            arrayOf(from.toString())
        ).use { c -> while (c.moveToNext()) out += SurfaceRow(c.getLong(0), c.getLong(1), c.getString(2)) }
        return out
    }

    /** count, summed duration, first-ever reel timestamp */
    fun allTime(): Triple<Int, Long, Long?> =
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(end_ts-start_ts),0), MIN(start_ts) FROM reels", null)
            .use { c ->
                c.moveToFirst()
                Triple(c.getInt(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
            }

    fun todayTotals(dayStart: Long): Triple<Int, Long, Int> {
        val (n, ms) = readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(end_ts-start_ts),0) FROM reels WHERE start_ts>=?",
            arrayOf(dayStart.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) to c.getLong(1) }
        val opens = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM app_opens WHERE ts>=?", arrayOf(dayStart.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        return Triple(n, ms, opens)
    }

    companion object {
        @Volatile private var instance: Db? = null
        private val writer = Executors.newSingleThreadExecutor()
        private val _changes = MutableStateFlow(0L)
        /** Bumps after every write so the UI can refresh. */
        val changes: StateFlow<Long> = _changes

        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(ctx).also { instance = it }
        }
    }
}
