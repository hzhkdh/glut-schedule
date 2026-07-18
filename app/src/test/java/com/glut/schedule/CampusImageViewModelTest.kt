package com.glut.schedule

import com.glut.schedule.service.campus.CampusImageDocument
import com.glut.schedule.service.campus.CampusImageGateway
import com.glut.schedule.service.campus.CampusImageType
import com.glut.schedule.ui.pages.CampusImageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusImageViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initialLoadDisplaysOfficialImage() = runTest {
        val document = document()
        val gateway = FakeGateway(Result.success(document))

        val viewModel = CampusImageViewModel(gateway, CampusImageType.ACADEMIC_CALENDAR)

        assertEquals(document, viewModel.uiState.value.document)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(false), gateway.forceRefreshValues)
    }

    @Test
    fun refreshBypassesCachesAndExplainsCachedFallback() = runTest {
        val cached = document().copy(fromCache = true)
        val gateway = FakeGateway(Result.success(cached))
        val viewModel = CampusImageViewModel(gateway, CampusImageType.SHUTTLE_BUS)
        gateway.forceRefreshValues.clear()

        viewModel.refresh()

        assertEquals(listOf(true), gateway.forceRefreshValues)
        assertTrue(viewModel.uiState.value.message.contains("缓存"))
    }

    @Test
    fun failureWithoutCacheShowsRetryMessage() = runTest {
        val gateway = FakeGateway(Result.failure(IllegalStateException("offline")))

        val viewModel = CampusImageViewModel(gateway, CampusImageType.SHUTTLE_BUS)

        assertEquals(null, viewModel.uiState.value.document)
        assertTrue(viewModel.uiState.value.message.contains("重试"))
    }

    private fun document() = CampusImageDocument(
        imageUrl = "https://xxfw.glut.edu.cn/image.png",
        bytes = byteArrayOf(1, 2, 3),
        fetchedAt = 100L
    )

    private class FakeGateway(private var result: Result<CampusImageDocument>) : CampusImageGateway {
        val forceRefreshValues = mutableListOf<Boolean>()
        override suspend fun fetch(type: CampusImageType, forceRefresh: Boolean): CampusImageDocument {
            forceRefreshValues += forceRefresh
            return result.getOrThrow()
        }
    }
}
