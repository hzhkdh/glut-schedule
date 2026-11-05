package com.glut.schedule.service.academic

/** 只保存白名单资料与原始采集时间；快照身份由校区地址和学号共同确定。 */
data class PersonalCenterSnapshot(val base: String, val studentNo: String, val values: Map<String, String>, val updatedAt: Long)

internal fun PersonalCenterSnapshot.matches(base: String, studentNo: String): Boolean =
    studentNo.isNotBlank() && this.base == base && this.studentNo == studentNo && values["studentNo"] == studentNo
