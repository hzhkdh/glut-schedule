package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewHardeningContractTest {
    @Test
    fun professionalRefreshCancelsOldMessageTimerAndPropagatesCancellation() {
        val source = source("ProfessionalScoreViewModel.kt")

        assertTrue(source.contains("private var messageJob: Job? = null"))
        assertTrue(source.contains("messageJob?.cancel()"))
        assertTrue(source.contains("catch (error: CancellationException)"))
        assertTrue(source.contains("messageJob = viewModelScope.launch"))
    }

    @Test
    fun financeTicketFetchPropagatesCoroutineCancellation() {
        val source = source("FinanceViewModel.kt")
        val fetchTicket = source
            .substringAfter("private suspend fun fetchTicket")
            .substringBefore("private suspend fun fetch(action")

        val cancellationCatch = fetchTicket.indexOf("catch (error: CancellationException)")
        val genericCatch = fetchTicket.indexOf("catch (error: Exception)")
        assertTrue(cancellationCatch >= 0)
        assertTrue(genericCatch > cancellationCatch)
    }

    private fun source(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }
}
