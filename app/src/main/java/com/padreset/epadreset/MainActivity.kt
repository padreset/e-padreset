package com.padreset.epadreset

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padreset.epadreset.ui.EPadResetApp
import com.padreset.epadreset.ui.MainViewModel
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

        val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
        }

        // Only on a fresh launch: the intent survives a rotation, and connecting
        // to the same device again would restart the session every time the
        // screen turns.
        if (savedInstanceState == null && usbDevice != null) {
            viewModel.connect(usbDevice)
        } else {
            viewModel.autoScanOnce()
        }

        setContent {
            EPadResetTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                EPadResetApp(
                    uiState = uiState,
                    onConnect = viewModel::discoverAndConnect,
                    onRefresh = viewModel::refreshStatus,
                    onReset = viewModel::resetWasteCounters,
                    onServiceClean = viewModel::runHeadCleaning,
                    onGenericClean = viewModel::runGenericHeadCleaning,
                    onNozzleCheck = viewModel::printNozzleCheck,
                    onDismissActionSuccess = viewModel::dismissActionSuccess,
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
    }

    override fun onStop() {
        runCatching { unregisterReceiver(detachReceiver) }
        super.onStop()
    }
}
