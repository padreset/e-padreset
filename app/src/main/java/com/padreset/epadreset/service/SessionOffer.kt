package com.padreset.epadreset.service

import com.padreset.epadreset.data.PrinterSpec

/**
 * What an opened D4 session may do once the catalog has been asked.
 *
 * An unknown model is not a failed connect. It is a session with no keys,
 * so status, a nozzle check and a gentle ESC/P2 clean stay available.
 * Waste writes need addresses. Power cleaning fills a pad this app could
 * not put back. Neither is offered, and neither can be reached by passing
 * a stub profile: there is no profile.
 */
internal object SessionOffer {
    data class Offer(
        val readOnly: Boolean,
        val nozzleCheck: Boolean,
        val gentleClean: Boolean,
        val powerClean: Boolean,
        val wasteWrite: Boolean,
        val serviceCleaning: Boolean,
    )

    class PowerCleanRefused :
        Exception("Power cleaning is not available without a verified profile")

    fun decide(spec: PrinterSpec?, unverifiedLocal: Boolean = false): Offer {
        if (spec == null) {
            return Offer(
                readOnly = true,
                nozzleCheck = true,
                gentleClean = true,
                powerClean = false,
                wasteWrite = false,
                serviceCleaning = false,
            )
        }
        return Offer(
            readOnly = false,
            nozzleCheck = true,
            gentleClean = true,
            powerClean = !unverifiedLocal,
            wasteWrite = spec.supportsWasteReset,
            serviceCleaning = spec.cleaningOptions.isNotEmpty(),
        )
    }

    /** Throws before a power-clean job is built. A gentle clean is not this. */
    fun refusePowerClean(offer: Offer, powerClean: Boolean) {
        if (powerClean && !offer.powerClean) throw PowerCleanRefused()
    }
}
