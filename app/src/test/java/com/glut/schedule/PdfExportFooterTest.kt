package com.glut.schedule

import com.glut.schedule.service.export.pdfExportFooter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfExportFooterTest {
    @Test fun footerUsesBrandAndExportDayInIsoFormat() {
        assertEquals("由「桂系一站式」导出·2026-01-09", pdfExportFooter(LocalDate.of(2026, 1, 9)))
    }
}
