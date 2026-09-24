package com.glut.schedule.data.model

import kotlin.math.pow

/**
 * 卡片左下角的「调」/「补」/「停」/「代」角标。
 *
 * 四类记录分别落在哪张卡上：调课与补课落在**补课时段新生成的那张卡**；
 * 停课与代课没有补课侧，落回**教务网格里原本就有的那张卡**——停课因此不再删卡，
 * 卡片保留、只加角标。
 *
 * 为什么只能靠反查：解析期确实会生成 `-makeup-<周>` / `-adjusted-<i>` 这类课次 id 后缀，
 * 但落库前会被 `mergeCompatibleCourses` 统一重编号成 `-occurrence-N`，后缀全被抹掉。
 * 卡片上没有任何字段能说明它来自哪种调整，所以只能拿持久化的调课记录按目标时段反查。
 */

private const val TYPE_ADJUSTED = "调课"
private const val TYPE_MAKEUP = "补课"
private const val TYPE_SUSPENDED = "停课"
private const val TYPE_SUBSTITUTE = "代课"

/**
 * 这张卡片是不是某条调课记录产生的，是则返回要标的单字。
 *
 * 三处容易写错的地方：
 * - **按类型换锚**。调课/补课比对 `makeup*`，停课/代课比对 `original*`（见 [anchorSideFor]）。
 *   把调课也按原时段匹配，会让残留的常规卡被错标「调」。
 * - **必须带周次判定**。若某条调整的目标时段恰好与该课程的常规时段重合，合并逻辑会把
 *   两者并成同一门课的两个课次；此时只按 (课程名, 星期, 节次) 匹配，会让**常规周的那张卡
 *   也被标上**。周次这一条不是保险，是必需的。
 * - **教师不参与匹配**。代课行的「教师姓名」写的是原教师，而网格里有时写的是代课人，
 *   纳入教师会直接漏标。
 */
fun adjustmentMarkerFor(
    occurrence: CourseOccurrence,
    courseTitle: String,
    weekNumber: Int,
    adjustments: List<SemesterAdjustment>
): String? {
    if (weekNumber <= 0 || occurrence.dayOfWeek <= 0) return null
    return findAdjustment(occurrence, courseTitle, weekNumber, adjustments)
        ?.let { markerTextFor(it.type) }
}

/**
 * 这个课次在第 [weekNumber] 周是不是被停课了。
 *
 * 课时统计要按周扣掉停课的周次（停课 = 那一周没上课），而卡片此时**仍然显示**并带「停」角标。
 * 两处必须共用同一份匹配口径，否则会出现「卡片标着停、统计里照样算课时」的自相矛盾。
 */
fun isStoppedWeek(
    occurrence: CourseOccurrence,
    courseTitle: String,
    weekNumber: Int,
    adjustments: List<SemesterAdjustment>
): Boolean {
    if (weekNumber <= 0 || occurrence.dayOfWeek <= 0) return false
    return findAdjustment(occurrence, courseTitle, weekNumber, adjustments, setOf(TYPE_SUSPENDED)) != null
}

/**
 * 找出与这个课次吻合的调整记录。
 *
 * [types] 把候选限定到某几类：同一天同一时段理论上可能既有调课记录又有停课记录，
 * 此时「取列表里第一条」会让判定结果取决于数据顺序，所以需要区分类型时必须显式限定。
 */
private fun findAdjustment(
    occurrence: CourseOccurrence,
    courseTitle: String,
    week: Int,
    adjustments: List<SemesterAdjustment>,
    types: Set<String>? = null
): SemesterAdjustment? {
    val titleKey = normalizeTitleKey(courseTitle)
    if (titleKey.isBlank()) return null
    return adjustments.firstOrNull { adjustment ->
        markerTextFor(adjustment.type) != null &&
            (types == null || adjustment.type in types) &&
            normalizeTitleKey(adjustment.title) == titleKey &&
            matchesSide(occurrence, week, adjustment, useOriginalSide = anchorSideFor(adjustment.type))
    }
}

/** 停课/代课锚原时段（没有补课侧），调课/补课锚补课时段。 */
private fun anchorSideFor(type: String): Boolean = type == TYPE_SUSPENDED || type == TYPE_SUBSTITUTE

/**
 * 原时段的匹配比补课时段多一条**教室**判定：同标题、同周、同节次的课可能在不同教室
 * （分教室的大学英语/体育就是这样），不比较会把隔壁那间教室的卡也标上。
 * 原教室为空时通配——与移除路径既有的宽松口径一致；角标只是显示，误标代价可接受。
 */
private fun matchesSide(
    occurrence: CourseOccurrence,
    week: Int,
    adjustment: SemesterAdjustment,
    useOriginalSide: Boolean
): Boolean {
    val adjustmentWeek = if (useOriginalSide) adjustment.originalWeek else adjustment.makeupWeek
    val adjustmentDay = if (useOriginalSide) adjustment.originalDay else adjustment.makeupDay
    val adjustmentStart = if (useOriginalSide) adjustment.originalStartSection else adjustment.makeupStartSection
    val adjustmentEnd = if (useOriginalSide) adjustment.originalEndSection else adjustment.makeupEndSection
    if (adjustmentWeek != week) return false
    if (adjustmentDay != occurrence.dayOfWeek) return false
    if (adjustmentStart != occurrence.startSection) return false
    if (adjustmentEnd != occurrence.endSection) return false
    if (useOriginalSide) {
        val roomKey = normalizeRoomKey(adjustment.originalRoom)
        if (roomKey.isNotEmpty() && roomKey != normalizeRoomKey(occurrence.note)) return false
    }
    return true
}

private fun markerTextFor(type: String): String? = when (type) {
    TYPE_ADJUSTED -> "调"
    TYPE_MAKEUP -> "补"
    TYPE_SUSPENDED -> "停"
    TYPE_SUBSTITUTE -> "代"
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
