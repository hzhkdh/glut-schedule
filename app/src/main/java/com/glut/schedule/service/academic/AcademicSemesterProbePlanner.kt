package com.glut.schedule.service.academic

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.service.parser.AcademicSemesterCatalogPlan

data class AcademicSemesterProbeDecision(
    val catalog: List<AcademicSemester>,
    val currentSemester: AcademicSemester,
    val promotedPayload: AcademicSemesterImportPayload?
)

object AcademicSemesterProbePlanner {
    fun decide(
        plan: AcademicSemesterCatalogPlan,
        probeResult: Result<AcademicSemesterImportPayload>?
    ): AcademicSemesterProbeDecision {
        val portalSelected = plan.semesters.firstOrNull { it.isCurrent }
            ?: plan.semesters.first()
        val payload = probeResult?.getOrNull()
        val shouldPromote = plan.nextSemester != null &&
            payload?.responseKind == AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE &&
            payload.courses.isNotEmpty()
        if (!shouldPromote) {
            return AcademicSemesterProbeDecision(plan.semesters, portalSelected, null)
        }

        val promoted = requireNotNull(plan.nextSemester).copy(
            isCurrent = true,
            semesterStartDate = null,
            semesterEndDate = null
        )
        return AcademicSemesterProbeDecision(
            catalog = plan.semesters.map { it.copy(isCurrent = false) } + promoted,
            currentSemester = promoted,
            promotedPayload = payload
        )
    }
}
