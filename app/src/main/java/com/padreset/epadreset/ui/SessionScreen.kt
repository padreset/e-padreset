package com.padreset.epadreset.ui

import com.padreset.epadreset.data.ModelSupportReport
import com.padreset.epadreset.data.WasteCounterSpec
import com.padreset.epadreset.service.PrinterService
import com.padreset.epadreset.service.SessionOffer

/**
 * What the status screen shows for an open session.
 *
 * Kept out of [MainViewModel] so the read-only case — no profile, no waste
 * write, no power clean, report still available — is one decision instead of
 * a branch buried in the screen update.
 */
internal data class SessionScreen(
    val cleaningOptions: List<String>,
    val supportsGenericMaintenanceJobs: Boolean,
    val allowsPowerClean: Boolean,
    val readOnlySession: Boolean,
    val unverifiedLocalProfile: Boolean,
    val supportsWasteReset: Boolean,
    val usesMaintenanceBox: Boolean,
    val wasteCounterSpecs: List<WasteCounterSpec>,
    val untouchableAddresses: Set<Int>,
    val connectedModelName: String?,
    val modelReport: ModelSupportReport.Report?,
    val modelReportUrl: String?,
)

internal fun sessionScreen(
    current: PrinterService.ConnectionState,
    appVersion: String,
    androidVersion: String,
    reportUrl: String?,
    citation: ModelSupportReport.Citation? = null,
): SessionScreen {
    val catalogued = current as? PrinterService.ConnectionState.Catalogued
    val unverified = catalogued?.unverifiedLocal == true
    val offer = when (current) {
        is PrinterService.ConnectionState.Catalogued ->
            SessionOffer.decide(current.spec, unverifiedLocal = unverified)
        is PrinterService.ConnectionState.ReadOnly -> SessionOffer.decide(null)
    }
    val readOnly = current as? PrinterService.ConnectionState.ReadOnly
    val report = if (readOnly != null || unverified) {
        ModelSupportReport.build(
            ModelSupportReport.Source(
                deviceId = current.deviceId,
                appVersion = appVersion,
                androidVersion = androidVersion,
                vendorId = current.session.device.vendorId,
                productId = current.session.device.productId,
                maintenanceBox = catalogued?.spec?.usesMaintenanceBox ?: readOnly?.usesMaintenanceBox ?: false,
                statusOpened = true,
                localProfile = citation,
            ),
        )
    } else {
        null
    }
    return SessionScreen(
        cleaningOptions = catalogued?.spec?.cleaningOptions?.map { it.name }.orEmpty(),
        supportsGenericMaintenanceJobs = offer.gentleClean || offer.nozzleCheck,
        allowsPowerClean = offer.powerClean,
        readOnlySession = offer.readOnly,
        unverifiedLocalProfile = unverified,
        supportsWasteReset = offer.wasteWrite,
        usesMaintenanceBox = catalogued?.spec?.usesMaintenanceBox ?: readOnly?.usesMaintenanceBox ?: false,
        wasteCounterSpecs = catalogued?.spec?.wasteCounters.orEmpty(),
        untouchableAddresses = catalogued?.spec?.untouchableAddresses.orEmpty(),
        connectedModelName = catalogued?.spec?.modelName ?: readOnly?.modelName,
        modelReport = report,
        modelReportUrl = report?.let { reportUrl },
    )
}
