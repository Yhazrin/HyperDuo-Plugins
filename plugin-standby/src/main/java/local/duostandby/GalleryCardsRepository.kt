package local.duostandby

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xiaomi 时光 stories: gallery_sub.db Card holds 254 story cards (title/date and
 * AI-selected images). The images are sha1 identities; gallery.db cloud maps each
 * sha1 to its file path, so the Photos face can use the real story photos instead
 * of the single public card. Read-only via `su -M` (app namespace hides the DBs).
 */
object GalleryCardsRepository {
    data class Card(val id: Long, val title: String, val date: String, val photos: List<String>)
    @Volatile var cards: List<Card> = emptyList()
        private set
    private val fetching = AtomicBoolean(false)
    @Volatile private var lastAttempt = 0L

    fun refresh(context: Context) {
        // Integration boundary: opening a clock must not request unrelated private-data Root access.
        if (!context.getSharedPreferences("standby_integration", Context.MODE_PRIVATE).getBoolean("root_gallery_opt_in", false)) { cards = emptyList(); return }
        if (System.currentTimeMillis() - lastAttempt < 30 * 60_000L) return
        if (!fetching.compareAndSet(false, true)) return
        lastAttempt = System.currentTimeMillis()
        Thread {
            try {
                val sub = copyDb(context, "gallery_sub.db", 15_000_000) ?: return@Thread
                val gallery = copyDb(context, "gallery.db", 40_000_000) ?: return@Thread
                val paths = mutableMapOf<String, String>()
                SQLiteDatabase.openDatabase(gallery.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT sha1, thumbnailFile FROM cloud WHERE thumbnailFile IS NOT NULL", null).use { c ->
                        while (c.moveToNext()) {
                            val sha = c.getString(0) ?: continue
                            val path = c.getString(1) ?: continue
                            if (path.startsWith("/storage/")) paths[sha] = path
                        }
                    }
                }
                val result = mutableListOf<Card>()
                SQLiteDatabase.openDatabase(sub.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT _id, title, description, extras FROM Card ORDER BY createTime DESC", null).use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val title = c.getString(1) ?: continue
                            val date = c.getString(2) ?: ""
                            val photos = runCatching {
                                val extras = JSONObject(c.getString(3) ?: "{}")
                                val arr = JSONArray(extras.optString("selected_images", "[]"))
                                (0 until arr.length()).mapNotNull { paths[arr.getString(it)] }
                            }.getOrDefault(emptyList())
                            if (photos.isNotEmpty()) result.add(Card(id, title, date, photos))
                        }
                    }
                }
                cards = result
                Log.d("DuoCards", "cards=${result.size} first=" + result.firstOrNull()?.let { "${it.title}/${it.date}/${it.photos.size}" })
            } catch (t: Throwable) {
                Log.w("DuoCards", "card read failed: ${t.javaClass.simpleName}")
            } finally {
                fetching.set(false)
            }
        }.start()
    }

    private fun copyDb(context: Context, name: String, minBytes: Long): File? {
        val dst = File(context.filesDir, name)
        val process = ProcessBuilder("su", "-M", "-c", "cat /data/data/com.miui.gallery/databases/$name").start()
        process.inputStream.use { input -> dst.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
        process.waitFor(30, TimeUnit.SECONDS)
        return if (dst.length() > minBytes) dst else null
    }
}
