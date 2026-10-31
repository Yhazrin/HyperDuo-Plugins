package local.duostandby

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Configuration selection is separate from its current asset. No image/library IO. */
class PhotoPageRepository(private val file: File, legacyAssetId: String? = null) {
    enum class SourceKind { XIAOMI_CURATED, DOCUMENTS, XIAOMI_CARD }
    data class Page(val id: String, val title: String, val sourceKind: SourceKind,
                    val assetUris: List<String> = emptyList(), val currentAssetId: String? = null,
                    val curatedCardId: String? = null, val timeLayoutRaw: Int = 1,
                    val numberSystemRaw: Int = 0)
    private data class State(val pages: List<Page>, val selectedId: String)
    private var state: State = if (file.exists()) decode(file.readText()) else {
        val page = Page(UUID.randomUUID().toString(), "小米精选", SourceKind.XIAOMI_CURATED, currentAssetId = legacyAssetId)
        State(listOf(page), page.id).also { persist(it) }
    }
    private var draft: MutableList<Page>? = null
    val pages: List<Page> get() = state.pages.toList()
    val selectedPageId: String get() = state.selectedId
    val draftPages: List<Page>? get() = draft?.toList()
    fun beginDraft(): List<Page> { if (draft == null) draft = state.pages.toMutableList(); return draft!!.toList() }
    fun addDraftChosenUris(uris: List<String>, title: String = "自选照片"): String? {
        val target = draft ?: return null
        val selected = uris.distinct()
        if (selected.isEmpty() || selected.size > 50 || target.size >= 20 || selected.any { !it.startsWith("content://") }) return null
        val page = Page(UUID.randomUUID().toString(), title.trim().take(80).ifBlank { "自选照片" }, SourceKind.DOCUMENTS, selected, selected.first())
        target.add(page); return page.id
    }
    /** 时光卡: gallery story card with local photo files (file:// paths) read via root. */
    fun addDraftCard(cardId: Long, title: String, paths: List<String>): String? {
        val target = draft ?: return null
        val uris = paths.distinct().take(50).map { if (it.startsWith("file://")) it else "file://" + it }
        if (uris.isEmpty() || target.size >= 20 || uris.any { !it.startsWith("file:///storage/") }) return null
        val page = Page(UUID.randomUUID().toString(), title.trim().take(80).ifBlank { "时光影集" }, SourceKind.XIAOMI_CARD, uris, uris.first(), curatedCardId = cardId.toString())
        target.add(page); return page.id
    }
    /** Switches the album of an existing 时光 page in place (same page identity, new card/photos). */
    fun switchCuratedCard(pageId: String, cardId: String, title: String, paths: List<String>): Boolean {
        val index = state.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return false
        val uris = paths.distinct().take(50).map { if (it.startsWith("file://")) it else "file://" + it }
        if (uris.isEmpty() || uris.any { !it.startsWith("file:///storage/") }) return false
        val page = state.pages[index]
        if (page.sourceKind != SourceKind.XIAOMI_CARD && page.sourceKind != SourceKind.XIAOMI_CURATED) return false
        val updated = page.copy(title = title.trim().take(80).ifBlank { "时光影集" },
            sourceKind = SourceKind.XIAOMI_CARD, assetUris = uris, currentAssetId = uris.first(),
            curatedCardId = cardId)
        val next = state.copy(pages = state.pages.toMutableList().also { it[index] = updated })
        state = next
        persist(next)
        return true
    }
    /** photosContent edits the selection owned by this configuration, never its identity. */
    fun replaceDraftChosenUris(pageId: String, uris: List<String>): Boolean {
        val target = draft ?: return false
        val index = target.indexOfFirst { it.id == pageId }
        val selected = uris.distinct()
        if (index < 0 || selected.isEmpty() || selected.size > 50 || selected.any { value ->
                runCatching { java.net.URI(value).let { it.scheme != "content" || it.rawAuthority.isNullOrBlank() } }.getOrDefault(true)
            }) return false
        val page = target[index]
        // Android document selection has no processed-asset record; retain the existing
        // chosen asset when still present, otherwise use the first returned selection.
        target[index] = page.copy(sourceKind = SourceKind.DOCUMENTS, assetUris = selected,
            currentAssetId = page.currentAssetId?.takeIf { it in selected } ?: selected.first(), curatedCardId = null)
        return true
    }
    fun editDraftTimeLayout(pageId: String, raw: Int): Boolean {
        if (raw !in setOf(0, 1, 2, 11)) return false
        val target = draft ?: return false
        val index = target.indexOfFirst { it.id == pageId }
        if (index < 0) return false
        target[index] = target[index].copy(timeLayoutRaw = raw)
        return true
    }
    /** Photos uses 0/1/2 (Devanagari=2), not the other posters' 0/1/3. */
    fun editDraftNumberSystem(pageId: String, raw: Int): Boolean {
        if (raw !in 0..2) return false
        val target = draft ?: return false
        val index = target.indexOfFirst { it.id == pageId }
        if (index < 0) return false
        target[index] = target[index].copy(numberSystemRaw = raw)
        return true
    }
    fun removeDraftPage(id: String): Boolean {
        val target = draft ?: return false
        if (target.size <= 1) return false
        return target.removeAll { it.id == id }
    }
    fun cancelDraft() { draft = null }
    /** Selects one committed photo page; used by the album rotation. */
    fun selectPage(pageId: String): Boolean {
        if (state.pages.none { it.id == pageId }) return false
        if (state.selectedId == pageId) return true
        val next = state.copy(selectedId = pageId)
        state = next
        persist(next)
        return true
    }
    /** Bundle state only: no persist(), no final selected-page mutation. */
    fun saveDraftState(): String? {
        val pending = draft ?: return null
        val selected = state.selectedId.takeIf { id -> pending.any { it.id == id } } ?: pending.first().id
        return JSONObject().put("schema", "duo.photo.configuration.draft").put("version", 1)
            .put("draft", encode(State(pending.toList(), selected))).toString()
    }
    fun restoreDraftState(value: String): Boolean = try {
        require(value.toByteArray(Charsets.UTF_8).size <= 262_144)
        val envelope = JSONObject(value)
        require(envelope.getString("schema") == "duo.photo.configuration.draft" && envelope.getInt("version") == 1)
        val restored = decode(envelope.getJSONObject("draft").toString())
        // Fully decode and validate before replacing an existing in-memory draft.
        draft = restored.pages.toMutableList()
        true
    } catch (_: Exception) { false }

    fun commitDraft(selectedPageId: String? = null): Boolean {
        val target = draft ?: return false
        if (target.isEmpty()) return false
        // Asset changes during editing are not rolled back by an unchanged selection.
        val merged = target.map { page -> state.pages.firstOrNull { it.id == page.id && it.sourceKind == page.sourceKind &&
                it.curatedCardId == page.curatedCardId && it.assetUris == page.assetUris }
            ?.let { page.copy(currentAssetId = it.currentAssetId) } ?: page }
        val next = State(merged, (selectedPageId ?: state.selectedId).takeIf { id -> merged.any { it.id == id } } ?: merged.first().id)
        return update(next).also { if (it) draft = null }
    }
    fun setCurrentPage(id: String): Boolean = state.pages.any { it.id == id } && update(state.copy(selectedId = id))
    fun rememberAsset(pageId: String, assetId: String): Boolean {
        if (state.pages.none { it.id == pageId }) return false
        return update(state.copy(pages = state.pages.map { if (it.id == pageId) it.copy(currentAssetId = assetId) else it }))
    }
    fun rememberCuratedSelection(pageId: String, cardId: String?, uris: List<String>, assetId: String): Boolean =
        update(state.copy(pages = state.pages.map { if (it.id == pageId && it.sourceKind == SourceKind.XIAOMI_CURATED)
            it.copy(assetUris = uris, currentAssetId = assetId, curatedCardId = cardId) else it }))
    private fun update(next: State): Boolean = try { persist(next); state = next; true } catch (_: Exception) { false }
    /** 原版照片面容 posterData 的结构（PhotosClockPoster.appex Info.plist 的
     *  PRStaticDescriptors["default-color-1"].posterData，815 字节 JSON，24A94425）。
     *  这里按同一套键写出来，便于以后直接迁移到 PRPosterConfiguration 描述符；
     *  我们自己的 assetUris/sourceKind 仍并存，读的时候以它为准。
     */
    private fun posterData(page: Page): JSONObject {
        val layoutCase = when (page.timeLayoutRaw) {
            2 -> "verticalDynamic"; 11 -> "small"; 0 -> "none"; else -> "horizontalDynamic"
        }
        val numberSystem = when (page.numberSystemRaw) {
            1 -> "arabicEastern"; 2 -> "devanagari"; else -> "arabicWestern"
        }
        val mode = when (page.sourceKind) {
            SourceKind.XIAOMI_CURATED -> 0    // 原版默认：图库自动精选
            SourceKind.XIAOMI_CARD -> 0
            SourceKind.DOCUMENTS -> 1        // 手动选照片
        }
        return JSONObject()
            .put("version", 1)
            .put("timeLayout", JSONObject().put(layoutCase, JSONObject()))
            .put("numberSystem", numberSystem)
            .put("shuffleFrequency", 1)                 // 原版默认 1（不是 0）
            .put("timeAppearance", "glass")
            .put("glassFrostLevel", 0.3)
            .put("internalSettings", JSONObject()
                .put("ignoresSegmentationScore", false)
                .put("maxDigitOverlap", "0.33")
                .put("showsDebugInfo", false)
                .put("shufflesAssets", false)
                .put("simulatesBacklightState", false)
                .put("useMaterialClock", false))
            .put("codableSelection", JSONObject()
                .put("ambientSelection", JSONObject.NULL)
                .put("assetCollectionIdentifiers", JSONArray())
                .put("assetIdentifiers", JSONArray(page.assetUris))
                .put("fallbackAssetIdentifiers", JSONArray())
                .put("isAutoCuratedDefault", page.sourceKind != SourceKind.DOCUMENTS)
                .put("mode", mode)
                .put("peopleIdentifiers", JSONArray())
                .put("sharedAlbumIdentifiers", JSONArray())
                .put("smartAlbumSubtypes", JSONArray())
                .put("socialGroupIdentifiers", JSONArray())
                .put("transientCollectionIdentifiers", JSONArray()))
    }

    private fun encode(next: State): JSONObject = JSONObject().put("version", 1).put("selectedPageId", next.selectedId)
            .put("pages", JSONArray().also { array -> next.pages.forEach { page ->
                array.put(JSONObject().put("id", page.id).put("title", page.title).put("sourceKind", page.sourceKind.name)
                    .put("curatedCardId", page.curatedCardId ?: JSONObject.NULL).put("assetUris", JSONArray(page.assetUris))
                    .put("timeLayoutRaw", page.timeLayoutRaw).put("numberSystemRaw", page.numberSystemRaw)
                    .put("currentAssetId", page.currentAssetId ?: JSONObject.NULL)
                    .put("posterData", posterData(page)))
            } })
    private fun persist(next: State) {
        val json = encode(next)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot atomically save photo configurations" }
        } finally { temporary.delete() }
    }
    private fun decode(text: String): State {
        val root = JSONObject(text); require(root.getInt("version") == 1)
        val array = root.getJSONArray("pages"); require(array.length() in 1..20)
        val pages = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index); val uris = item.getJSONArray("assetUris")
            Page(item.getString("id"), item.getString("title"), SourceKind.valueOf(item.getString("sourceKind")),
                (0 until uris.length()).map { uris.getString(it) }, if(item.isNull("currentAssetId")) null else item.getString("currentAssetId"),
                if(item.isNull("curatedCardId")) null else item.optString("curatedCardId").takeIf { it.isNotBlank() },
                item.optInt("timeLayoutRaw", 1), item.optInt("numberSystemRaw", 0))
        }
        require(pages.map { it.id }.distinct().size == pages.size)
        require(pages.all { page ->
            UUID.fromString(page.id).toString() == page.id && page.title.isNotBlank() && page.title.length <= 80 &&
                page.timeLayoutRaw in setOf(0, 1, 2, 11) && page.numberSystemRaw in 0..2 &&
                page.assetUris.distinct().size == page.assetUris.size &&
                page.assetUris.all { value -> val uri = java.net.URI(value)
                    if (page.sourceKind == SourceKind.XIAOMI_CARD) uri.scheme == "file" && value.startsWith("file:///storage/")
                    else uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() } &&
                when (page.sourceKind) {
                    SourceKind.DOCUMENTS -> page.assetUris.size in 1..50 &&
                        (page.currentAssetId == null || page.currentAssetId in page.assetUris)
                    SourceKind.XIAOMI_CURATED -> page.assetUris.size <= 8 &&
                        page.assetUris.all { value -> val uri = java.net.URI(value)
                            uri.rawAuthority == "com.miui.gallery.widget" && uri.path == "/maml/openImage" }
                    SourceKind.XIAOMI_CARD -> page.assetUris.size in 1..50 &&
                        (page.currentAssetId == null || page.currentAssetId in page.assetUris)
                }
        })
        val selected = root.getString("selectedPageId"); require(pages.any { it.id == selected })
        return State(pages, selected)
    }
}
