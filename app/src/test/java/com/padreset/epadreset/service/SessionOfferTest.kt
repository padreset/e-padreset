package com.padreset.epadreset.service

import com.padreset.epadreset.data.PrinterSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an opened D4 session may do once the catalog has been asked.
 *
 * An unknown model is not a failed connect. It is a session with no keys,
 * so the only jobs that do not need a profile stay available. Waste writes
 * and power cleaning do not: one needs addresses, the other fills a pad
 * this app could not put back.
 */
class SessionOfferTest {

    @Test
    fun `an unknown model keeps status and gentle maintenance and nothing that writes`() {
        val offer = SessionOffer.decide(spec = null)

        assertTrue(offer.readOnly)
        assertTrue(offer.nozzleCheck)
        assertTrue(offer.gentleClean)
        assertFalse(offer.powerClean)
        assertFalse(offer.wasteWrite)
        assertFalse(offer.serviceCleaning)
    }

    @Test
    fun `a catalogued model keeps the jobs its spec actually publishes`() {
        val offer = SessionOffer.decide(spec())

        assertFalse(offer.readOnly)
        assertTrue(offer.powerClean)
        assertTrue(offer.wasteWrite)
        assertTrue(offer.serviceCleaning)
    }

    @Test
    fun `power cleaning on a read-only session is refused before any bytes are sent`() {
        val refused = runCatching {
            SessionOffer.refusePowerClean(SessionOffer.decide(spec = null), powerClean = true)
        }.exceptionOrNull()

        assertTrue(refused is SessionOffer.PowerCleanRefused)
        SessionOffer.refusePowerClean(SessionOffer.decide(spec()), powerClean = true)
        SessionOffer.refusePowerClean(SessionOffer.decide(spec = null), powerClean = false)
    }

    private fun spec() = PrinterSpec(
        modelName = "L365 Series",
        controlModel = byteArrayOf(0, 0),
        keyword = ByteArray(8),
        wasteCounters = listOf(
            com.padreset.epadreset.data.WasteCounterSpec("Main pad", listOf(0x18), 100),
        ),
        resetMap = mapOf(0x18 to 0),
        cleaningOptions = listOf(
            com.padreset.epadreset.data.CleaningSpec("CL", "service", byteArrayOf(0x01)),
        ),
    )
}
