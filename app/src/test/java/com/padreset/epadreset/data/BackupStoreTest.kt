package com.padreset.epadreset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the store through a fake backing store and a scripted clock.
 *
 * The two properties worth pinning are the ones a user would feel: the newest
 * record is the one offered first, and the list stops growing at five so the
 * store cannot become a log nobody prunes. Neither needs a device, which is why
 * [BackupStorage] exists as an interface.
 */
class BackupStoreTest {

    private class FakeBackupStorage(private var blob: String? = null) : BackupStorage {
        var writes = 0
            private set

        override fun read(): String? = blob

        override fun write(blob: String) {
            this.blob = blob
            writes += 1
        }
    }

    private var clock = 1_000L

    private fun store(storage: BackupStorage = FakeBackupStorage()): BackupStore =
        BackupStore(storage = storage, now = { clock }) { }

    private fun values(marker: Int): Map<Int, Byte> = mapOf(0x1C to marker.toByte())

    @Test
    fun `the newest record is offered first`() {
        val subject = store()
        subject.save("first", values(0x11))
        clock += 1_000
        subject.save("second", values(0x22))

        val history = subject.history()

        assertEquals(listOf("second", "first"), history.map { it.model })
    }

    @Test
    fun `the history stops at five records`() {
        val subject = store()
        repeat(6) { index ->
            subject.save("model-$index", values(index))
            clock += 1_000
        }

        val history = subject.history()

        assertEquals(BackupStore.MAX_RECORDS, history.size)
        // The sixth save pushes the first one out, and the newest is still first.
        assertEquals("model-5", history.first().model)
        assertTrue(
            "the oldest record should have been dropped: ${history.map { it.model }}",
            history.none { it.model == "model-0" },
        )
    }

    @Test
    fun `two saves never share a timestamp`() {
        // A fixed clock is the worst case: the record's identity is its timestamp,
        // and two writes inside one millisecond would otherwise collide.
        val subject = store()
        subject.save("first", values(0x11))
        subject.save("second", values(0x22))

        val timestamps = subject.history().map { it.timestamp }

        assertEquals(2, timestamps.distinct().size)
        assertNotEquals(timestamps[0], timestamps[1])
    }

    @Test
    fun `an empty store has an empty history`() {
        assertTrue(store().history().isEmpty())
    }

    @Test
    fun `a corrupt blob reads as empty instead of throwing`() {
        val subject = store(FakeBackupStorage(blob = "not a backup at all"))

        assertTrue(subject.history().isEmpty())
    }

    @Test
    fun `an empty backup is not stored`() {
        val storage = FakeBackupStorage()
        val subject = store(storage)

        subject.save("L365", emptyMap())

        assertEquals(0, storage.writes)
        assertTrue(subject.history().isEmpty())
    }

    // -- de-duplication ------------------------------------------------------

    @Test
    fun `saving the same state again does not add a record`() {
        // The case this exists for: a reset saves the state, a restore writes it
        // back, and the restore's own backup is the state the reset produced —
        // which is a state the list already knows.
        val subject = store()
        subject.save("L365", values(0x11), BackupOrigin.RESET)
        val before = subject.history()
        clock += 1_000

        val outcome = subject.save("L365", values(0x11), BackupOrigin.RESTORE)

        assertTrue("expected the state to be recognised", outcome.deduplicated)
        assertEquals(before, subject.history())
    }

    @Test
    fun `a duplicate leaves the existing record's time and origin alone`() {
        // Re-stamping the record would turn "the state before my reset" into "the
        // state before a restore", which is the one label the user needs.
        val subject = store()
        subject.save("L365", values(0x11), BackupOrigin.RESET)
        val original = subject.history().single()
        clock += 60_000

        subject.save("L365", values(0x11), BackupOrigin.RESTORE)

        assertEquals(original, subject.history().single())
    }

    @Test
    fun `two models with identical bytes are not the same record`() {
        // Different printers can hold the same bytes at the same addresses by
        // coincidence, and the addresses mean different things on each.
        val subject = store()
        subject.save("ET-2820", values(0x11))
        clock += 1_000

        val outcome = subject.save("L365", values(0x11))

        assertEquals(false, outcome.deduplicated)
        assertEquals(listOf("L365", "ET-2820"), subject.history().map { it.model })
    }

    @Test
    fun `a model name and its series name collapse`() {
        val subject = store()
        subject.save("L365", values(0x11))
        clock += 1_000

        val outcome = subject.save("L365 Series", values(0x11))

        assertTrue("the catalog treats these as one printer", outcome.deduplicated)
        assertEquals(1, subject.history().size)
    }

    @Test
    fun `a duplicate never pushes a record out of a full history`() {
        val subject = store()
        repeat(5) { index ->
            subject.save("model-$index", values(index))
            clock += 1_000
        }
        val before = subject.history()
        clock += 1_000

        val outcome = subject.save("model-0", values(0))

        assertTrue(outcome.deduplicated)
        assertEquals(BackupStore.MAX_RECORDS, outcome.history.size)
        assertEquals(before, subject.history())
    }

    @Test
    fun `a saved origin reads back from the history`() {
        val subject = store()

        subject.save("L365", values(0x11), BackupOrigin.RESTORE)

        assertEquals(BackupOrigin.RESTORE, subject.history().single().origin)
    }

    @Test
    fun `a record with no origin given reads back as unknown`() {
        val subject = store()

        subject.save("L365", values(0x11))

        assertEquals(BackupOrigin.UNKNOWN, subject.history().single().origin)
    }

    @Test
    fun `a save that fails never claims a persisted history entry`() {
        val storage = object : BackupStorage {
            override fun read(): String? = null
            override fun write(blob: String) = error("disk full")
        }
        val lines = mutableListOf<String>()
        val subject = BackupStore(storage, now = { 1L }, log = { lines += it })

        val outcome = subject.save("L365", values(0x11))

        assertEquals(0, outcome.history.size)
        assertEquals(false, outcome.persisted)
        assertTrue(lines.any { it.contains("could not be saved") })
    }
}
