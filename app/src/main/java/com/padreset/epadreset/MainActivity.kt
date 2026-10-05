package com.padreset.epadreset

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padreset.epadreset.data.EpsonPrinterCatalog
import com.padreset.epadreset.ui.EPadResetApp
import com.padreset.epadreset.ui.MainViewModel
import com.padreset.epadreset.ui.deleteGapProfile
import com.padreset.epadreset.ui.pasteGapProfile
import com.padreset.epadreset.ui.theme.EPadResetTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    /**
     * Watches for the printer being unplugged while the app is open.
     *
     * Registered at runtime rather than in the manifest on purpose: this is not
     * a way to launch the app, it is a way to notice that the session it is
     * holding has ended. Without it the app kept showing counters read from a
     * printer that was no longer there.
     */
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                viewModel.onPrinterDetached()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val attached = if (savedInstanceState == null) epsonDevice(intent) else null
        if (attached != null) {
            viewModel.connect(attached)
        } else {
            viewModel.autoScanOnce()
        }

        setContent {
            EPadResetTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    EPadResetApp(
                        uiState = uiState,
                        // The flow itself, not a collected list: collecting it
                        // here would recompose everything below on every log
                        // line, which is what the separate flow avoids.
                        logs = viewModel.logs,
                        onConnect = viewModel::discoverAndConnect,
                    onRefresh = viewModel::refreshStatus,
                    onReset = viewModel::resetWasteCounters,
                    onRestore = viewModel::restoreBackup,
                    onServiceClean = viewModel::runHeadCleaning,
                    onGenericClean = viewModel::runGenericHeadCleaning,
                    onNozzleCheck = viewModel::printNozzleCheck,
                    onDismissActionSuccess = viewModel::dismissActionSuccess,
                    onPasteGap = viewModel::pasteGapProfile,
                    onTemporaryBackupAnswer = viewModel.writeBackups::answer,
                    onDeleteGap = viewModel::deleteGapProfile,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(detachReceiver, filter)
        }
        // The receiver above was not listening while the app sat in the
        // background, so a printer unplugged then was never reported. Ask now
        // rather than keep offering operations against a session that is gone.
        viewModel.onReturnedToForeground()
    }

    /**
     * A printer plugged in while this activity is already up.
     *
     * [android:launchMode] is `singleTask`, so the system delivers that attach
     * here instead of stacking a second activity on the one that already holds
     * the USB interface. A second instance used to fail with "held by another
     * app" — the other app was this one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        epsonDevice(intent)?.let(viewModel::connect)
    }

    /**
     * The Epson device in a USB attach intent, or null.
     *
     * The manifest filter already limits the system attach to Epson. An explicit
     * intent can still carry any device, so the vendor is checked again before
     * the write path. A rotation keeps the original intent and is not a new attach.
     */
    private fun epsonDevice(source: Intent?): UsbDevice? {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            source?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        return device?.takeIf { it.vendorId == EpsonPrinterCatalog.EPSON_VENDOR_ID }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(detachReceiver) }
        super.onStop()
    }
}
