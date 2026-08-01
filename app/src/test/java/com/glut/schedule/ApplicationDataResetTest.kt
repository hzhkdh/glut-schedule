package com.glut.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationDataResetTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resetWaitsForActiveDownloadsToCancelBeforeClearingData() = runTest {
        val allowCancellationToFinish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val reset = async {
            resetApplicationDataSafely(
                cancelActiveDownloads = {
                    events += "cancel-start"
                    allowCancellationToFinish.await()
                    events += "cancel-finish"
                },
                clearData = { events += "clear" }
            )
        }

        runCurrent()
        assertEquals(listOf("cancel-start"), events)

        allowCancellationToFinish.complete(Unit)
        reset.await()

        assertEquals(listOf("cancel-start", "cancel-finish", "clear"), events)
    }
}
