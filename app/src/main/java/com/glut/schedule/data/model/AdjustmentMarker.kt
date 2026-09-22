package com.glut.schedule.data.model

import kotlin.math.pow

/**
 * 卡片左下角的「调」/「补」角标。
 *
 * 为什么只能靠反查：解析期确实会生成 `-makeup-<周>` / `-adjusted-<i>` 这类课次 id 后缀，
 * 但落库前会被 `mergeCompatibleCourses` 统一重编号成 `-occurrence-N`，后缀全被抹掉。
 * 卡片上没有任何字段能说明它来自哪种调整，所以只能拿持久化的调课记录按目标时段反查。
 */

private const val TYPE_ADJUSTED = "调课"
private const val TYPE_MAKEUP = "补课"

/**
 * 这张卡片是不是「调课」或「补课」产生的，是则返回要标的单字。
 *
 * 两处容易写错的地方：
 * - **停课、代课一律不标**。停课的 `makeupWeek = 0` 天然不命中；但代课在 Android 侧
 *   **也会生成一张补课卡**（解析器把停/代课侧数据搬到了 makeup 侧），不显式滤掉就会被错标。
 * - **必须带周次判定**。若某条调整的目标时段恰好与该课程的常规时段重合，合并逻辑会把
 *   两者并成同一门课的两个课次；此时只按 (课程名, 星期, 节次) 匹配，会让**常规周的那张卡
 *   也被标上**。周次这一条不是保险，是必需的。
 */
fun adjustmentMarkerFor(
    occurrence: CourseOccurrence,
    courseTitle: String,
    weekNumber: Int,
    adjustments: List<SemesterAdjustment>
): String? {
    if (weekNumber <= 0 || occurrence.dayOfWeek <= 0) return null
    val titleKey = normalizeTitleKey(courseTitle)
    if (titleKey.isBlank()) return null

    val matched = adjustments.firstOrNull { adjustment ->
        markerTextFor(adjustment.type) != null &&
            adjustment.makeupWeek == weekNumber &&
            adjustment.makeupDay == occurrence.dayOfWeek &&
            adjustment.makeupStartSection == occurrence.startSection &&
            adjustment.makeupEndSection == occurrence.endSection &&
            normalizeTitleKey(adjustment.title) == titleKey
    } ?: return null
    return markerTextFor(matched.type)
}

private fun markerTextFor(type: String): String? = when (type) {
    TYPE_ADJUSTED -> "调"
    TYPE_MAKEUP -> "补"
    else -> null
}

/** 角标在深色卡片上用的字色。 */
private const val MARKER_COLOR_ON_DARK = "#FFFFFF"

/** 角标在浅色卡片上用的字色，与设置页正文同色，保证在任何浅底上都够黑。 */
private const val MARKER_COLOR_ON_LIGHT = "#141821"

/**
 * 按卡片底色挑一个读得清的字色。
 *
 * 卡片底色是任意的：20 个预设色里有 5 个白字对比度低于 3.2:1，自动配色的 12 色里 7 个
 * 不到 4.5:1，用户还能在高级调色里选到纯白。固定颜色必然会在某些卡上糊掉，
 * 所以取白字与深字里 WCAG 对比度更高的那个。
 *
 * 底色解析不出来时退回白字——与卡片正文的既有行为一致。
 *
 * 注意这只能选到「该底色下最好的那个」，不能保证一定达标：像 `#D95412` 这种中间亮度，
 * 配白字约 4.02、配深字约 4.42，两个候选都到不了 4.5，那是底色本身的限制。
 */
fun readableMarkerColor(backgroundHex: String): String {
    val luminance = relativeLuminance(backgroundHex) ?: return MARKER_COLOR_ON_DARK
    val contrastWithWhite = 1.05 / (luminance + 0.05)
    val darkLuminance = relativeLuminance(MARKER_COLOR_ON_LIGHT) ?: 0.0
    val contrastWithDark = (luminance + 0.05) / (darkLuminance + 0.05)
    // 交点大约在相对亮度 0.199：比它暗的卡片用白字，比它亮的用深字。
    return if (contrastWithDark > contrastWithWhite) MARKER_COLOR_ON_LIGHT else MARKER_COLOR_ON_DARK
}

/** WCAG 相对亮度；无法解析时返回 null。 */
private fun relativeLuminance(hex: String): Double? {
    val clean = CourseColorMapper.normalizeHexColor(hex)?.removePrefix("#") ?: return null
    if (clean.length != 6) return null
    val channels = listOf(0, 2, 4).map { index ->
        val value = clean.substring(index, index + 2).toIntOrNull(16) ?: return null
        val ratio = value / 255.0
        if (ratio <= 0.03928) ratio / 12.92 else ((ratio + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
}
