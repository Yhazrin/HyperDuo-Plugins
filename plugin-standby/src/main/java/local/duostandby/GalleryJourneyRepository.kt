package local.duostandby

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xiaomi 时光 (memories) adapter: the gallery's journey collections live in
 * /data/data/com.miui.gallery/databases/gallery_info.db (journey_collection /
 * journey_member). The public widget provider only exposes the currently
 * selected card, so the full 时光 collection is read from the user's own gallery
 * DB copy through root (read-only; no upload; no library scan).
 */
object GalleryJourneyRepository {
    data class Journey(val id: Long, val name: String, val startMs: Long, val endMs: Long,
                       val coverId: Long?, val mediaIds: List<Long>)
    @Volatile var journeys: List<Journey> = emptyList()
        private set
    private val fetching = AtomicBoolean(false)
    @Volatile private var lastAttempt = 0L

    fun refresh(context: Context) {
        // Integration boundary: opening a clock must not request unrelated private-data Root access.
        if (!context.getSharedPreferences("standby_integration", Context.MODE_PRIVATE).getBoolean("root_gallery_opt_in", false)) { return }
        if (System.currentTimeMillis() - lastAttempt < 10 * 60_000L) return
        if (!fetching.compareAndSet(false, true)) return
        lastAttempt = System.currentTimeMillis()
        Thread {
            val dbFile = File(context.filesDir, "gallery_info.db")
            try {
                // Read-only: stream the DB bytes through root and let the app write its own copy
                // (no root-owned files or chown needed, so the app can always reopen it).
                val process = ProcessBuilder("su", "-M", "-c", "cat /data/data/com.miui.gallery/databases/gallery_info.db").start()
                val err = StringBuilder()
                val errThread = Thread { runCatching { process.errorStream.bufferedReader().use { err.append(it.readText().take(300)) } } }.apply { isDaemon = true; start() }
                process.inputStream.use { input ->
                    dbFile.outputStream().use { output -> input.copyTo(output, 65536) }
                }
                process.waitFor(15, TimeUnit.SECONDS)
                errThread.join(1000)
                Log.d("DuoJourney", "copy result bytes=${dbFile.length()} err=${err.toString().trim()}")
                if (!dbFile.exists() || dbFile.length() < 1024) return@Thread
                val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                db.use { read(it) }
            } catch (t: Throwable) {
                Log.w("DuoJourney", "journey read failed: ${t.javaClass.simpleName}")
            } finally {
                fetching.set(false)
            }
        }.start()
    }

    private fun read(db: SQLiteDatabase) {
        val result = mutableListOf<Journey>()
        db.rawQuery("SELECT _id, name, cover_id, start_time, end_time, local_collection_id FROM journey_collection", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val cover = if (c.isNull(2)) null else c.getLong(2)
                val start = c.getLong(3)
                val end = c.getLong(4)
                val localCollectionId = c.getString(5)
                val media = mutableListOf<Long>()
                if (localCollectionId != null) {
                    db.rawQuery("SELECT media_id FROM journey_member WHERE local_collection_id = ?", arrayOf(localCollectionId)).use { m ->
                        while (m.moveToNext()) media.add(m.getLong(0))
                    }
                }
                result.add(Journey(id, name, start, end, cover, media))
            }
        }
        journeys = result
        Log.d("DuoJourney", "journeys=" + result.joinToString("; ") { "${it.name}:${it.startMs}:${it.mediaIds.size}" })
    }
}
