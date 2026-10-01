package com.padreset.epadreset

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
        }

        usbDevice?.let { device ->
            viewModel.connect(device)
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
}
