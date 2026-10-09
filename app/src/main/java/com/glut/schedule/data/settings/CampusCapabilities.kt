package com.glut.schedule.data.settings

/**
 * 导入来源。
 *
 * 与 [CampusType] 分开是刻意的：`CampusType` 描述「当前数据属于哪个来源」，
 * 只应在**导入成功之后**被切换；`ImportSource` 描述「用户在导入页选了哪一个」，
 * 可以在失败后随意改动。把两者混成一个字段，就会出现「点开下拉框看了一眼研究生，
 * 首页的作息/菜单就跟着变了」这种半切换状态。
 *
 * 顺序即下拉框顺序：南宁分校、桂林本部、研究生。
 */
enum class ImportSource(val storageValue: String, val label: String, val campus: CampusType) {
    NANNING("nanning", "南宁分校", CampusType.NANNING),
    GUILIN("guilin", "桂林本部", CampusType.GUILIN),
    GRADUATE("graduate", "研究生", CampusType.GRADUATE);

    companion object {
        /** 未知/缺失一律回退桂林本部：这是历史上唯一的默认来源。 */
        fun fromStorageValue(value: String?): ImportSource =
            entries.firstOrNull { it.storageValue == value } ?: GUILIN

        fun of(campus: CampusType): ImportSource =
            entries.firstOrNull { it.campus == campus } ?: GUILIN
    }
}

/**
 * 单个来源支持的能力集合。
 *
 * 集中定义的目的：过去「是否支持某菜单」散落在各页的 `campus != NANNING` 判断里，
 * 加入第三个来源后每处都要再改一遍，且很容易漏。这里是一次性的事实声明，
 * 新增来源时只需补一个 `when` 分支，编译器会强制补全。
 */
data class CampusCapabilities(
    /** 登录需要图形验证码（南宁、研究生）。 */
    val requiresCaptcha: Boolean,
    /** 支持多学期目录与历史学期离线缓存。 */
    val supportsSemesterHistory: Boolean,
    /** 一天是否含「中午1/中午2」节次（仅桂林）。 */
    val hasNoonSections: Boolean,
    /**
     * 各时段的**最后一个内部节次**（上午/中午/下午/晚上依次排列）。
     *
     * 课表网格用它判断「相邻两行之间是不是时段分界」，从而把分界缝画得更重。
     * 这里存内部节次号而不是显示号：显示号会随来源与「显示中午」开关变化，内部号才稳定。
     *
     * 判断分界时必须比较**相邻两行各自的时段**，不能拿节次号直接比对这张表里的数字——
     * 桂林隐藏中午后内部 4 与 7 之间会并成同一条缝，只有按相邻行时段比较才能自动并成一条线。
     */
    val blockEndSections: List<Int>,
    /** 门户提供考试安排。 */
    val supportsExam: Boolean,
    /** 门户提供成绩查询。 */
    val supportsScore: Boolean,
    /** 成绩含绩点，可展示绩点与均分统计。 */
    val supportsGpa: Boolean,
    val supportsGradeExam: Boolean,
    val supportsProfessionalScore: Boolean,
    val supportsFitnessScore: Boolean,
    /** 门户提供培养计划（研究生为只读课程列表）。 */
    val supportsStudyPlan: Boolean,
    val supportsCampusInfo: Boolean,
    val supportsFinance: Boolean,
    val supportsPartnerSchedule: Boolean,
    /** 该来源允许进入课时统计；具体钟点是否已配置由统计页按作息档案判断。 */
    val supportsCourseTimeStats: Boolean
) {
    /** 该来源的教务菜单是否可用；不可用时界面保留入口并提示 [unsupportedHint]。 */
    fun menuEnabled(item: MenuCapability): Boolean = when (item) {
        MenuCapability.EXAM -> supportsExam
        MenuCapability.SCORE -> supportsScore
        MenuCapability.GRADE_EXAM -> supportsGradeExam
        MenuCapability.PROFESSIONAL_SCORE -> supportsProfessionalScore
        MenuCapability.FITNESS_SCORE -> supportsFitnessScore
        MenuCapability.STUDY_PLAN -> supportsStudyPlan
        MenuCapability.CAMPUS_INFO -> supportsCampusInfo
        MenuCapability.FINANCE -> supportsFinance
        MenuCapability.PARTNER_SCHEDULE -> supportsPartnerSchedule
        MenuCapability.COURSE_TIME_STATS -> supportsCourseTimeStats
        MenuCapability.SEMESTER_HISTORY -> supportsSemesterHistory
    }

    companion object {
        /** 研究生门户没有这些模块，界面保留入口但禁用并显示这句。 */
        const val UNSUPPORTED_HINT = "研究生暂不支持"
    }
}

/** 能力项与抽屉菜单一一对应的枚举，避免界面层再写一遍校区分支。 */
enum class MenuCapability {
    EXAM,
    SCORE,
    GRADE_EXAM,
    PROFESSIONAL_SCORE,
    FITNESS_SCORE,
    STUDY_PLAN,
    CAMPUS_INFO,
    FINANCE,
    PARTNER_SCHEDULE,
    COURSE_TIME_STATS,
    SEMESTER_HISTORY
}

fun capabilitiesFor(campus: CampusType): CampusCapabilities = when (campus) {
    CampusType.GUILIN -> CampusCapabilities(
        requiresCaptcha = false,
        supportsSemesterHistory = true,
        hasNoonSections = true,
        // 上午 1-4、中午 5-6（午1/午2）、下午 7-10、晚上 11-14。
        blockEndSections = listOf(4, 6, 10),
        supportsExam = true,
        supportsScore = true,
        supportsGpa = true,
        supportsGradeExam = true,
        supportsProfessionalScore = true,
        supportsFitnessScore = true,
        supportsStudyPlan = true,
        supportsCampusInfo = true,
        supportsFinance = true,
        supportsPartnerSchedule = true,
        supportsCourseTimeStats = true
    )
    CampusType.NANNING -> CampusCapabilities(
        requiresCaptcha = true,
        supportsSemesterHistory = true,
        hasNoonSections = false,
        // 上午 1-4、下午 5-8、晚上 9-11。南宁没有中午节次，所以分界里没有 6。
        blockEndSections = listOf(4, 8),
        supportsExam = true,
        supportsScore = true,
        supportsGpa = true,
        supportsGradeExam = true,
        supportsProfessionalScore = true,
        supportsFitnessScore = true,
        supportsStudyPlan = true,
        // 南宁同样有校园信息页；此前整项隐藏是历史遗留，三类来源现在口径一致。
        supportsCampusInfo = true,
        supportsFinance = true,
        supportsPartnerSchedule = true,
        supportsCourseTimeStats = true
    )
    // 研究生：门户只暴露课表、成绩与培养计划；成绩不含绩点，不做任何均分推算。
    // 作息默认没有钟点，因此一切依赖时间的统计与倒计时都必须停用。
    CampusType.GRADUATE -> CampusCapabilities(
        requiresCaptcha = true,
        supportsSemesterHistory = true,
        hasNoonSections = false,
        // 上午 1-4、下午 5-8、晚上 9-12；与南宁同形，但晚上多一节。
        blockEndSections = listOf(4, 8),
        supportsExam = false,
        supportsScore = true,
        supportsGpa = false,
        supportsGradeExam = false,
        supportsProfessionalScore = false,
        supportsFitnessScore = false,
        supportsStudyPlan = true,
        supportsCampusInfo = true,
        supportsFinance = false,
        supportsPartnerSchedule = true,
        // 统计页的每一个数字都由作息钟点算出，而研究生门户不提供任何上课时间：
        // 默认作息整份为空，用户也没有权威钟点可填，页面永远算不出结果，因此这里直接禁用。
        supportsCourseTimeStats = false
    )
}
