package com.transfer.flash.server.announcements

import com.transfer.flash.server.storage.ServerDatabase
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SRVHUB-03 (ANN-01): announcement writes are serialised, so seq stays unique and the limits hold under concurrency. */
class AnnouncementStoreConcurrencyTest {

    private lateinit var db: ServerDatabase
    private lateinit var store: AnnouncementStore

    @BeforeTest
    fun setUp() {
        // A WAL file with several pooled connections, like production: a one-connection pool would serialise the writers
        // by itself, and the shared-cache memory mode fails with SQLITE_LOCKED, which busy_timeout does not cover.
        val dir = Files.createTempDirectory("flash-ann-race")
        db = ServerDatabase("jdbc:sqlite:${dir.resolve("announcements.db")}", maxPoolSize = 24)
        store = AnnouncementStore.open(db) { 1_000_000L }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    /** Runs every task at the same moment on its own thread and returns each outcome (value or the thrown exception). */
    private fun <T> race(vararg tasks: () -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val ready = CountDownLatch(tasks.size)
            val go = CountDownLatch(1)
            val futures = tasks.map { task ->
                pool.submit<Result<T>> {
                    ready.countDown()
                    go.await()
                    runCatching(task)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun create(requestId: String = java.util.UUID.randomUUID().toString(), pinned: Boolean = false, title: String = "T") =
        store.createAnnouncement(
            clientRequestId = requestId, scope = "org", departmentId = null, title = title, body = "Body of $title",
            authorKind = "console", authorRef = "console", authorName = "Console", authorRole = "admin", pinned = pinned,
        )

    @Test
    fun concurrentCreatesGetDistinctSeqNumbersWithNoGaps() {
        var expected = 0L
        repeat(10) { round ->
            val items = race(*Array(20) { i -> { create(title = "Item $round-$i") } }).map { it.getOrThrow() }
            expected += 20
            assertEquals(20, items.map { it.seq }.toSet().size, "round $round: every announcement has its own change number")
            assertEquals(((expected - 19)..expected).toSet(), items.map { it.seq }.toSet(), "round $round: and the numbers have no gaps")
        }
        assertEquals(expected, store.currentSeq())
    }

    @Test
    fun concurrentUpdatesWithTheSameExpectedSeqOneWinsAndTheRestConflict() {
        repeat(10) { round ->
            val item = create(title = "Round $round")
            val results = race(*Array(8) { i -> { store.updateAnnouncement(item.id, expectedSeq = item.seq, title = "Edit $i", actorRole = "admin") } })
            assertEquals(1, results.count { it.isSuccess }, "round $round: exactly one update is accepted: $results")
            assertTrue(
                results.filter { it.isFailure }.all { it.exceptionOrNull() is AnnouncementConflictException },
                "round $round: the others get the conflict, not a silent overwrite: $results",
            )
        }
    }

    @Test
    fun concurrentPinsWithTwoAlreadyPinnedOnlyOneFits() {
        repeat(5) { round ->
            // each round uses its own department scope so earlier rounds' pins do not count
            val dept = "dept-$round"
            fun make(title: String, pinned: Boolean) = store.createAnnouncement(
                scope = "department", departmentId = dept, title = title, body = "Body $title",
                authorKind = "console", authorRef = "console", authorName = "Console", authorRole = "admin", pinned = pinned,
            )
            make("pinned 1", true)
            make("pinned 2", true)
            val candidates = List(8) { make("candidate $it", false) }
            val results = race(*Array(8) { i -> { store.setPinned(candidates[i].id, true, actorRole = "admin") } })
            assertEquals(1, results.count { it.isSuccess }, "round $round: limit is 3: $results")
            assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is AnnouncementPinLimitException }, "round $round: $results")
        }
    }

    @Test
    fun concurrentCreatesWithTheSameClientRequestIdMakeOneRow() {
        repeat(10) { round ->
            val before = store.currentSeq()
            val items = race(*Array(8) { { create(requestId = "same-request-$round") } }).map { it.getOrThrow() }
            assertEquals(1, items.map { it.id }.toSet().size, "round $round: a retried request is one announcement")
            assertEquals(before + 1, store.currentSeq(), "round $round: and one change number")
        }
    }
}
