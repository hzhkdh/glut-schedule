package com.glut.schedule.data.model

/**
 * 周次文本的「去掉某一周」与「压缩成区间文本」。
 *
 * 这两个函数原本是 `AcademicScheduleParser` 的私有实现（教务调课移除原周次时用）。
 * 手动隐藏卡片要按周剥掉课次周次，用的是同一套口径，所以提取到这里共用——
 * 仓库里的约定是同一个规则只保留一份实现，避免两份逻辑慢慢漂移。
 */

/**
 * 把周次文本去掉指定的一周，返回**可能是多段**的剩余周次文本。
 *
 * 之所以返回列表而不是单个字符串：`1-16周` 去掉第 5 周之后没法用一段文本表示，
 * 必须拆成 `["第1-4周", "第6-16周"]`，调用方要为每一段各生成一个课次。
 * 所有周次都被去掉时返回空列表，调用方据此丢弃该课次。
 */
internal fun weekTextWithoutWeek(weekText: String, removedWeek: Int): List<String> {
    val remainingWeeks = academicWeeksForText(weekText).filter { it != removedWeek }
    return compactWeekNumbers(remainingWeeks)
}

/** 把周次编号列表压成 `第5周` / `1-16周` 这样的可读文本，连续编号合并成一个区间。 */
internal fun compactWeekNumbers(weeks: List<Int>): List<String> {
    if (weeks.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = weeks.first()
    var previous = start
    weeks.drop(1).forEach { week ->
        if (week == previous + 1) {
            previous = week
        } else {
            ranges += start..previous
            start = week
            previous = week
        }
    }
    ranges += start..previous
    return ranges.map { range ->
        if (range.first == range.last) {
            "第${range.first}周"
        } else {
            "${range.first}-${range.last}周"
        }
    }
}
