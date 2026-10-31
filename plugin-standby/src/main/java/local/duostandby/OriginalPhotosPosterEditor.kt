package local.duostandby

/** PhotosClockPoster source semantics, iOS 27.1 / 24A94425.
 * This is the paged-editor model, not a reconstruction of its native controls.
 * Sources: editableProperties 0x1e345d9f0; localizedTitle 0x1e3317a68;
 * standBy supportedLayouts 0x1e33219e0. See supplement-20261023-v42/photo-properties.
 */
object OriginalPhotosPosterEditor {
    enum class Property(val id: String, val titleZh: String, val titleEn: String) {
        CONTENT("photosContent", "内容", "Content"),
        POSITION("timeLayout", "位置", "Position"),
        COLOR("Color", "颜色", "Color"),
        NUMERALS("Font", "数字", "Numerals"),
    }

    // Select is an accessory in Content, not an extra property page.
    val properties: List<Property> = listOf(
        Property.CONTENT, Property.POSITION, Property.COLOR, Property.NUMERALS,
    )
    const val selectTitleZh = "选择"
    const val selectTitleEn = "Select"
    const val defaultTimeLayoutRaw = 1
    const val editingUnavailableMessageZh = "此样式无法自定义。"
    const val editingUnavailableMessageEn = "Customization is unavailable for this style."

    data class Position(
        val raw: Int,
        val sourceCase: String,
        val localizationKey: String,
        val labelZh: String,
        val labelEn: String,
    )

    // Original standBy provider array: 01 02 0b 00. Do not expose allCases.
    // Labels are the enum's localizedTitle; the native range renders
    // TimeLayoutSelectorView(layout, numberSystem, deviceCategory), not bare text.
    val supportedPositions: List<Position> = listOf(
        Position(1, "horizontalDynamic", "ClockPoster_Layout_HorizontalDynamic", "水平", "Horizontal"),
        Position(2, "verticalDynamic", "ClockPoster_Layout_VerticalDynamic", "叠放", "Stacked"),
        Position(11, "small", "ClockPoster_Layout_Small", "小", "Small"),
        Position(0, "none", "ClockPoster_Layout_None", "无", "None"),
    )

    // PhotosPosterTimeNumberSystem is NOT the analog/digital plugin enum:
    // devanagari has native byte 2 here, not their byte 3.
    enum class NumberSystem(val raw: Int, val persistedId: String, val labelZh: String, val labelEn: String) {
        ARABIC_WESTERN(0, "arabicWestern", "阿拉伯数字", "Arabic, Western"),
        ARABIC_EASTERN(1, "arabicEastern", "阿拉伯印度文", "Arabic, Eastern"),
        DEVANAGARI(2, "devanagari", "天城文", "Devanagari"),
    }
    const val numberSystemMenuIcon = "globe"
    const val numberSystemSectionTitleZh = "文字"
    const val numberSystemSectionTitleEn = "Script"
    val numberSystemCases: List<NumberSystem> = listOf(
        NumberSystem.ARABIC_WESTERN, NumberSystem.ARABIC_EASTERN, NumberSystem.DEVANAGARI,
    )

    // PhotosClockPosterTimeAppearance: retain all original identities, but the
    // original availableAppearances exposes ONLY glass and glassAccentColor.
    // No RGB values/material approximation or unrelated clock palette is implied.
    enum class Appearance(val raw: Int, val persistedId: String) {
        GLASS(0, "glass"),
        GLASS_ACCENT_COLOR(1, "glassAccentColor"),
        VIBRANT_MATERIAL(2, "vibrantMaterial"),
        ACCENT_COLOR(3, "accentColor"),
    }
    val availableAppearances: List<Appearance> = listOf(Appearance.GLASS, Appearance.GLASS_ACCENT_COLOR)
    val defaultAppearance: Appearance = Appearance.GLASS

    fun editingUnavailable(property: Property, timeLayoutRaw: Int): Boolean =
        timeLayoutRaw == 0 && (property == Property.COLOR || property == Property.NUMERALS)

    fun positionForRaw(raw: Int): Position? = supportedPositions.firstOrNull { it.raw == raw }
}
