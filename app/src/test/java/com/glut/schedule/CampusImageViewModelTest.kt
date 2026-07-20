package com.glut.schedule

import com.glut.schedule.service.campus.CampusImageDocument
import com.glut.schedule.service.campus.CampusImageGateway
import com.glut.schedule.service.campus.CampusImageType
import com.glut.schedule.ui.pages.CampusImageViewModel
import kotlinx.coroutines.CompletableDeferred
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
    fun initialLoadDisplaysOnlyTheAcademicCalendar() = runTest {
        val calendar = document(CampusImageType.ACADEMIC_CALENDAR)
        val gateway = FakeGateway().apply {
            results[CampusImageType.ACADEMIC_CALENDAR] = Result.success(calendar)
        }

        val viewModel = CampusImageViewModel(gateway)

        assertEquals(CampusImageType.ACADEMIC_CALENDAR, viewModel.uiState.value.selectedType)
        assertEquals(calendar, viewModel.uiState.value.document)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(
            listOf(FetchCall(CampusImageType.ACADEMIC_CALENDAR, false)),
            gateway.calls
        )
    }

    @Test
    fun selectingATabLoadsItOnceAndPreservesPreviouslyLoadedDocuments() = runTest {
        val calendar = document(CampusImageType.ACADEMIC_CALENDAR)
        val classTime = document(CampusImageType.CLASS_TIME)
        val gateway = FakeGateway().apply {
            results[CampusImageType.ACADEMIC_CALENDAR] = Result.success(calendar)
            results[CampusImageType.CLASS_TIME] = Result.success(classTime)
        }
        val viewModel = CampusImageViewModel(gateway)

        viewModel.selectType(CampusImageType.CLASS_TIME)
        viewModel.selectType(CampusImageType.ACADEMIC_CALENDAR)

        assertEquals(calendar, viewModel.uiState.value.document)
        assertEquals(
            listOf(
                FetchCall(CampusImageType.ACADEMIC_CALENDAR, false),
                FetchCall(CampusImageType.CLASS_TIME, false)
            ),
            gateway.calls
        )
    }

    @Test
    fun selectingTheBundledCampusMapNeverUsesTheNetworkGateway() = runTest {
        val gateway = FakeGateway().apply {
            results[CampusImageType.ACADEMIC_CALENDAR] = Result.success(document(CampusImageType.ACADEMIC_CALENDAR))
        }
        val viewModel = CampusImageViewModel(gateway)
        gateway.calls.clear()

        viewModel.selectType(CampusImageType.CAMPUS_MAP)
        viewModel.refreshCurrent()

        assertEquals(CampusImageType.CAMPUS_MAP, viewModel.uiState.value.selectedType)
        assertEquals(emptyList<FetchCall>(), gateway.calls)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun refreshOnlyBypassesCachesForTheSelectedTab() = runTest {
        val cached = document(CampusImageType.SHUTTLE_BUS).copy(fromCache = true)
        val gateway = FakeGateway().apply {
            results[CampusImageType.ACADEMIC_CALENDAR] = Result.success(document(CampusImageType.ACADEMIC_CALENDAR))
            results[CampusImageType.SHUTTLE_BUS] = Result.success(cached)
        }
        val viewModel = CampusImageViewModel(gateway)
        viewModel.selectType(CampusImageType.SHUTTLE_BUS)
        gateway.calls.clear()

        viewModel.refreshCurrent()

        assertEquals(listOf(FetchCall(CampusImageType.SHUTTLE_BUS, true)), gateway.calls)
        assertTrue(viewModel.uiState.value.message.contains("缓存"))
    }

    @Test
    fun failureWithoutCacheShowsRetryMessage() = runTest {
        val gateway = FakeGateway().apply {
            results[CampusImageType.ACADEMIC_CALENDAR] = Result.failure(IllegalStateException("offline"))
        }

        val viewModel = CampusImageViewModel(gateway)

        assertEquals(null, viewModel.uiState.value.document)
        assertTrue(viewModel.uiState.value.message.contains("重试"))
    }

    @Test
    fun repeatedRefreshIsIgnoredWhileTheCurrentTabIsLoading() = runTest {
        val deferred = CompletableDeferred<CampusImageDocument>()
        val gateway = object : CampusImageGateway {
            val calls = mutableListOf<FetchCall>()
            override suspend fun fetch(type: CampusImageType, forceRefresh: Boolean): CampusImageDocument {
                calls += FetchCall(type, forceRefresh)
                return deferred.await()
            }
        }
        val viewModel = CampusImageViewModel(gateway)

        viewModel.refreshCurrent()
        viewModel.refreshCurrent()

        assertEquals(listOf(FetchCall(CampusImageType.ACADEMIC_CALENDAR, false)), gateway.calls)
        assertTrue(viewModel.uiState.value.isLoading)
        deferred.complete(document(CampusImageType.ACADEMIC_CALENDAR))
    }

    private fun document(type: CampusImageType) = CampusImageDocument(
        imageUrl = "https://xxfw.glut.edu.cn/${type.name.lowercase()}.png",
        bytes = byteArrayOf(1, 2, 3),
        fetchedAt = 100L
    )

    private data class FetchCall(val type: CampusImageType, val forceRefresh: Boolean)

    private class FakeGateway : CampusImageGateway {
        val results = mutableMapOf<CampusImageType, Result<CampusImageDocument>>()
        val calls = mutableListOf<FetchCall>()
        override suspend fun fetch(type: CampusImageType, forceRefresh: Boolean): CampusImageDocument {
            calls += FetchCall(type, forceRefresh)
            return results.getValue(type).getOrThrow()
        }
    }
}
