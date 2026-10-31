package local.duostandby

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground opt-in only. No reads until the user connects and opens Photos. */
class XiaomiCuratedPhotoControls(
    private val activity: Activity,
    private val changed: (XiaomiCuratedPhotoSource.Snapshot) -> Unit,
) {
    private val prefs = activity.getSharedPreferences("xiaomi_curated_photo_source", Activity.MODE_PRIVATE)
    private val repository = PhotoPageRepository(File(activity.filesDir, "photo-configurations-v41.json"), prefs.getString("selected_photo_id", null))
    val pages get() = repository.pages
    val selectedPageId get() = repository.selectedPageId
    val draftPages get() = repository.draftPages
    fun beginDraft(): List<PhotoPageRepository.Page> {
        if (repository.draftPages == null) draftBaseSnapshot = snapshot
        setInteractionActive(true)
        return repository.beginDraft()
    }
    fun addDraftChosenUris(uris: List<Uri>, title: String = "自选照片"): String? {
        val grants = activity.contentResolver.persistedUriPermissions
        if (uris.any { uri -> uri.scheme != "content" || grants.none { it.isReadPermission && it.uri == uri } }) return null
        return repository.addDraftChosenUris(uris.map { it.toString() }, title)
    }
    fun replaceDraftChosenUris(pageId: String, uris: List<Uri>): Boolean {
        val grants = runCatching { activity.contentResolver.persistedUriPermissions }.getOrNull() ?: return false
        if (uris.any { uri -> uri.scheme != "content" || grants.none { it.isReadPermission && it.uri == uri } }) return false
        return repository.replaceDraftChosenUris(pageId, uris.map { it.toString() })
    }
    fun timeLayoutRaw(pageId: String = selectedPageId): Int =
        (draftPages ?: pages).firstOrNull { it.id == pageId }?.timeLayoutRaw ?: 1
    fun editDraftTimeLayout(raw: Int, pageId: String = selectedPageId): Boolean =
        repository.editDraftTimeLayout(pageId, raw).also { if (it) publishDraft(snapshot) }
    fun numberSystemRaw(pageId: String = selectedPageId): Int =
        (draftPages ?: pages).firstOrNull { it.id == pageId }?.numberSystemRaw ?: 0
    fun editDraftNumberSystem(raw: Int, pageId: String = selectedPageId): Boolean =
        repository.editDraftNumberSystem(pageId, raw).also { if (it) publishDraft(snapshot) }
    fun saveDraftState(): String? = repository.saveDraftState()
    fun restoreDraftState(value: String): Boolean = repository.restoreDraftState(value).also {
        if (it) { draftBaseSnapshot = snapshot; setInteractionActive(true) }
    }
    fun removeDraftPage(id: String) = repository.removeDraftPage(id)
    fun cancelDraft() {
        val restore = draftBaseSnapshot
        cancel(); repository.cancelDraft(); draftPreviewPageId = null; draftBaseSnapshot = null
        interactionActive = false
        if (restore != null) publish(restore)
        if (started && active) load()
    }
    fun commitDraft(selectedPageId: String? = null): Boolean {
        // Android grant state can change while the external picker is foreground.
        // Recheck before persistence; failure must leave the draft and selection intact.
        val pending = repository.draftPages ?: return false
        val granted = runCatching { activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }.map { it.uri.toString() }.toSet() }.getOrNull() ?: return false
        if (pending.any { page -> page.sourceKind == PhotoPageRepository.SourceKind.DOCUMENTS &&
                page.assetUris.any { it !in granted } }) return false
        val previous = repository.pages.associateBy { it.id }
        if (!repository.commitDraft(selectedPageId)) return false
        cancel(); interactionActive = false; draftPreviewPageId = null; draftBaseSnapshot = null
        // A stable ID may now own a different selection; discard its old ready bitmap.
        cache.keys.retainAll(pages.filter { page -> previous[page.id]?.let { sameReadSelection(it, page) } == true }.map { it.id }.toSet())
        // Done may reuse its already-decoded preview only AFTER the guarded commit.
        val committed = selectedPage()
        if (readyForPage(snapshot, committed)) cache[committed.id] = snapshot.copy(readyPrevious = null, readyNext = null)
        load(); return true
    }
    fun setCurrentPage(id: String): Boolean {
        if (!repository.setCurrentPage(id)) return false
        load(); return true
    }
    private fun selectedPage() = pages.first { it.id == selectedPageId }
    private fun allowed(page: PhotoPageRepository.Page) = page.sourceKind == PhotoPageRepository.SourceKind.DOCUMENTS || enabled
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "XiaomiCuratedPhotoIO").apply { isDaemon = true } }
    private var started = false
    private var active = false
    private var generation = 0
    private var signal: CancellationSignal? = null
    private var source: XiaomiCuratedPhotoSource? = null
    private var job: Future<*>? = null
    private var prefetchPool: java.util.concurrent.ExecutorService? = null
    private var dialog: AlertDialog? = null
    private var snapshot = XiaomiCuratedPhotoSource.disconnected()
    private var draftBaseSnapshot: XiaomiCuratedPhotoSource.Snapshot? = null
    private var draftPreviewPageId: String? = null
    private var pendingLoadPage: PhotoPageRepository.Page? = null
    private var pendingLoadIsDraft = false
    // Main-thread owned, bounded to the selected configuration and adjacent configurations.
    private val cache = linkedMapOf<String, XiaomiCuratedPhotoSource.Snapshot>()
    // Transient decoded assets of the current selection, bounded to current +/- 1.
    // Configuration pages remain persisted in repository; these never create pages.
    private val assetCache = linkedMapOf<String, XiaomiCuratedPhotoSource.Snapshot>()
    private var requestedIndex: Int? = null
    private var interactionActive = false
    private var deferredCommit: XiaomiCuratedPhotoSource.Snapshot? = null
    /** User-held preview owns the current image; pause Android auto-rotation. */
    fun setInteractionActive(value: Boolean) {
        interactionActive = value || repository.draftPages != null
        if (interactionActive) main.removeCallbacks(rotate) else {
            val deferred = deferredCommit; deferredCommit = null
            if (deferred != null && started && active && allowed(selectedPage()) && deferred.pageId == selectedPageId) commit(deferred, true)
            scheduleRotation()
        }
    }
    // Original Shuffle Frequency (PhotosPosterUI): Hourly / Daily / On Lock / On Tap.
    private val rotationIntervals = intArrayOf(3600, 86400, 0)
    private val shuffleFrequency get() = prefs.getInt("shuffle_frequency", 1).coerceIn(0, 3)
    private val rotationSeconds get() = when (shuffleFrequency) { 0 -> 3600; 1 -> 86400; else -> 0 }
    private val rotate = Runnable {
        if (started && active && allowed(selectedPage()) && !interactionActive && dialog?.isShowing != true &&
            snapshot.phase == XiaomiCuratedPhotoSource.Phase.READY && requestedIndex == null) stepPhoto(false)
        scheduleRotation()
    }
    private val bridge = ShizukuPhotoBridge(activity.applicationContext) { if (started && active) load(retryPending = true) }
    private val enabled get() = prefs.getBoolean("enabled", false)

    fun start(isPhotos: Boolean) { started = true; active = isPhotos; interactionActive = repository.draftPages != null; bridge.connectIfGranted(); if (active) load() else publish(snapshot) }
    fun setActive(isPhotos: Boolean) {
        if (active == isPhotos) return
        active = isPhotos
        if (started && active) load() else if (!active) cancel()
    }
    fun stop() { started = false; interactionActive = false; deferredCommit = null; cancel(); dialog?.dismiss(); dialog = null }
    fun close() { stop(); bridge.close(); worker.shutdownNow() }
    fun show() {
        if (!started || dialog?.isShowing == true) return
        val builder = AlertDialog.Builder(activity)
        if (!enabled) {
            builder.setTitle("连接小米精选照片源")
                .setMessage("只读小米相册公开精选卡片和照片，不扫描普通图库，不上传，不使用 root。完整“时光”的标题、日期和海报尚未接入；当前仅为来源预览。")
                .setPositiveButton("连接并读取") { _, _ -> prefs.edit().putBoolean("enabled", true).apply(); if (active) load() }
                .setNegativeButton("取消", null)
        } else {
            builder.setTitle("小米精选照片源")
                .setItems(arrayOf("时光影集（${GalleryCardsRepository.cards.size} 张）", "刷新精选来源", "授权 Shizuku 只读图片桥", "照片随机显示", "断开连接")) { _, which ->
                    if (which == 0) main.post { showShiguangCards() }
                    if (which == 1 && active) load(refreshCurated = true)
                    if (which == 2) bridge.request()
                    if (which == 3) onShuffle?.invoke()
                    if (which == 4) {
                        bridge.disconnect()
                        prefs.edit().putBoolean("enabled", false).apply(); cancel(); cache.clear(); assetCache.clear(); requestedIndex = null
                        if (selectedPage().sourceKind == PhotoPageRepository.SourceKind.DOCUMENTS) load()
                        else publish(XiaomiCuratedPhotoSource.disconnected().copy(pageId = selectedPageId))
                    }
                }
                .setNegativeButton("取消", null)
        }
        main.removeCallbacks(rotate)
        dialog = builder.create().also { it.setOnDismissListener { dialog = null; scheduleRotation() }; it.show() }
    }
    var onCardPicked: ((String) -> Unit)? = null
    var onShuffle: (() -> Unit)? = null
    /** Editor: add one 时光 album as a photo page in the current draft. */
    fun addAlbumToDraft(cardId: Long): Boolean {
        val card = GalleryCardsRepository.cards.firstOrNull { it.id == cardId } ?: return false
        if (repository.draftPages == null) beginDraft()
        val pageId = repository.addDraftCard(card.id, card.title, card.photos) ?: return false
        onCardPicked?.invoke(pageId)
        previewDraftPage(pageId)
        return true
    }
    /** Boundary gesture: rotate through the configured album pages, else the adjacent card. */
    fun switchCard(reverse: Boolean): Boolean {
        val page = selectedPage()
        if (page.sourceKind != PhotoPageRepository.SourceKind.XIAOMI_CARD &&
            page.sourceKind != PhotoPageRepository.SourceKind.XIAOMI_CURATED) return false
        val photoPages = pages.filter {
            it.sourceKind == PhotoPageRepository.SourceKind.XIAOMI_CARD || it.sourceKind == PhotoPageRepository.SourceKind.XIAOMI_CURATED
        }
        if (photoPages.size > 1) {
            val current = photoPages.indexOfFirst { it.id == selectedPageId }
            val target = ((if (current < 0) 0 else current) + if (reverse) -1 else 1 + photoPages.size) % photoPages.size
            if (repository.selectPage(photoPages[target].id)) {
                android.util.Log.i("DuoPhotoPaging", "switch page reverse=$reverse title=${photoPages[target].title}")
                load()
                return true
            }
            return false
        }
        val cards = GalleryCardsRepository.cards
        if (cards.size < 2) return false
        val current = cards.indexOfFirst { it.id.toString() == page.curatedCardId }
        val target = if (current < 0) (if (reverse) cards.lastIndex else 0)
            else current + if (reverse) -1 else 1
        if (target !in cards.indices) return false
        val card = cards[target]
        if (!repository.switchCuratedCard(page.id, card.id.toString(), card.title, card.photos)) return false
        android.util.Log.i("DuoPhotoPaging", "switch card reverse=$reverse title=${card.title} index=$target")
        load()
        return true
    }
    fun showAlbumPicker() { main.post { showShiguangCards() } }
    /** Commit a chosen 时光 album as the current photo page (in place, persisted). */
    fun applyCuratedCard(card: GalleryCardsRepository.Card): Boolean {
        val page = selectedPage()
        if (!repository.switchCuratedCard(page.id, card.id.toString(), card.title, card.photos)) return false
        android.util.Log.i("DuoPhotoPaging", "apply album title=${card.title}")
        load()
        return true
    }

    private fun showShiguangCards() {
        val cards = GalleryCardsRepository.cards
        if (cards.isEmpty()) {
            android.widget.Toast.makeText(activity, "时光卡尚未加载，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (repository.draftPages == null) beginDraft()
        val labels = cards.map { "${it.title} · ${it.date}" }.toTypedArray()
        dialog = AlertDialog.Builder(activity).setTitle("时光影集（${cards.size} 张）")
            .setItems(labels) { d, index ->
                d.dismiss()
                val card = cards[index]
                val pageId = repository.addDraftCard(card.id, card.title, card.photos)
                if (pageId == null) {
                    android.widget.Toast.makeText(activity, "卡片没有可读取的本地照片", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onCardPicked?.invoke(pageId)
                    previewDraftPage(pageId)
                }
            }.setNegativeButton("取消", null).create().also {
                it.setOnDismissListener { dialog = null; scheduleRotation() }; it.show()
            }
    }
    private fun showRotationOptions() {
        if (!started || !enabled || dialog?.isShowing == true) return
        main.removeCallbacks(rotate)
        val labels = arrayOf("每小时", "每天", "锁定时", "轻点时")
        dialog = AlertDialog.Builder(activity).setTitle("照片随机显示")
            .setSingleChoiceItems(labels, shuffleFrequency) { d, index ->
                prefs.edit().putInt("shuffle_frequency", index).apply()
                d.dismiss()
            }.setNegativeButton("取消", null).create().also {
                it.setOnDismissListener { dialog = null; scheduleRotation() }; it.show()
            }
    }
    private fun scheduleRotation() {
        main.removeCallbacks(rotate)
        if (started && active && allowed(selectedPage()) && !interactionActive && rotationSeconds > 0 && snapshot.bitmap != null &&
            snapshot.photos.size > 1 && dialog?.isShowing != true) {
            main.postDelayed(rotate, rotationSeconds * 1000L)
        }
    }
    private fun cancel() {
        prefetchPool?.shutdownNow(); prefetchPool = null
        main.removeCallbacks(rotate)
        deferredCommit = null
        generation++; signal?.cancel(); signal = null; source?.cancelRead(); source = null
        job?.cancel(true); job = null
        pendingLoadPage = null
    }
    private fun sameReadSelection(a: PhotoPageRepository.Page, b: PhotoPageRepository.Page): Boolean =
        a.id == b.id && a.sourceKind == b.sourceKind && a.curatedCardId == b.curatedCardId &&
            a.assetUris == b.assetUris && a.currentAssetId == b.currentAssetId
    private fun readyForPage(value: XiaomiCuratedPhotoSource.Snapshot?, page: PhotoPageRepository.Page,
                             assetId: String? = page.currentAssetId): Boolean {
        if (value == null || value.phase != XiaomiCuratedPhotoSource.Phase.READY || value.bitmap?.isRecycled != false ||
            value.pageId != page.id || value.sourceKind != page.sourceKind.name || value.cardId != page.curatedCardId ||
            value.photos.map { it.uri.toString() } != page.assetUris || value.currentPhotoId != assetId) return false
        // A warm bitmap does not confer a revoked document grant.
        return page.sourceKind != PhotoPageRepository.SourceKind.DOCUMENTS || runCatching {
            val grants = activity.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
            page.assetUris.all { it in grants }
        }.getOrDefault(false)
    }
    private fun readPage(reader: XiaomiCuratedPhotoSource, page: PhotoPageRepository.Page, cancellation: CancellationSignal, refreshCurated: Boolean = false): XiaomiCuratedPhotoSource.Snapshot {
        if (!allowed(page)) return XiaomiCuratedPhotoSource.disconnected().copy(pageId = page.id)
        return when (page.sourceKind) {
            PhotoPageRepository.SourceKind.DOCUMENTS -> reader.readDocuments(page.id, page.assetUris, page.currentAssetId, cancellation)
            PhotoPageRepository.SourceKind.XIAOMI_CURATED -> {
                val refs = page.assetUris.mapNotNull { value -> val uri = Uri.parse(value)
                    uri.getQueryParameter("id")?.let { XiaomiCuratedPhotoSource.PhotoRef(it, uri) } }
                (if (!refreshCurated && refs.isNotEmpty()) reader.readPhoto(page.curatedCardId, refs,
                    refs.indexOfFirst { it.id == page.currentAssetId }.coerceAtLeast(0), cancellation)
                else reader.read(cancellation, page.currentAssetId)).copy(pageId = page.id)
            }
            PhotoPageRepository.SourceKind.XIAOMI_CARD -> {
                val card = GalleryCardsRepository.cards.firstOrNull { it.id.toString() == page.curatedCardId }
                reader.readCard(page.id, page.assetUris, page.currentAssetId, page.title, card?.date ?: "", page.curatedCardId, cancellation)
            }
        }
    }
    private fun load(refreshCurated: Boolean = false, retryPending: Boolean = false) {
        draftPreviewPageId?.let { owner ->
            repository.draftPages?.firstOrNull { it.id == owner }?.let { loadDraftPreview(it, retryPending); return }
        }
        if (!started || !active) { cancel(); return }
        val page = selectedPage()
        if (!refreshCurated && !retryPending && !pendingLoadIsDraft && pendingLoadPage?.let { sameReadSelection(it, page) } == true && signal?.isCanceled == false) {
            android.util.Log.d("DuoPhotoLoad", "coalesced committed read")
            return
        }
        cancel(); requestedIndex = null
        if (!allowed(page)) { publish(XiaomiCuratedPhotoSource.disconnected().copy(pageId = page.id)); return }
        if (!refreshCurated && readyForPage(cache[page.id], page)) {
            android.util.Log.d("DuoPhotoLoad", "ready cache hit; no provider read or bitmap decode")
            publish(cache.getValue(page.id))
            prefetchAssets()
            return
        }
        val token = generation
        val cancellation = CancellationSignal().also { signal = it }
        pendingLoadPage = page; pendingLoadIsDraft = false
        val reader = XiaomiCuratedPhotoSource(activity.applicationContext, bridge).also { source = it }
        publish(if (snapshot.bitmap != null) snapshot.copy(phase = XiaomiCuratedPhotoSource.Phase.LOADING,
            message = "正在准备照片页，保留当前照片…") else XiaomiCuratedPhotoSource.loading().copy(pageId = page.id))
        job = worker.submit {
            try {
                val result = readPage(reader, page, cancellation, refreshCurated)
                main.post { if (valid(token, cancellation)) {
                    pendingLoadPage = null
                    if (result.phase == XiaomiCuratedPhotoSource.Phase.READY) commit(result)
                    else keepVisibleOnFailure(result)
                } }
            } catch (_: OperationCanceledException) { }
        }
    }
    /** Read-only draft preview: never call commit()/rememberAsset() or fill committed caches. */
    fun previewDraftPage(pageId: String): Boolean {
        val page = repository.draftPages?.firstOrNull { it.id == pageId } ?: return false
        draftPreviewPageId = pageId
        loadDraftPreview(page)
        return true
    }
    private fun publishDraft(value: XiaomiCuratedPhotoSource.Snapshot) {
        snapshot = withPageSettings(value.copy(readyPrevious = null, readyNext = null),
            repository.draftPages?.firstOrNull { it.id == value.pageId })
        changed(snapshot)
    }
    private fun loadDraftPreview(page: PhotoPageRepository.Page, retryPending: Boolean = false) {
        if (!retryPending && started && active && pendingLoadIsDraft && pendingLoadPage?.let { sameReadSelection(it, page) } == true && signal?.isCanceled == false) {
            android.util.Log.d("DuoPhotoLoad", "coalesced draft read")
            return
        }
        cancel()
        if (!started || !active) return
        if (!allowed(page)) { publishDraft(XiaomiCuratedPhotoSource.disconnected().copy(pageId = page.id)); return }
        val ready = snapshot.takeIf { readyForPage(it, page) } ?: cache[page.id]?.takeIf { readyForPage(it, page) }
        if (ready != null) {
            android.util.Log.d("DuoPhotoLoad", "ready draft preview hit; no provider read or bitmap decode")
            publishDraft(ready)
            return
        }
        val token = generation
        val cancellation = CancellationSignal().also { signal = it }
        pendingLoadPage = page; pendingLoadIsDraft = true
        val reader = XiaomiCuratedPhotoSource(activity.applicationContext, bridge).also { source = it }
        publishDraft(if (snapshot.bitmap != null) snapshot.copy(phase = XiaomiCuratedPhotoSource.Phase.LOADING,
            pageId = page.id, message = "正在准备所选照片预览…") else XiaomiCuratedPhotoSource.loading().copy(pageId = page.id))
        job = worker.submit {
            try {
                val result = readPage(reader, page, cancellation)
                main.post {
                    if (valid(token, cancellation)) pendingLoadPage = null
                    if (valid(token, cancellation) && draftPreviewPageId == page.id &&
                            repository.draftPages?.firstOrNull { it.id == page.id }?.let {
                                it.sourceKind == page.sourceKind && it.assetUris == page.assetUris && it.curatedCardId == page.curatedCardId
                            } == true) {
                        publishDraft(if (result.phase == XiaomiCuratedPhotoSource.Phase.READY || snapshot.bitmap == null) result
                            else snapshot.copy(phase = result.phase, message = result.message))
                    }
                }
            } catch (_: OperationCanceledException) { }
        }
    }
    /** Edge taps and automatic rotation change the asset INSIDE the selected configuration. */
    /** Shuffle Frequency "On Lock": advance the photo when the device locks/screen turns off. */
    fun shuffleOnLock() {
        if (shuffleFrequency != 2) return
        if (!started || !active) return
        if (dialog?.isShowing == true) return
        if (repository.draftPages != null) return
        if (snapshot.phase != XiaomiCuratedPhotoSource.Phase.READY) return
        if (snapshot.photos.size < 2) return
        stepPhoto(false)
    }
    fun stepPhoto(inReverse: Boolean) {
        if (repository.draftPages != null || !started || !active || !allowed(selectedPage()) || snapshot.pageId != selectedPageId || snapshot.bitmap == null || snapshot.photos.size < 2) return
        val index = Math.floorMod((requestedIndex ?: snapshot.selectedIndex) + if (inReverse) -1 else 1, snapshot.photos.size)
        val cached = snapshot.assetIdentityAt(index)?.let(assetCache::get)
        if (readyForPage(cached, selectedPage(), snapshot.photos[index].id)) {
            cancel(); commit(cached!!); return
        }
        cancel(); requestedIndex = index
        val page = selectedPage(); val refs = snapshot.photos; val card = snapshot.cardId
        val token = generation; val cancellation = CancellationSignal().also { signal = it }
        val reader = XiaomiCuratedPhotoSource(activity.applicationContext, bridge).also { source = it }
        publish(snapshot.copy(phase = XiaomiCuratedPhotoSource.Phase.LOADING, message = "正在准备当前照片页中的下一张…"))
        job = worker.submit {
            try {
                val result = if (page.sourceKind == PhotoPageRepository.SourceKind.DOCUMENTS)
                    reader.readDocuments(page.id, page.assetUris, refs[index].id, cancellation)
                else reader.readPhoto(card, refs, index, cancellation).copy(pageId = page.id)
                main.post { if (valid(token, cancellation)) {
                    if (result.phase == XiaomiCuratedPhotoSource.Phase.READY) commit(result) else keepVisibleOnFailure(result)
                } }
            } catch (_: OperationCanceledException) { }
        }
    }
    /** Existing configuration navigation is separate from current-selection asset paging. */
    fun commitReadyPageStep(inReverse: Boolean, expectedPageId: String): Boolean {
        if (repository.draftPages != null || !started || !active || snapshot.bitmap == null || snapshot.pageId != selectedPageId) return false
        val index = pages.indexOfFirst { it.id == selectedPageId } + if (inReverse) -1 else 1
        val page = pages.getOrNull(index) ?: return false
        if (page.id != expectedPageId || !allowed(page)) return false
        val ready = cache[page.id]?.takeIf { it.phase == XiaomiCuratedPhotoSource.Phase.READY && it.bitmap?.isRecycled == false } ?: return false
        if (!repository.setCurrentPage(page.id)) return false
        cancel(); requestedIndex = null; return commit(ready, true)
    }
    /** Atomic commit of a decoded neighbor of the still-current selection. No image reads or decoding in a gesture. */
    fun commitReadyAssetStep(inReverse: Boolean, expectedAssetIdentity: String): Boolean {
        if (repository.draftPages != null || !started || !active || !allowed(selectedPage()) ||
            snapshot.phase != XiaomiCuratedPhotoSource.Phase.READY || snapshot.pageId != selectedPageId) return false
        val index = snapshot.adjacentAssetIndex(inReverse) ?: return false
        if (snapshot.assetIdentityAt(index) != expectedAssetIdentity) return false
        var ready = assetCache[expectedAssetIdentity]
        if (!readyForPage(ready, selectedPage(), snapshot.photos[index].id)) {
            // Swipe can land before the background prefetch; read this one target synchronously
            // instead of refusing the step (which snapped the pager back = "same photo").
            val reader = XiaomiCuratedPhotoSource(activity.applicationContext, bridge).also { source = it }
            ready = readPage(reader, selectedPage().copy(currentAssetId = snapshot.photos[index].id), CancellationSignal())
            if (ready.phase != XiaomiCuratedPhotoSource.Phase.READY) return false
        }
        cancel(); requestedIndex = null
        return commit(ready ?: return false, true)
    }
    @Deprecated("Use commitReadyPageStep with configuration UUID, not asset ID")
    fun commitReadyStep(inReverse: Boolean, expectedId: String): Boolean = commitReadyPageStep(inReverse, expectedId)
    private fun valid(token: Int, cancellation: CancellationSignal) = started && active && token == generation && !cancellation.isCanceled
    private fun keepVisibleOnFailure(result: XiaomiCuratedPhotoSource.Snapshot) {
        requestedIndex = null
        publish(if (snapshot.bitmap != null) snapshot.copy(phase = result.phase, message = result.message) else result)
    }
    private fun commit(result: XiaomiCuratedPhotoSource.Snapshot, allowDuringInteraction: Boolean = false): Boolean {
        if (result.pageId != selectedPageId) return false
        if (interactionActive && !allowDuringInteraction) { deferredCommit = result.copy(readyPrevious = null, readyNext = null); return false }
        check(result.phase == XiaomiCuratedPhotoSource.Phase.READY && result.bitmap != null)
        requestedIndex = null
        val pageId = result.pageId ?: return false
        val saved = if (selectedPage().sourceKind == PhotoPageRepository.SourceKind.XIAOMI_CURATED)
            repository.rememberCuratedSelection(pageId, result.cardId, result.photos.map { it.uri.toString() }, result.currentPhotoId!!)
        else repository.rememberAsset(pageId, result.currentPhotoId!!)
        if (!saved) return false
        cache[pageId] = result.copy(readyPrevious = null, readyNext = null)
        result.currentAssetIdentity?.let { assetCache[it] = result.copy(readyPrevious = null, readyNext = null) }
        val index = pages.indexOfFirst { it.id == pageId }
        val neighbors = listOfNotNull(pages.getOrNull(index - 1), pages.getOrNull(index + 1))
        cache.keys.retainAll((neighbors.map { it.id } + pageId).toSet())
        if (selectedPage().sourceKind == PhotoPageRepository.SourceKind.XIAOMI_CURATED)
            prefs.edit().putString("selected_photo_id", result.currentPhotoId).apply()
        publish(result)
        prefetchAssets()
        return true
    }
    /** Predecode only current-selection neighbors. Completion never changes currentAsset. */
    private fun prefetchAssets() {
        if (!started || !active || repository.draftPages != null ||
            snapshot.phase != XiaomiCuratedPhotoSource.Phase.READY || snapshot.pageId != selectedPageId) return
        val page = selectedPage(); val origin = snapshot
        if (!allowed(page)) return
        val indices = if (page.sourceKind == PhotoPageRepository.SourceKind.XIAOMI_CURATED)
            origin.photos.indices.filter { it != origin.selectedIndex }
        else listOfNotNull(origin.adjacentAssetIndex(true), origin.adjacentAssetIndex(false)).distinct()
        val retained = (indices + origin.selectedIndex).mapNotNull(origin::assetIdentityAt).toSet()
        assetCache.keys.retainAll(retained)
        origin.currentAssetIdentity?.let { assetCache[it] = origin.copy(readyPrevious = null, readyNext = null) }
        val missing = indices.filter { index ->
            !readyForPage(origin.assetIdentityAt(index)?.let(assetCache::get), page, origin.photos[index].id)
        }
        if (missing.isEmpty()) return
        val token = generation; val owner = origin.currentAssetIdentity
        val cancellation = CancellationSignal().also { signal = it }
        val reader = XiaomiCuratedPhotoSource(activity.applicationContext, bridge).also { source = it }
        // Root reads cost ~1s each (su spawn + content provider); run them in parallel so the
        // first swipe is not blocked behind a serial 8-photo prefetch.
        val pool = prefetchPool ?: java.util.concurrent.Executors.newFixedThreadPool(4).also { prefetchPool = it }
        job = worker.submit {
            val prefetchStart = System.currentTimeMillis()
            val tasks = missing.map { index ->
                pool.submit {
                try {
                    cancellation.throwIfCanceled()
                    val taskReader = if (missing.size > 1) XiaomiCuratedPhotoSource(activity.applicationContext, bridge) else reader
                    val result = if (page.sourceKind == PhotoPageRepository.SourceKind.DOCUMENTS)
                        taskReader.readDocuments(page.id, page.assetUris, origin.photos[index].id, cancellation)
                    else taskReader.readPhoto(origin.cardId, origin.photos, index, cancellation).copy(pageId = page.id)
                    main.post {
                        if (valid(token, cancellation) && repository.draftPages == null &&
                            selectedPageId == page.id && snapshot.currentAssetIdentity == owner &&
                            sameReadSelection(page, selectedPage()) &&
                            readyForPage(result, page, origin.photos[index].id)) {
                            result.currentAssetIdentity?.let {
                                assetCache[it] = result.copy(readyPrevious = null, readyNext = null)
                            }
                            publish(snapshot)
                        }
                    }
                } catch (_: OperationCanceledException) { }
                }
            }
            tasks.forEach { runCatching { it.get() } }
            android.util.Log.d("DuoPhotoPrefetch", "count=${missing.size} ms=${System.currentTimeMillis()-prefetchStart}")
        }
    }
    private fun withPageSettings(value: XiaomiCuratedPhotoSource.Snapshot, page: PhotoPageRepository.Page?) =
        if (page == null) value else value.copy(timeLayoutRaw = page.timeLayoutRaw, numberSystemRaw = page.numberSystemRaw)
    private fun publish(value: XiaomiCuratedPhotoSource.Snapshot) {
        val plain = withPageSettings(value.copy(readyPrevious = null, readyNext = null), pages.firstOrNull { it.id == value.pageId })
        fun readyNeighbor(step: Int): XiaomiCuratedPhotoSource.Snapshot? {
            if (repository.draftPages != null || plain.phase != XiaomiCuratedPhotoSource.Phase.READY ||
                plain.bitmap == null || plain.pageId != selectedPageId) return null
            val page = selectedPage()
            if (!allowed(page)) return null
            val index = plain.adjacentAssetIndex(step < 0) ?: return null
            val identity = plain.assetIdentityAt(index) ?: return null
            return assetCache[identity]?.takeIf { readyForPage(it, page, plain.photos[index].id) }
                ?.let { withPageSettings(it.copy(readyPrevious = null, readyNext = null), page) }
        }
        snapshot = plain.copy(readyPrevious = readyNeighbor(-1), readyNext = readyNeighbor(1))
        // Private local source diagnostics; no tokens or image bytes, no public export.
        runCatching {
            val tmp = File(activity.filesDir, "xiaomi-curated-photo-status.json.tmp")
            tmp.writeText(snapshot.report().put("rotationSeconds", rotationSeconds)
                .put("rotationPolicy", "android_foreground_adapter").toString())
            check(tmp.renameTo(File(activity.filesDir, "xiaomi-curated-photo-status.json")))
        }
        changed(snapshot)
        scheduleRotation()
    }
}
