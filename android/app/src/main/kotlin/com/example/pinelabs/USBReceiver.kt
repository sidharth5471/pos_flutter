package com.example.pinelabs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class USBReceiver : BroadcastReceiver() {
    private val TAG = "USBReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == POSBridge.ACTION_USB_PERMISSION) {
            synchronized(this) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let {
                        Log.d(TAG, "USB permission granted for device: ${it.deviceName}")
                        // Notify POSBridge about the permission
                        POSBridge.onUSBPermissionGranted(it)
                    }
                } else {
                    Log.e(TAG, "USB permission denied")
                    POSBridge.onUSBPermissionDenied()
                }
            }
        }
    }
} 