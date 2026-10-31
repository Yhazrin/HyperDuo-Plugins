package local.duostandby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Xiaomi's exported curated-card adapter, NOT a claim of the full Time collection API.
 * Route/key identities are recovered in xiaomi-shiguang-access-v28/EXACT-URI-ROUTES.json.
 * Never substitutes MediaStore, personal-library scanning, or made-up poster metadata.
 */
class XiaomiCuratedPhotoSource(private val context: Context, private val bridge: ShizukuPhotoBridge? = null) {
    enum class Phase { DISCONNECTED, LOADING, READY, UNAVAILABLE, CONSENT_REQUIRED, ACCESS_DENIED, EMPTY, ERROR }
    data class PhotoRef(val id: String, val uri: Uri)
    data class Snapshot(
        val phase: Phase,
        val message: String,
        val cardId: String? = null,
        val photos: List<PhotoRef> = emptyList(),
        val bitmap: Bitmap? = null,
        val contentSha256: String? = null,
        val contentBytes: Int = 0,
        val shizukuUsed: Boolean = false,
        val selectedIndex: Int = 0,
        // Only EXIF DateTimeOriginal from the returned image bytes, never retrieval time.
        // Kept as wall time because a missing EXIF offset must not be guessed.
        val captureDateTime: LocalDateTime? = null,
        val captureDateTimeSource: String? = null,
        // Original image GPS only; never device location or a guessed city name.
        val captureLatitude: Double? = null,
        val captureLongitude: Double? = null,
        val captureLocationSource: String? = null,
        // UI-owned bounded previews. Cached/source snapshots always leave these null.
        val readyPrevious: Snapshot? = null,
        val readyNext: Snapshot? = null,
        val pageId: String? = null,
        val captureLocationName: String? = null,
        val sourceKind: String = "XIAOMI_CURATED",
        val timeLayoutRaw: Int = 1,
        val numberSystemRaw: Int = 0,
    ) {
        val currentPhotoId: String? get() = photos.getOrNull(selectedIndex)?.id
        /** Stable transient asset owner; never a new persisted configuration UUID. */
        val currentAssetIdentity: String? get() = assetIdentityAt(selectedIndex)
        fun assetIdentityAt(index: Int): String? {
            val page = pageId ?: return null
            val ref = photos.getOrNull(index) ?: return null
            return listOf(page, sourceKind, cardId.orEmpty(), ref.id, ref.uri.toString())
                .joinToString("") { "${it.length}:$it" }
        }
        /** Same directional cycling as requestNextAsset/our existing stepPhoto adapter. */
        fun adjacentAssetIndex(inReverse: Boolean): Int? = if (photos.size < 2) null else
            Math.floorMod(selectedIndex + if (inReverse) -1 else 1, photos.size)
        // Retrieval time is deliberately not passed off as the photograph's date.
        fun report(): JSONObject = JSONObject().put("source", if (sourceKind == "DOCUMENTS") "persisted_document_selection" else "xiaomi_public_curated_cards")
            .put("phase", phase.name).put("message", message)
            .put("cardId", cardId ?: JSONObject.NULL)
            .put("selectedIndex", selectedIndex).put("currentPhotoId", currentPhotoId ?: JSONObject.NULL)
            .put("currentAssetIdentity", currentAssetIdentity ?: JSONObject.NULL)
            .put("timeLayoutRaw", timeLayoutRaw).put("numberSystemRaw", numberSystemRaw)
            .put("previousReadyPhotoId", readyPrevious?.currentPhotoId ?: JSONObject.NULL)
            .put("nextReadyPhotoId", readyNext?.currentPhotoId ?: JSONObject.NULL)
            .put("photos", JSONArray().also { a -> photos.forEach { a.put(JSONObject().put("id", it.id).put("uri", it.uri.toString())) } })
            .put("decodedWidth", bitmap?.width ?: 0).put("decodedHeight", bitmap?.height ?: 0)
            .put("contentSha256", contentSha256 ?: JSONObject.NULL).put("contentBytes", contentBytes)
            .put("title", JSONObject.NULL).put("captureTime", captureDateTime?.toString() ?: JSONObject.NULL)
            .put("captureTimeSource", captureDateTimeSource ?: JSONObject.NULL)
            .put("captureTimeZoneKnown", false)
            .put("captureLatitude", captureLatitude ?: JSONObject.NULL)
            .put("captureLongitude", captureLongitude ?: JSONObject.NULL)
            .put("captureLocationSource", captureLocationSource ?: JSONObject.NULL)
            .put("pageId", pageId ?: JSONObject.NULL)
            .put("capturePlaceName", captureLocationName ?: JSONObject.NULL).put("poster", JSONObject.NULL)
            .put("fullTimeCollection", false).put("rootUsed", false).put("shizukuUsed", shizukuUsed)
    }
    private val openRead = AtomicReference<InputStream?>(null)
    fun cancelRead() { runCatching { openRead.getAndSet(null)?.close() } }

    fun read(signal: CancellationSignal, preferredPhotoId: String? = null): Snapshot {
        try {
            val resolver = context.contentResolver
            signal.throwIfCanceled()
            val cta = resolver.query(uri("maml/cta"), null, null, null, null, signal)?.use { cursor ->
                val column = cursor.getColumnIndex("gallery_cta_allowed")
                if (!cursor.moveToFirst() || column < 0) null else cursor.getString(column) == "1"
            } ?: return Snapshot(Phase.UNAVAILABLE, "小米相册公开精选接口未返回状态")
            if (!cta) return Snapshot(Phase.CONSENT_REQUIRED, "请先在小米相册完成其使用授权；不会代替你修改授权")
            val request = uri("maml/suggests").buildUpon()
                .appendQueryParameter("widgetId", context.packageName)
                .appendQueryParameter("imageCount", "8").build()
            val payload = resolver.query(request, null, null, null, null, signal)?.use { cursor ->
                val column = cursor.getColumnIndex("suggestsInfo")
                if (!cursor.moveToFirst() || column < 0) null else cursor.getString(column)
            } ?: return Snapshot(Phase.EMPTY, "相册当前没有返回精选卡片；未改用普通图库")
            require(payload.length <= 131_072) { "Curated-card response exceeds adapter limit" }
            val card = JSONObject(payload)
            val cardId = card.optString("cardId").takeIf { it.isNotBlank() }
            val images = card.optJSONArray("images") ?: JSONArray()
            val photos = (0 until minOf(images.length(), 8)).mapNotNull { i ->
                val row = images.optJSONObject(i) ?: return@mapNotNull null
                val id = row.optString("id")
                val image = Uri.parse(row.optString("uri"))
                if (!id.matches(Regex("[0-9]{1,20}")) || image.scheme != "content" ||
                    image.authority != AUTHORITY || image.path != "/maml/openImage" ||
                    image.getQueryParameter("id") != id) null else PhotoRef(id, image)
            }.distinctBy { it.id }
            if (photos.isEmpty()) return Snapshot(Phase.EMPTY, "精选卡片没有可读取的照片", cardId)
            val index = photos.indexOfFirst { it.id == preferredPhotoId }.coerceAtLeast(0)
            return readPhoto(cardId, photos, index, signal)
        } catch (e: OperationCanceledException) {
            throw e
        } catch (e: SecurityException) {
            return Snapshot(Phase.ACCESS_DENIED, "相册拒绝读取；不会越过授权")
        } catch (e: Exception) {
            return Snapshot(Phase.ERROR, "精选照片读取失败：${e.javaClass.simpleName}")
        }
    }

    /** 时光 card page: local gallery photos read through the read-only root bridge. */
    fun readCard(pageId: String, paths: List<String>, currentUri: String?, title: String, date: String,
                 cardId: String?, signal: CancellationSignal): Snapshot {
        val refs = paths.map { PhotoRef(it, Uri.parse(if (it.startsWith("file://")) it else "file://" + it)) }
        if (refs.isEmpty()) return Snapshot(Phase.EMPTY, "时光卡没有可读取的照片", pageId = pageId, sourceKind = "XIAOMI_CARD")
        val index = refs.indexOfFirst { it.uri.toString() == currentUri }.coerceAtLeast(0)
        val path = refs[index].uri.path ?: return Snapshot(Phase.EMPTY, "时光卡照片路径无效", pageId = pageId, sourceKind = "XIAOMI_CARD")
        // Same fast path as the curated photos: resolve the path to a MediaStore id and read it
        // through our own grant instead of spawning su. Root stays as the fallback.
        val startedAt = System.currentTimeMillis()
        val directBitmap = readPathDirect(path)
        val rawBitmap = directBitmap
            ?: bridge?.readFile(path)?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
            ?: return Snapshot(Phase.UNAVAILABLE, "时光照片暂不可读；需要只读图片桥", photos = refs, selectedIndex = index, pageId = pageId, sourceKind = "XIAOMI_CARD")
        // 时光卡也必须按 EXIF 摆正：精品照片路径 decode() 会做，这里原来没做，
        // 结果"面上不转、编辑器里转"（或反过来），进入编辑就是用户看到的跳变。
        val bitmap = orientForExif(rawBitmap, path)
        android.util.Log.d("DuoPhotoRead", "card path=$path direct=${directBitmap != null} ms=${System.currentTimeMillis() - startedAt}")
        // Xiaomi card date is month-granular ("2023年10月"); keep it as the original string instead of
        // inventing a day in captureDateTime.
        val meta = if (date.isBlank()) title else "$title · $date"
        return Snapshot(Phase.READY, title, cardId = cardId, photos = refs, bitmap = bitmap, selectedIndex = index, pageId = pageId,
            sourceKind = "XIAOMI_CARD", captureDateTimeSource = "shiguang_card", captureLocationName = meta)
    }
    /** Same EXIF orientation handling as decode(); path may be unreadable (root case) -> keep as is. */
    private fun orientForExif(bitmap: Bitmap, path: String): Bitmap = runCatching {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return@runCatching bitmap
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)

    /** Decode one already-approved card member; never requery the library for neighbor reads. */
    fun readPhoto(cardId: String?, photos: List<PhotoRef>, index: Int, signal: CancellationSignal): Snapshot {
        try {
            require(photos.size in 1..8 && index in photos.indices)
            val ref = photos[index]
            require(ref.id.matches(Regex("[0-9]{1,20}")))
            require(ref.uri.scheme == "content" && ref.uri.authority == AUTHORITY &&
                ref.uri.path == "/maml/openImage" && ref.uri.getQueryParameter("id") == ref.id)
            val resolver = context.contentResolver
            signal.throwIfCanceled()
            // The dimensions are a bounded decoding request, not recovered iPhone geometry.
            val image = uri("maml/openImage").buildUpon().appendQueryParameter("id", ref.id).appendQueryParameter("w", "512")
                .appendQueryParameter("h", "512").appendQueryParameter("widgetId", context.packageName).build()
            // The HyperOS gallery widget provider stalls (starts the gallery process, then fails)
            // on every read. Prefer the proven root/MediaStore bridge and only fall back to the
            // provider when the bridge is unavailable.
            val startedAt = System.currentTimeMillis()
            // Fast path: read the asset through our own MediaStore grant (READ_MEDIA_IMAGES).
            // Apple's Photos face reads the asset library in-process; spawning su per photo costs
            // ~1s and cannot match that. Root stays only as the fallback when the grant is absent.
            val mediaUri = android.net.Uri.parse("content://media/external/images/media/${photos[index].id}")
            val directStream = runCatching { resolver.openInputStream(mediaUri) }.getOrNull()
            var usedBridge = false
            val bytes = if (directStream != null) {
                try { readAll(directStream, signal) } finally { runCatching { directStream.close() } }
            } else {
                var fd = bridge?.openPhoto(photos[index].id)?.also { usedBridge = true }
                if (fd == null) {
                    val direct = try { resolver.acquireUnstableContentProviderClient(image)?.use { client ->
                        client.openFile(image, "r", signal)
                    } } catch (_: Throwable) { null }
                    fd = direct
                }
                signal.throwIfCanceled()
                if (fd == null) return Snapshot(Phase.ERROR, "相册返回了照片记录，但图片暂不可读；${bridge?.status ?: "未连接图片桥"}", cardId, photos)
                val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
                openRead.set(input)
                try { readAll(input, signal) } finally { openRead.compareAndSet(input, null) }
            }
            android.util.Log.d("DuoPhotoRead", "id=${photos[index].id} direct=${directStream != null} bridge=$usedBridge bytes=${bytes.size} ms=${System.currentTimeMillis()-startedAt}")
            signal.throwIfCanceled()
            return attachStoreCapture(decode(bytes, cardId, photos, index, usedBridge, signal, "小米精选 · 真实照片（非完整时光海报）"))
        } catch (e: OperationCanceledException) {
            throw e
        } catch (e: SecurityException) {
            return Snapshot(Phase.ACCESS_DENIED, "相册拒绝读取；不会越过授权")
        } catch (e: Exception) {
            return Snapshot(Phase.ERROR, "精选照片读取失败：${e.javaClass.simpleName}")
        }
    }
    /** Only persisted document grants explicitly returned by Android's picker. */
    fun readDocuments(pageId: String, uris: List<String>, preferredId: String?, signal: CancellationSignal): Snapshot {
        try {
            require(uris.size in 1..50)
            val refs = uris.map { PhotoRef(it, Uri.parse(it)) }
            val index = refs.indexOfFirst { it.id == preferredId }.coerceAtLeast(0)
            val uri = refs[index].uri
            require(uri.scheme == "content")
            if (context.contentResolver.persistedUriPermissions.none { it.isReadPermission && it.uri == uri })
                return Snapshot(Phase.ACCESS_DENIED, "所选照片的读取授权已失效，请重新选择", pageId = pageId)
            signal.throwIfCanceled()
            val input = context.contentResolver.openInputStream(uri)
                ?: return Snapshot(Phase.UNAVAILABLE, "所选照片暂不可读", pageId = pageId)
            openRead.set(input)
            val bytes = try { input.use { stream ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(16_384)
                while (true) { signal.throwIfCanceled(); val count = stream.read(buffer); if (count < 0) break
                    require(output.size() + count <= MAX_BYTES); output.write(buffer, 0, count) }
                output.toByteArray()
            } } finally { openRead.compareAndSet(input, null) }
            return decode(bytes, null, refs, index, false, signal, "自选照片").copy(pageId = pageId, sourceKind = "DOCUMENTS")
        } catch (e: OperationCanceledException) { throw e }
        catch (_: SecurityException) { return Snapshot(Phase.ACCESS_DENIED, "所选照片授权不可用，请重新选择", pageId = pageId) }
        catch (e: Exception) { return Snapshot(Phase.ERROR, "所选照片读取失败：${e.javaClass.simpleName}", pageId = pageId) }
    }
    private fun decode(bytes: ByteArray, cardId: String?, photos: List<PhotoRef>, index: Int,
                       usedBridge: Boolean, signal: CancellationSignal, message: String): Snapshot {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000L) { "Invalid curated image dimensions" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: return Snapshot(Phase.ERROR, "已读取图片数据，但解码失败", cardId, photos)
            signal.throwIfCanceled()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val captured = exif?.let { readOriginalCaptureTime(it) }
            val coordinates = exif?.let { readOriginalCoordinates(it) }
            return Snapshot(Phase.READY, message, cardId, photos,
                bitmap, hash, bytes.size, usedBridge, index, captured,
                if (captured != null) "image_exif_DateTimeOriginal" else null,
                coordinates?.first, coordinates?.second,
                if (coordinates != null) "image_exif_GPSLatitude_GPSLongitude" else null)
    }

    /** Fills only fields still missing on this one asset. The id comes from the curated row just read. */
    /** Resolve a /storage path to a MediaStore image and decode it with the app's own grant. */
    private fun readPathDirect(path: String): Bitmap? = runCatching {
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val id = context.contentResolver.query(collection,
            arrayOf(android.provider.MediaStore.Images.Media._ID),
            android.provider.MediaStore.Images.Media.DATA + "=?", arrayOf(path), null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        } ?: return null
        context.contentResolver.openInputStream(android.content.ContentUris.withAppendedId(collection, id))
            ?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun readAll(input: java.io.InputStream, signal: CancellationSignal): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            signal.throwIfCanceled()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Curated image exceeds adapter limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun attachStoreCapture(snapshot: Snapshot): Snapshot {
        if (snapshot.captureDateTime != null && snapshot.captureLatitude != null) return snapshot
        val id = snapshot.currentPhotoId ?: return snapshot
        val row = storeCapture(id) ?: run {
            android.util.Log.i("DuoPhotoMeta", "id=$id unread")
            return snapshot
        }
        val millis = row.optLong("datetaken", 0L)
        val date = if (snapshot.captureDateTime != null || millis <= 0L) snapshot.captureDateTime
            else Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val latitude = snapshot.captureLatitude ?: row.optDouble("latitude").takeIf { row.has("latitude") && it.isFinite() }
        val longitude = snapshot.captureLongitude ?: row.optDouble("longitude").takeIf { row.has("longitude") && it.isFinite() }
        // Apple's photos poster shows localizedGeoDescription + creationDate. iOS geocodes the
        // asset's GPS; on Android the platform equivalent is Geocoder (explicit adapter, network).
        val place = if (snapshot.captureLocationName.isNullOrBlank() && latitude != null && longitude != null)
            reverseGeocode(latitude, longitude) else null
        // No EXIF GPS on many gallery photos; fall back to Xiaomi's own geocoded address path
        // (cloud.location) so the Apple-style metadata still has a place, clearly adapter-sourced.
        val galleryCity = if (snapshot.captureLocationName.isNullOrBlank() && place == null && latitude == null)
            id.toLongOrNull()?.let { GalleryAlbumsRepository.cityFor(context, it) } else null
        val located = snapshot.captureLocationName ?: place ?: galleryCity
        android.util.Log.i("DuoPhotoMeta", "id=$id date=${date != null} coordinates=${latitude != null && longitude != null} place=$located")
        return snapshot.copy(
            captureLocationName = located,
            captureDateTime = date,
            captureDateTimeSource = snapshot.captureDateTimeSource
                ?: if (date != null && millis > 0L) "mediastore_dateTaken" else null,
            captureLatitude = latitude,
            captureLongitude = longitude,
            captureLocationSource = snapshot.captureLocationSource
                ?: when {
                    latitude != null && longitude != null -> "mediastore_latitude_longitude"
                    place != null -> "android_geocoder_adapter"
                    galleryCity != null -> "xiaomi_gallery_address"
                    else -> null
                },
        )
    }
    private fun reverseGeocode(latitude: Double, longitude: Double): String? = runCatching {
        val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
        if (!android.location.Geocoder.isPresent()) return null
        val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() ?: return null
        (address.locality ?: address.subAdminArea ?: address.adminArea ?: address.featureName)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun storeCapture(id: String): JSONObject? {
        if (!id.matches(Regex("[0-9]{1,20}"))) return null
        val direct = runCatching {
            context.contentResolver.query(
                Uri.parse("content://media/external/images/media/$id"),
                arrayOf("datetaken", "latitude", "longitude"), null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                JSONObject().apply {
                    val date = cursor.getColumnIndex("datetaken")
                    if (date >= 0 && !cursor.isNull(date)) put("datetaken", cursor.getLong(date))
                    val latitude = cursor.getColumnIndex("latitude")
                    if (latitude >= 0 && !cursor.isNull(latitude)) put("latitude", cursor.getDouble(latitude))
                    val longitude = cursor.getColumnIndex("longitude")
                    if (longitude >= 0 && !cursor.isNull(longitude)) put("longitude", cursor.getDouble(longitude))
                }
            }
        }.getOrNull()
        if (direct != null && direct.length() > 0) return direct
        val raw = bridge?.readCapture(id) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.length() > 0 }
    }
    private fun readOriginalCaptureTime(exif: ExifInterface): LocalDateTime? = runCatching {
        // The provider may strip EXIF. In that case there is no photo date to display.
        val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim() ?: return@runCatching null
        LocalDateTime.parse(original, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT))
    }.getOrNull()

    private fun readOriginalCoordinates(exif: ExifInterface): Pair<Double, Double>? = runCatching {
        val coordinates = FloatArray(2)
        if (!exif.getLatLong(coordinates)) return@runCatching null
        val latitude = coordinates[0].toDouble(); val longitude = coordinates[1].toDouble()
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) null
        else latitude to longitude
    }.getOrNull()

    companion object {
        const val AUTHORITY = "com.miui.gallery.widget"
        private const val MAX_BYTES = 12 * 1024 * 1024
        private fun uri(path: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).path(path).build()
        fun disconnected() = Snapshot(Phase.DISCONNECTED, "点按连接小米精选照片源")
        fun loading() = Snapshot(Phase.LOADING, "正在读取小米相册精选卡片…")
    }
}
