package local.duostandby

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xiaomi 时光 album pool: the gallery keeps one AI "great content" set per album
 * (gallery_info.db album_great_content, 252 albums on this device) plus the album
 * names/covers (gallery.db album). The Photos face source should be one of these
 * albums, not the single journey collection. Read-only copies through `su -M`
 * (the app mount namespace hides /data/data/com.miui.gallery without it).
 */
object GalleryAlbumsRepository {
    data class Album(val id: Long, val name: String?, val coverId: Long?, val photos: List<Long>)
    @Volatile var albums: List<Album> = emptyList()
        private set
    private val fetching = AtomicBoolean(false)
    @Volatile private var lastAttempt = 0L

    fun refresh(context: Context) {
        // Integration boundary: opening a clock must not request unrelated private-data Root access.
        if (!context.getSharedPreferences("standby_integration", Context.MODE_PRIVATE).getBoolean("root_gallery_opt_in", false)) { return }
        if (System.currentTimeMillis() - lastAttempt < 30 * 60_000L) return
        if (!fetching.compareAndSet(false, true)) return
        lastAttempt = System.currentTimeMillis()
        Thread {
            try {
                val info = copyDb(context, "gallery_info.db", 5_000_000)
                val gallery = copyDb(context, "gallery.db", 40_000_000)
                if (info == null) return@Thread
                val names = mutableMapOf<Long, Pair<String?, Long?>>()
                if (gallery != null) {
                    SQLiteDatabase.openDatabase(gallery.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT _id, name, coverId FROM album", null).use { c ->
                            while (c.moveToNext()) {
                                names[c.getLong(0)] = (if (c.isNull(1)) null else c.getString(1)) to
                                    (if (c.isNull(2)) null else c.getLong(2))
                            }
                        }
                    }
                }
                val result = mutableListOf<Album>()
                SQLiteDatabase.openDatabase(info.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT album_id, media_ids FROM album_great_content WHERE album_type = 14", null).use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val photos = runCatching {
                                val arr = JSONArray(c.getString(1) ?: "[]")
                                (0 until arr.length()).map { arr.getLong(it) }
                            }.getOrDefault(emptyList())
                            if (photos.isEmpty()) continue
                            val meta = names[id]
                            result.add(Album(id, meta?.first, meta?.second, photos))
                        }
                    }
                }
                albums = result
                Log.d("DuoAlbums", "albums=${result.size} named=${result.count { it.name != null }} first=" +
                    result.firstOrNull()?.let { "${it.id}/${it.name}/${it.photos.size}" })
            } catch (t: Throwable) {
                Log.w("DuoAlbums", "album read failed: ${t.javaClass.simpleName}")
            } finally {
                fetching.set(false)
            }
        }.start()
    }

    private val cityCache = mutableMapOf<Long, String?>()
    /** Xiaomi gallery's own geocoded address (cloud.location) for a photo id, e.g.
     * "中国/北京市/北京市/朝阳区/..." -> 北京市. This is Xiaomi's data, not Apple's geocoder;
     * used only when the photo has no EXIF GPS. */
    fun cityFor(context: Context, photoId: Long): String? {
        synchronized(cityCache) { cityCache[photoId]?.let { return it } }
        val db = File(context.filesDir, "gallery.db")
        if (!db.exists() || db.length() < 1_000_000L) return null
        val city = runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("SELECT location FROM cloud WHERE _id = ?", arrayOf(photoId.toString())).use { c ->
                    if (!c.moveToFirst()) null else c.getString(0)
                }
            }
        }.getOrNull()?.let { loc ->
            loc.split("/").drop(1).take(2).map { it.trim() }.lastOrNull { it.isNotEmpty() }
        }
        synchronized(cityCache) { cityCache[photoId] = city }
        return city
    }

    private fun copyDb(context: Context, name: String, minBytes: Long): File? {
        val dst = File(context.filesDir, name)
        val process = ProcessBuilder("su", "-M", "-c", "cat /data/data/com.miui.gallery/databases/$name").start()
        process.inputStream.use { input -> dst.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
        process.waitFor(20, TimeUnit.SECONDS)
        return if (dst.length() > minBytes) dst else null
    }
}
