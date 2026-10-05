package com.padreset.epadreset.ui

import androidx.lifecycle.viewModelScope
import com.padreset.epadreset.R
import com.padreset.epadreset.data.EpsonPrinterCatalog
import com.padreset.epadreset.data.GapCommit
import com.padreset.epadreset.data.GapProfile
import com.padreset.epadreset.data.GapProfileStore
import com.padreset.epadreset.data.GapRefusal
import com.padreset.epadreset.data.commitGapPaste
import com.padreset.epadreset.data.selectGap
import com.padreset.epadreset.protocol.EpsonProtocol
import com.padreset.epadreset.service.PrinterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The lock covers persistence and adoption; only disk/parsing leaves Main. */
internal suspend fun MainViewModel.pasteGapProfile(text: String): String? {
    var error: String? = null
    replaceProfile {
        val before = connection
        val result = withContext(Dispatchers.IO) {
            commitGapPaste(
                text = text,
                isBox = { EpsonPrinterCatalog.usesMaintenanceBox(getApplication(), it) },
                isCatalogued = { name -> EpsonPrinterCatalog.resolve(getApplication(), "MDL:$name;") != null },
                store = GapProfileStore.from(getApplication()),
            )
        }
        error = when (result) {
            is GapCommit.Saved -> {
                if (connection === before) adoptGap(result.profile)
                null
            }
            is GapCommit.Failed -> getApplication<android.app.Application>().getString(gapRefusal(result.reason))
        }
    }
    return error ?: uiState.value.error
}

internal fun MainViewModel.deleteGapProfile() {
    viewModelScope.launch {
        replaceProfile {
            val name = uiState.value.connectedModelName ?: return@replaceProfile
            val current = connection as? PrinterService.ConnectionState.Catalogued ?: return@replaceProfile
            withContext(Dispatchers.IO) { GapProfileStore.from(getApplication()).delete(name) }
            if (!current.unverifiedLocal || connection !== current) return@replaceProfile
            connection = PrinterService.ConnectionState.ReadOnly(
                session = current.session,
                protocol = EpsonProtocol.statusOnly(name),
                deviceId = current.deviceId,
                backend = current.backend,
                controlChannel = current.controlChannel,
                modelName = name,
                usesMaintenanceBox = current.spec.usesMaintenanceBox,
            )
        }
    }
}

internal fun keepsModelReport(current: PrinterService.ConnectionState?): Boolean =
    current is PrinterService.ConnectionState.ReadOnly ||
        (current is PrinterService.ConnectionState.Catalogued && current.unverifiedLocal)

private fun MainViewModel.adoptGap(profile: GapProfile) {
    val current = connection as? PrinterService.ConnectionState.ReadOnly ?: return
    if (selectGap(current.modelName.orEmpty(), catalogued = false, gaps = listOf(profile)) == null) return
    val spec = profile.toSpec()
    connection = PrinterService.ConnectionState.Catalogued(
        session = current.session,
        spec = spec,
        protocol = EpsonProtocol(spec),
        deviceId = current.deviceId,
        backend = current.backend,
        controlChannel = current.controlChannel,
        unverifiedLocal = true,
    )
}

private fun gapRefusal(reason: GapRefusal): Int = when (reason) {
    GapRefusal.PROVENANCE -> R.string.error_gap_provenance
    GapRefusal.MALFORMED -> R.string.error_gap_malformed
    GapRefusal.MAINTENANCE_BOX -> R.string.error_gap_box
    GapRefusal.DOCTYPE -> R.string.error_gap_doctype
    GapRefusal.CATALOGUED -> R.string.error_gap_catalogued
    GapRefusal.FULL -> R.string.error_gap_full
}
