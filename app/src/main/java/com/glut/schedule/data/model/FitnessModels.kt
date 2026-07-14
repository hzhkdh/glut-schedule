package com.glut.schedule.data.model

data class FitnessScoreItem(
    val name: String,
    val testScore: String = "",
    val score: String = "",
    val conclusion: String = "",
    val standardScore: String = "",
    val bonusScore: String = ""
)

data class FitnessResult(
    val items: List<FitnessScoreItem> = emptyList(),
    val totalScore: String = "",
    val totalLevel: String = ""
)

data class FitnessHistoryRequest(
    val studentNo: String,
    val academicYear: String,
    val term: String,
    val gradeNo: String,
    val sex: String
)

data class FitnessHistoryRecord(
    val year: String,
    val term: String,
    val grade: String = "",
    val totalScore: String = "",
    val totalLevel: String = "",
    val detailRequest: FitnessHistoryRequest? = null
) {
    val key: String get() = "$year-$term"
}

data class FitnessSnapshot(
    val current: FitnessResult,
    val history: List<FitnessHistoryRecord>
)

enum class FitnessStandardType { COMPOSITE, BMI, BONUS }

data class FitnessStandardRow(
    val level: String = "",
    val score: String = "",
    val label: String = "",
    val values: List<String> = emptyList()
)

data class FitnessStandardTable(
    val key: String,
    val title: String,
    val type: FitnessStandardType,
    val headers: List<String>,
    val rows: List<FitnessStandardRow>,
    val scores: List<String> = emptyList(),
    val weightNote: String = "",
    val note: String = ""
)
