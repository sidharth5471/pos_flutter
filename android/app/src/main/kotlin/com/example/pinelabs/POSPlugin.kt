package com.example.pinelabs

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel

class POSPlugin: FlutterPlugin {
    private lateinit var channel: MethodChannel
    private lateinit var posBridge: POSBridge

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "com.example.pinelabs/pos")
        posBridge = POSBridge(binding.applicationContext, channel)

        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "scanForDevices" -> {
                    val devices = posBridge.scanForDevices()
                    result.success(devices)
                }
                "connectToDevice" -> {
                    val deviceId = call.argument<Int>("deviceId")
                    if (deviceId != null) {
                        posBridge.connectToDevice(deviceId)
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "Device ID is required", null)
                    }
                }
                "initiatePayment" -> {
                    val amount = call.argument<Double>("amount")
                    val paymentType = call.argument<String>("paymentType") ?: "UPI"
                    if (amount != null) {
                        posBridge.initiatePayment(amount, paymentType)
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "Amount is required", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
} 