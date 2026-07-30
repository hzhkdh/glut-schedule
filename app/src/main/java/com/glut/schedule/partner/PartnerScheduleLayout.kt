package com.glut.schedule.partner

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.NOON_SECTIONS
import com.glut.schedule.data.settings.PartnerScheduleViewMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class PartnerOverlapKind {
    NONE,
    SAME_OWNER_CONFLICT,
    PARTIAL,
    EXACT,
    SAME_COURSE
}

data class PartnerDisplayGroup(
    val courses: List<PartnerCourse>,
    val kind: PartnerOverlapKind,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: String,
    val endTime: String
)

fun classifyPartnerOverlap(
    first: PartnerCourse,
    second: PartnerCourse,
    firstCampus: String = GUILIN_DEFAULT_CAMPUS,
    secondCampus: String = GUILIN_DEFAULT_CAMPUS
): PartnerOverlapKind {
    if (first.ownerColor == second.ownerColor || first.dayOfWeek != second.dayOfWeek) {
        return PartnerOverlapKind.NONE
    }
    val firstRange = partnerCanonicalSectionRange(first, firstCampus)
    val secondRange = partnerCanonicalSectionRange(second, secondCampus)
    if (firstRange.last < secondRange.first || secondRange.last < firstRange.first) {
        return PartnerOverlapKind.NONE
    }

    if (firstRange != secondRange) return PartnerOverlapKind.PARTIAL

    val sameTitle = first.title.normalizedCourseText() == second.title.normalizedCourseText()
    val sameRoom = first.room.orEmpty().normalizedCourseText() == second.room.orEmpty().normalizedCourseText()
    return if (sameTitle && sameRoom) PartnerOverlapKind.SAME_COURSE else PartnerOverlapKind.EXACT
}

fun partnerDisplayGroups(
    week: Int,
    courses: List<PartnerCourse>,
    campusByOwner: Map<PartnerIdentityColor, String> = emptyMap()
): List<PartnerDisplayGroup> {
    return courses
        .filter { week in it.weeks }
        .groupBy { it.dayOfWeek }
        .toSortedMap()
        .flatMap { (day, dayCourses) ->
            val sorted = dayCourses.sortedWith(
                compareBy<PartnerCourse> { LocalTime.parse(it.startTime) }
                    .thenBy { LocalTime.parse(it.endTime) }
                    .thenBy { it.title }
            )
            val remaining = sorted.toMutableList()
            val connectedGroups = buildList {
                while (remaining.isNotEmpty()) {
                    val component = mutableListOf(remaining.removeAt(0))
                    var expanded: Boolean
                    do {
                        expanded = false
                        val iterator = remaining.iterator()
                        while (iterator.hasNext()) {
                            val candidate = iterator.next()
                            // 双方校区钟点可以不同，组合课表只按标准化后的逻辑节次判断相交。
                            if (component.any { existing ->
                                    coursesOverlapInLogicalSections(
                                        existing,
                                        campusByOwner.campusFor(existing),
                                        candidate,
                                        campusByOwner.campusFor(candidate)
                                    )
                                }
                            ) {
                                component += candidate
                                iterator.remove()
                                expanded = true
                            }
                        }
                    } while (expanded)
                    add(component)
                }
            }
            connectedGroups.map { group ->
                val groupKind = when {
                    group.size == 1 -> PartnerOverlapKind.NONE
                    group.size == 2 && group.map { it.ownerColor }.distinct().size == 1 ->
                        PartnerOverlapKind.SAME_OWNER_CONFLICT
                    group.size == 2 -> classifyPartnerOverlap(
                        group[0],
                        group[1],
                        campusByOwner.campusFor(group[0]),
                        campusByOwner.campusFor(group[1])
                    )
                    else -> multiCourseGroupKind(group, campusByOwner)
                }
                val canonicalRanges = group.map { course ->
                    partnerCanonicalSectionRange(course, campusByOwner.campusFor(course))
                }
                PartnerDisplayGroup(
                    courses = group,
                    kind = groupKind,
                    dayOfWeek = day,
                    startSection = canonicalRanges.minOf { it.first },
                    endSection = canonicalRanges.maxOf { it.last },
                    startTime = group.minOf { it.startTime },
                    endTime = group.maxOf { it.endTime }
                )
            }.sortedBy { LocalTime.parse(it.startTime) }
        }
}

fun partnerCardTimeRange(group: PartnerDisplayGroup): Pair<String, String> {
    val displayedCourse = group.courses.first()
    return if (group.kind == PartnerOverlapKind.PARTIAL) {
        displayedCourse.startTime to displayedCourse.endTime
    } else {
        group.startTime to group.endTime
    }
}

/**
 * 组合课表的网格遵循本机当前校区的上课时间设置；找不到节次时才回退到发送方原始时间。
 */
fun partnerGridCourseTimeRange(
    course: PartnerCourse,
    localPeriods: List<ClassPeriod>
): Pair<String, String> {
    val start = localPeriods.firstOrNull { it.section == course.startSection }?.startsAt
    val end = localPeriods.firstOrNull { it.section == course.endSection }?.endsAt
    return (start ?: course.startTime) to (end ?: course.endTime)
}

/** 详情始终显示邀请发送方上传的原始时间，不受接收方自定义节次时间影响。 */
fun partnerCourseDetailTimeRange(course: PartnerCourse): Pair<String, String> =
    course.startTime to course.endTime

fun partnerOverlapDetailTitle(courseCount: Int): String =
    if (courseCount > 1) "重叠课程" else "课程详情"

fun partnerInviteExpiryText(
    expiresAt: String,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val localExpiry = runCatching {
        Instant.parse(expiresAt).atZone(zoneId)
    }.getOrNull() ?: return "有效期：生成后24小时内"
    val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    return "有效期至 ${localExpiry.format(formatter)}"
}

fun canGeneratePartnerInvite(
    hasCourses: Boolean,
    isBusy: Boolean,
    hasActiveInvite: Boolean
): Boolean = hasCourses && !isBusy && !hasActiveInvite

fun isPartnerSemesterCompatible(
    localStart: LocalDate,
    localEnd: LocalDate,
    snapshot: PartnerScheduleSnapshot
): Boolean = snapshot.semesterStartMonday == localStart && snapshot.semesterEndDate == localEnd

/**
 * 学校各校区共用统一校历。导入前严格校验学期，避免旧快照覆盖当前 TA 课表。
 */
fun requirePartnerSemesterCompatible(
    localStart: LocalDate,
    localEnd: LocalDate,
    snapshot: PartnerScheduleSnapshot
) {
    require(isPartnerSemesterCompatible(localStart, localEnd, snapshot)) {
        "该邀请码不是当前学期的课表，请让TA重新生成"
    }
}

/**
 * TA 页面沿用首页的紧凑标题规则，同时保留页面身份，避免再占用一整行展示周次。
 */
fun partnerHeaderPrimaryText(
    weekNumber: Int
): String = "第${weekNumber}周"

enum class PartnerViewModeIcon {
    SINGLE_PERSON,
    GROUP
}

fun partnerViewModeIcon(mode: PartnerScheduleViewMode): PartnerViewModeIcon = when (mode) {
    PartnerScheduleViewMode.PARTNER -> PartnerViewModeIcon.SINGLE_PERSON
    PartnerScheduleViewMode.COMBINED -> PartnerViewModeIcon.GROUP
}

data class PartnerShareOptions(
    val shareRoom: Boolean,
    val shareTeacher: Boolean
)

/** 新邀请码默认共享完整课程摘要，用户仍可在生成前单独关闭任一字段。 */
fun partnerDefaultShareOptions(): PartnerShareOptions =
    PartnerShareOptions(shareRoom = true, shareTeacher = true)

/** 根据本地记忆的展示模式选择数据；切换只影响渲染，不触发网络请求。 */
fun partnerCoursesForMode(
    mode: PartnerScheduleViewMode,
    ownCourses: List<PartnerCourse>,
    partnerCourses: List<PartnerCourse>
): List<PartnerCourse> = when (mode) {
    PartnerScheduleViewMode.PARTNER -> partnerCourses
    PartnerScheduleViewMode.COMBINED -> ownCourses + partnerCourses
}

/** 两门重叠课程已经完整展示双方标题，只有三门及以上才需要数量角标。 */
fun partnerShouldShowOverlapBadge(courseCount: Int): Boolean = courseCount >= 3

/** 组合课表拥有独立开关；南宁 11 节课表没有独立午间节次，必须保持全部可见。 */
fun partnerVisibleSections(
    periods: List<ClassPeriod>,
    showNoon: Boolean,
    campus: String = GUILIN_DEFAULT_CAMPUS
): List<Int> {
    return periods
        .map { partnerCanonicalSection(campus, it.section) }
        .filter { showNoon || it !in NOON_SECTIONS }
}

fun partnerGridRowIndex(
    section: Int,
    periods: List<ClassPeriod>,
    showNoon: Boolean,
    campus: String = GUILIN_DEFAULT_CAMPUS
): Int? = partnerVisibleSections(periods, showNoon, campus).indexOf(section).takeIf { it >= 0 }

/**
 * 将不同校区的原始节次统一到桂林 14 行课表坐标：
 * 1–4 为上午，5–6 为午间，7–14 为下午和晚上。
 * 南宁没有午间行，所以其原始 5–11 节整体后移两行。
 */
fun partnerCanonicalSection(campus: String, rawSection: Int): Int =
    if (campus == NANNING_CAMPUS && rawSection >= 5) rawSection + 2 else rawSection

fun partnerRawSectionForCanonical(campus: String, canonicalSection: Int): Int? =
    if (campus == NANNING_CAMPUS) {
        when (canonicalSection) {
            in 1..4 -> canonicalSection
            in 7..13 -> canonicalSection - 2
            else -> null
        }
    } else {
        canonicalSection.takeIf { it in 1..14 }
    }

private fun partnerCanonicalSectionRange(
    course: PartnerCourse,
    campus: String
): IntRange = partnerCanonicalSection(campus, course.startSection)..
    partnerCanonicalSection(campus, course.endSection)

fun partnerCardShowsMetadata(kind: PartnerOverlapKind): Boolean =
    kind == PartnerOverlapKind.NONE

fun partnerAvailableIdentityColors(partner: PartnerIdentityColor?): List<PartnerIdentityColor> =
    PartnerIdentityColor.entries.filterNot { it == partner }

/**
 * 导入后双方颜色不能相同；冲突时按固定调色板顺序选择首个空闲颜色，
 * 让 Android 与微信端可以复现完全一致的回退结果。
 */
fun resolveDistinctPartnerColor(
    current: PartnerIdentityColor,
    partner: PartnerIdentityColor
): PartnerIdentityColor =
    current.takeIf { it != partner }
        ?: partnerAvailableIdentityColors(partner).first()

/**
 * 有效邀请码已经发布了本机身份色，此时导入同色快照不能静默改色，
 * 否则正在流通的邀请码与本机显示会产生冲突。
 */
fun partnerImportLocalColor(
    current: PartnerIdentityColor,
    partner: PartnerIdentityColor,
    hasActiveInvite: Boolean
): PartnerIdentityColor {
    require(!hasActiveInvite || current != partner) {
        "TA的身份色与你当前邀请码相同，请先撤销邀请码再导入"
    }
    return resolveDistinctPartnerColor(current, partner)
}

/** 混合卡优先保留每个身份的一门课，避免三门重叠时某一方课程完全不可见。 */
fun partnerMixedCardCourses(courses: List<PartnerCourse>): List<PartnerCourse> =
    courses.distinctBy { it.ownerColor }.take(2)

/**
 * 单色冲突卡展示前两门课；跨身份卡仍优先保证双方各出现一门，避免同色课程挤掉另一方。
 */
fun partnerCardVisibleCourses(
    courses: List<PartnerCourse>,
    campusByOwner: Map<PartnerIdentityColor, String> = emptyMap()
): List<PartnerCourse> =
    if (courses.map { it.ownerColor }.distinct().size == 1) {
        courses.take(2)
    } else {
        // 连锁重叠可能由同色课程桥接；必须选出真正跨身份相交的一对作为卡片代表。
        courses.indices.firstNotNullOfOrNull { firstIndex ->
            ((firstIndex + 1) until courses.size).firstNotNullOfOrNull { secondIndex ->
                val first = courses[firstIndex]
                val second = courses[secondIndex]
                listOf(first, second).takeIf {
                    first.ownerColor != second.ownerColor &&
                        coursesOverlapInLogicalSections(
                            first,
                            campusByOwner.campusFor(first),
                            second,
                            campusByOwner.campusFor(second)
                        )
                }
            }
        } ?: partnerMixedCardCourses(courses)
    }

private fun multiCourseGroupKind(
    group: List<PartnerCourse>,
    campusByOwner: Map<PartnerIdentityColor, String>
): PartnerOverlapKind {
    if (group.map { it.ownerColor }.distinct().size == 1) {
        return PartnerOverlapKind.SAME_OWNER_CONFLICT
    }
    val crossOwnerKinds = group.indices.flatMap { first ->
        ((first + 1) until group.size).map { second ->
            classifyPartnerOverlap(
                group[first],
                group[second],
                campusByOwner.campusFor(group[first]),
                campusByOwner.campusFor(group[second])
            )
        }
    }.filter { it != PartnerOverlapKind.NONE }
    return when {
        crossOwnerKinds.isEmpty() -> PartnerOverlapKind.PARTIAL
        crossOwnerKinds.all { it == PartnerOverlapKind.SAME_COURSE } -> PartnerOverlapKind.SAME_COURSE
        crossOwnerKinds.all { it == PartnerOverlapKind.EXACT } -> PartnerOverlapKind.EXACT
        else -> PartnerOverlapKind.PARTIAL
    }
}

private fun coursesOverlapInLogicalSections(
    first: PartnerCourse,
    firstCampus: String,
    second: PartnerCourse,
    secondCampus: String
): Boolean {
    if (first.dayOfWeek != second.dayOfWeek) return false
    val firstRange = partnerCanonicalSectionRange(first, firstCampus)
    val secondRange = partnerCanonicalSectionRange(second, secondCampus)
    return firstRange.first <= secondRange.last && secondRange.first <= firstRange.last
}

private fun String.normalizedCourseText(): String =
    trim().replace(Regex("""\s+"""), " ").lowercase()

private fun Map<PartnerIdentityColor, String>.campusFor(course: PartnerCourse): String =
    get(course.ownerColor) ?: GUILIN_DEFAULT_CAMPUS

private const val NANNING_CAMPUS = "nanning"
private const val GUILIN_DEFAULT_CAMPUS = "guilin-yanshan"
