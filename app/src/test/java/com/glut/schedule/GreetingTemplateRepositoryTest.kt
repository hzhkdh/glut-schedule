package com.glut.schedule

import com.glut.schedule.service.greeting.GreetingCategory
import com.glut.schedule.service.greeting.GreetingTemplateCache
import com.glut.schedule.service.greeting.GreetingTemplateCacheSnapshot
import com.glut.schedule.service.greeting.GreetingTemplateRemote
import com.glut.schedule.service.greeting.GreetingTemplateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTemplateRepositoryTest {

    @Test
    fun successfulCacheYoungerThanTwentyFourHoursSkipsNetwork() = runTest {
        val now = 100L * HOUR
        val cache = FakeCache(
            GreetingTemplateCacheSnapshot(
                rawJson = validJson("缓存问候 {name}"),
                lastSuccessEpochMillis = now - 23L * HOUR,
                lastAttemptEpochMillis = now - 23L * HOUR
            )
        )
        val remote = FakeRemote(validJson("远程问候 {name}"))
        val repository = GreetingTemplateRepository(cache, remote)

        val refreshed = repository.initializeAndRefresh(now)

        assertFalse(refreshed)
        assertEquals(0, remote.calls)
        assertEquals(
            listOf("缓存问候 {name}"),
            repository.templates.value.forCategory(GreetingCategory.GREETING)
        )
    }

    @Test
    fun expiredCacheRefreshesAndAtomicallyPublishesValidRemote() = runTest {
        val now = 100L * HOUR
        val cache = FakeCache(
            GreetingTemplateCacheSnapshot(
                rawJson = validJson("旧问候 {name}"),
                lastSuccessEpochMillis = now - 24L * HOUR,
                lastAttemptEpochMillis = 0L
            )
        )
        val remoteJson = validJson("新问候 {name}")
        val repository = GreetingTemplateRepository(cache, FakeRemote(remoteJson))

        val refreshed = repository.initializeAndRefresh(now)

        assertTrue(refreshed)
        assertEquals(remoteJson, cache.snapshot.rawJson)
        assertEquals(now, cache.snapshot.lastSuccessEpochMillis)
        assertEquals(
            listOf("新问候 {name}"),
            repository.templates.value.forCategory(GreetingCategory.GREETING)
        )
    }

    @Test
    fun failedAttemptKeepsLastValidCacheAndBacksOffForOneHour() = runTest {
        val now = 100L * HOUR
        val oldJson = validJson("保留问候 {name}")
        val cache = FakeCache(
            GreetingTemplateCacheSnapshot(
                rawJson = oldJson,
                lastSuccessEpochMillis = now - 30L * HOUR,
                lastAttemptEpochMillis = now - 30L * HOUR
            )
        )
        val remote = FakeRemote("""{"schemaVersion":2}""")
        val repository = GreetingTemplateRepository(cache, remote)

        assertFalse(repository.initializeAndRefresh(now))
        assertFalse(repository.initializeAndRefresh(now + HOUR - 1L))

        assertEquals(1, remote.calls)
        assertEquals(oldJson, cache.snapshot.rawJson)
        assertEquals(
            listOf("保留问候 {name}"),
            repository.templates.value.forCategory(GreetingCategory.GREETING)
        )
    }

    @Test
    fun incompatibleFreshCacheDoesNotSuppressRemoteRefresh() = runTest {
        val now = 100L * HOUR
        val cache = FakeCache(
            GreetingTemplateCacheSnapshot(
                rawJson = """{"schemaVersion":2,"contentVersion":1,"templates":{}}""",
                lastSuccessEpochMillis = now - HOUR,
                lastAttemptEpochMillis = now - 2L * HOUR
            )
        )
        val remote = FakeRemote(validJson("兼容的新问候 {name}"))
        val repository = GreetingTemplateRepository(cache, remote)

        assertTrue(repository.initializeAndRefresh(now))
        assertEquals(1, remote.calls)
        assertEquals(
            listOf("兼容的新问候 {name}"),
            repository.templates.value.forCategory(GreetingCategory.GREETING)
        )
    }

    private class FakeCache(
        var snapshot: GreetingTemplateCacheSnapshot
    ) : GreetingTemplateCache {
        override suspend fun read(): GreetingTemplateCacheSnapshot = snapshot

        override suspend fun recordAttempt(nowEpochMillis: Long) {
            snapshot = snapshot.copy(lastAttemptEpochMillis = nowEpochMillis)
        }

        override suspend fun saveSuccess(rawJson: String, nowEpochMillis: Long) {
            snapshot = GreetingTemplateCacheSnapshot(rawJson, nowEpochMillis, nowEpochMillis)
        }
    }

    private class FakeRemote(private val response: String?) : GreetingTemplateRemote {
        var calls = 0

        override suspend fun fetch(): String? {
            calls++
            return response
        }
    }

    private fun validJson(greeting: String) = """
        {
          "schemaVersion": 1,
          "contentVersion": 1,
          "templates": {"greeting": ["$greeting"]}
        }
    """.trimIndent()

    companion object {
        private const val HOUR = 60L * 60L * 1000L
    }
}
