package com.example.pinelabs

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class POSBridge(private val context: Context, private val channel: MethodChannel) {
    private val TAG = "POSBridge"
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpointOut: UsbEndpoint? = null
    private var usbEndpointIn: UsbEndpoint? = null
    private var isDeviceInitialized = false
    private val executor = Executors.newFixedThreadPool(10)
    private var lastConnectedDeviceId: Int? = null
    private var lastConnectedDeviceType: String? = null
    private var lastConnectedIpAddress: String? = null
    private var _isDeviceConnected = false
    private val TIMEOUT = 5000
    private val NETWORK_PORT = 9100
    private val NETWORK_PORTS = listOf(9100, 9101, 9102, 9103, 9104, 9105)
    private val NETWORK_TIMEOUT = 300

    init {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private fun createDeviceInfo(device: UsbDevice? = null, ipAddress: String? = null): Map<String, Any>? {
        return when {
            device != null -> mapOf(
                "deviceId" to device.deviceId,
                "deviceName" to (device.deviceName ?: "Unknown Device"),
                "manufacturerName" to (device.manufacturerName ?: "Unknown Manufacturer"),
                "productName" to (device.productName ?: "Unknown Product"),
                "vendorId" to device.vendorId,
                "productId" to device.productId,
                "deviceClass" to device.deviceClass,
                "deviceSubclass" to device.deviceSubclass,
                "deviceProtocol" to device.deviceProtocol,
                "connectionType" to "USB"
            )
            ipAddress != null -> mapOf(
                "deviceId" to ipAddress.hashCode(),
                "deviceName" to "Pine Labs Device",
                "ipAddress" to ipAddress,
                "port" to NETWORK_PORT,
                "connectionType" to "NETWORK"
            )
            else -> null
        }
    }

    fun scanForDevices(): List<Map<String, Any>> {
        // If already connected, return current device
        if (_isDeviceConnected && lastConnectedDeviceId != null) {
            Log.d(TAG, "Device already connected, returning current device")
            return createDeviceInfo(usbDevice, lastConnectedIpAddress)?.let { listOf(it) } ?: emptyList()
        }

        // Scan for new devices without attempting connections
        return try {
            val devices = mutableListOf<Map<String, Any>>()
            devices.addAll(scanUsbDevices())
            devices.addAll(scanNetworkDevices())
            devices
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning devices", e)
            emptyList()
        }
    }

    private fun scanUsbDevices(): List<Map<String, Any>> {
        val deviceList = usbManager?.deviceList ?: return emptyList()
        Log.d(TAG, "Scanning for USB devices... Found ${deviceList.size} devices")

        return deviceList.values.mapNotNull { device ->
            try {
                Log.d(TAG, "Checking device: ${device.deviceName}")
                createDeviceInfo(device)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing USB device", e)
                null
            }
        }
    }

    private fun scanNetworkDevices(): List<Map<String, Any>> {
        val devices = Collections.synchronizedList(ArrayList<Map<String, Any>>())
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()
        
        Log.d(TAG, "Starting network device scan...")

        while (networkInterfaces.hasMoreElements()) {
            val networkInterface = networkInterfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) {
                Log.d(TAG, "Skipping interface: ${networkInterface.displayName} (loopback: ${networkInterface.isLoopback}, up: ${networkInterface.isUp})")
                continue
            }

            Log.d(TAG, "Scanning interface: ${networkInterface.displayName}")
            networkInterface.inetAddresses.asSequence()
                .filter { !it.hostAddress.contains(":") }
                .forEach { address ->
                    val baseAddress = address.hostAddress.substring(0, address.hostAddress.lastIndexOf(".") + 1)
                    Log.d(TAG, "Scanning network range: $baseAddress*")
                    
                    // Scan the entire range (1-254) for the subnet
                    for (i in 1..254) {
                        val targetAddress = "$baseAddress$i"
                        try {
                            Log.d(TAG, "Checking IP: $targetAddress")
                            
                            // Try to connect to common POS ports
                            for (port in NETWORK_PORTS) {
                                try {
                                    val socket = java.net.Socket()
                                    socket.connect(java.net.InetSocketAddress(targetAddress, port), 100)
                                    Log.d(TAG, "Device at $targetAddress responded on port $port")
                                    
                                    // Try to identify if it's a Pine Labs device
                                    try {
                                        val outputStream = socket.getOutputStream()
                                        val inputStream = socket.getInputStream()
                                        
                                        // Send a simple status command
                                        val statusCommand = byteArrayOf(
                                            0x02, // STX
                                            0x53, // 'S'
                                            0x54, // 'T'
                                            0x41, // 'A'
                                            0x54, // 'T'
                                            0x55, // 'U'
                                            0x53, // 'S'
                                            0x1C, // FS
                                            0x03  // ETX
                                        )
                                        
                                        outputStream.write(statusCommand)
                                        outputStream.flush()
                                        
                                        // Read response
                                        val responseBuffer = ByteArray(1024)
                                        val bytesRead = inputStream.read(responseBuffer)
                                        
                                        if (bytesRead > 0) {
                                            Log.d(TAG, "Received response from $targetAddress: ${responseBuffer.take(bytesRead).joinToString(", ") { String.format("0x%02X", it) }}")
                                            createDeviceInfo(ipAddress = targetAddress)?.let { devices.add(it) }
                                            Log.d(TAG, "Added Pine Labs device at $targetAddress")
                                        }
                                    } catch (e: Exception) {
                                        Log.d(TAG, "Error communicating with device at $targetAddress: ${e.message}")
                                    } finally {
                                        socket.close()
                                    }
                                    break // Found a responding port, no need to check others
                                } catch (e: Exception) {
                                    // Skip unreachable ports silently
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Error checking IP $targetAddress: ${e.message}")
                        }
                    }
                }
        }

        Log.d(TAG, "Network scan complete, found ${devices.size} potential devices")
        return devices
    }

    private fun initializeDevice(): Boolean {
        if (isDeviceInitialized) {
            Log.d(TAG, "Device already initialized")
            return true
        }

        try {
            Log.d(TAG, "Initializing device...")
            
            // Send initialization command
            val initCommand = byteArrayOf(
                0x02, // STX
                0x49, // 'I'
                0x4E, // 'N'
                0x49, // 'I'
                0x54, // 'T'
                0x1C, // FS
                0x03  // ETX
            )
            
            Log.d(TAG, "Sending init command: ${initCommand.joinToString(", ") { String.format("0x%02X", it) }}")
            val initResult = usbConnection?.bulkTransfer(usbEndpointOut, initCommand, initCommand.size, TIMEOUT)
            Log.d(TAG, "Init command result: $initResult")
            
            if (initResult != null && initResult > 0) {
                // Wait for response
                Thread.sleep(1000)
                
                val responseBuffer = ByteArray(1024)
                val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                Log.d(TAG, "Init response result: $responseResult")
                
                if (responseResult != null && responseResult > 0) {
                    Log.d(TAG, "Init response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                    isDeviceInitialized = true
                    return true
                }
            }
            
            Log.e(TAG, "Device initialization failed")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error during device initialization", e)
            return false
        }
    }

    fun connectToDevice(deviceId: Int) {
        try {
            // Store the device ID for future reconnection attempts
            lastConnectedDeviceId = deviceId
            
            val deviceList = usbManager?.deviceList ?: emptyMap()
            usbDevice = deviceList.values.find { it.deviceId == deviceId }
            
            if (usbDevice == null) {
                Log.e(TAG, "Device not found with ID: $deviceId")
                channel.invokeMethod("onError", "Device not found. Please ensure the device is connected.")
                return
            }

            Log.d(TAG, "Found device: ${usbDevice?.deviceName}")
            Log.d(TAG, "Device interface count: ${usbDevice?.interfaceCount}")

            // Check if we already have permission
            if (usbManager?.hasPermission(usbDevice) == true) {
                Log.d(TAG, "Already have USB permission")
                try {
                    usbConnection = usbManager?.openDevice(usbDevice)
                    Log.d(TAG, "USB connection opened: ${usbConnection != null}")

                    // Try to find the correct interface and endpoints
                    var foundInterface: UsbInterface? = null
                    var foundEndpointOut: UsbEndpoint? = null
                    var foundEndpointIn: UsbEndpoint? = null

                    for (i in 0 until usbDevice!!.interfaceCount) {
                        val currentInterface = usbDevice!!.getInterface(i)
                        Log.d(TAG, "Checking interface $i: ${currentInterface.name}")
                        
                        // Skip ADB interface
                        if (currentInterface.name?.contains("ADB", ignoreCase = true) == true) {
                            Log.d(TAG, "Skipping ADB interface")
                            continue
                        }
                        
                        // Look for bulk transfer endpoints
                        for (j in 0 until currentInterface.endpointCount) {
                            val endpoint = currentInterface.getEndpoint(j)
                            Log.d(TAG, "Endpoint $j: direction=${endpoint.direction}, type=${endpoint.type}, address=${endpoint.address}")
                            
                            // We want bulk transfer endpoints (type 2)
                            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                                    foundEndpointOut = endpoint
                                    Log.d(TAG, "Found OUT endpoint: address=${endpoint.address}")
                                } else if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                    foundEndpointIn = endpoint
                                    Log.d(TAG, "Found IN endpoint: address=${endpoint.address}")
                                }
                            }
                            
                            if (foundEndpointOut != null && foundEndpointIn != null) {
                                foundInterface = currentInterface
                                Log.d(TAG, "Found suitable interface and endpoints")
                                break
                            }
                        }
                        if (foundInterface != null) break
                    }

                    if (usbConnection != null && foundInterface != null && foundEndpointOut != null && foundEndpointIn != null) {
                        usbInterface = foundInterface
                        usbEndpointOut = foundEndpointOut
                        usbEndpointIn = foundEndpointIn
                        
                        // Release any previously claimed interface
                        usbConnection?.releaseInterface(usbInterface)
                        
                        val claimed = usbConnection?.claimInterface(usbInterface, true)
                        Log.d(TAG, "Interface claimed: $claimed")
                        
                        if (claimed == true) {
                            // Initialize the device
                            if (initializeDevice()) {
                                lastConnectedDeviceType = "USB"
                                channel.invokeMethod("onDeviceConnected", mapOf<String, Any>(
                                    "deviceId" to usbDevice!!.deviceId,
                                    "deviceName" to (usbDevice!!.deviceName ?: "Unknown Device"),
                                    "connectionType" to "USB"
                                ))
                            } else {
                                Log.e(TAG, "Device initialization failed")
                                channel.invokeMethod("onError", "Failed to initialize device. Please try reconnecting.")
                            }
                        } else {
                            Log.e(TAG, "Failed to claim interface")
                            channel.invokeMethod("onError", "Failed to claim USB interface. Please try reconnecting the device.")
                        }
                    } else {
                        Log.e(TAG, "Failed to initialize device connection")
                        channel.invokeMethod("onError", "Failed to initialize device connection. Please try reconnecting the device.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during device initialization", e)
                    channel.invokeMethod("onError", "Error during device initialization: ${e.message}")
                }
                return
            }

            // Create an explicit intent for USB permission
            val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }

            // Set up permission callbacks
            permissionCallback = { device ->
                try {
                    Log.d(TAG, "USB permission granted for device: ${device.deviceName}")
                    usbConnection = usbManager?.openDevice(device)
                    Log.d(TAG, "USB connection opened: ${usbConnection != null}")

                    // Try to find the correct interface and endpoints
                    var foundInterface: UsbInterface? = null
                    var foundEndpointOut: UsbEndpoint? = null
                    var foundEndpointIn: UsbEndpoint? = null

                    for (i in 0 until device.interfaceCount) {
                        val currentInterface = device.getInterface(i)
                        Log.d(TAG, "Checking interface $i: ${currentInterface.name}")
                        
                        for (j in 0 until currentInterface.endpointCount) {
                            val endpoint = currentInterface.getEndpoint(j)
                            Log.d(TAG, "Endpoint $j: direction=${endpoint.direction}, type=${endpoint.type}")
                            
                            if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                                foundEndpointOut = endpoint
                                Log.d(TAG, "Found OUT endpoint")
                            } else if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                foundEndpointIn = endpoint
                                Log.d(TAG, "Found IN endpoint")
                            }
                            
                            if (foundEndpointOut != null && foundEndpointIn != null) {
                                foundInterface = currentInterface
                                break
                            }
                        }
                        if (foundInterface != null) break
                    }

                    if (usbConnection != null && foundInterface != null && foundEndpointOut != null && foundEndpointIn != null) {
                        usbInterface = foundInterface
                        usbEndpointOut = foundEndpointOut
                        usbEndpointIn = foundEndpointIn
                        val claimed = usbConnection?.claimInterface(usbInterface, true)
                        Log.d(TAG, "Interface claimed: $claimed")
                        
                        if (claimed == true) {
                            channel.invokeMethod("onDeviceConnected", mapOf<String, Any>(
                                "deviceId" to device.deviceId,
                                "deviceName" to (device.deviceName ?: "Unknown Device")
                            ))
                        } else {
                            Log.e(TAG, "Failed to claim interface")
                            channel.invokeMethod("onError", "Failed to claim USB interface. Please try reconnecting the device.")
                        }
                    } else {
                        Log.e(TAG, "Failed to initialize device connection")
                        channel.invokeMethod("onError", "Failed to initialize device connection. Please try reconnecting the device.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during device initialization", e)
                    channel.invokeMethod("onError", "Error during device initialization: ${e.message}")
                }
            }

            permissionDeniedCallback = {
                Log.e(TAG, "USB permission denied")
                channel.invokeMethod("onError", "Permission denied for USB device. Please grant USB permission.")
            }

            // Request permission to access the USB device
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                permissionIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            )

            Log.d(TAG, "Requesting USB permission...")
            usbManager?.requestPermission(usbDevice, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            channel.invokeMethod("onError", "Error connecting to device: ${e.message}")
        }
    }

    private fun checkAndReconnectLastDevice(): Boolean {
        return when (lastConnectedDeviceType) {
            "USB" -> checkAndReconnectUsbDevice()
            "NETWORK" -> checkAndReconnectNetworkDevice()
            else -> false
        }
    }

    private fun checkAndReconnectUsbDevice(): Boolean {
        if (lastConnectedDeviceId == null) return false

        return try {
            val device = usbManager?.deviceList?.values?.find { it.deviceId == lastConnectedDeviceId }
                ?: return false

            if (!usbManager?.hasPermission(device)!!) return false

            usbConnection = usbManager?.openDevice(device) ?: return false
            val endpoints = findUsbEndpoints(device) ?: return false

            usbInterface = endpoints.first
            usbEndpointOut = endpoints.second
            usbEndpointIn = endpoints.third

            val claimed = usbConnection?.claimInterface(usbInterface, true) ?: false
            if (!claimed) return false

            initializeDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Error reconnecting to USB device", e)
            false
        }
    }

    private fun findUsbEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val currentInterface = device.getInterface(i)
            if (currentInterface.name?.contains("ADB", ignoreCase = true) == true) continue

            var endpointOut: UsbEndpoint? = null
            var endpointIn: UsbEndpoint? = null

            for (j in 0 until currentInterface.endpointCount) {
                val endpoint = currentInterface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue

                when (endpoint.direction) {
                    UsbConstants.USB_DIR_OUT -> endpointOut = endpoint
                    UsbConstants.USB_DIR_IN -> endpointIn = endpoint
                }

                if (endpointOut != null && endpointIn != null) {
                    return Triple(currentInterface, endpointOut, endpointIn)
                }
            }
        }
        return null
    }

    private fun checkAndReconnectNetworkDevice(): Boolean {
        if (lastConnectedIpAddress == null) return false

        return try {
            val inetAddress = InetAddress.getByName(lastConnectedIpAddress)
            if (!inetAddress.isReachable(1000)) return false

            val socket = java.net.Socket()
            try {
                socket.connect(java.net.InetSocketAddress(inetAddress, NETWORK_PORT), 1000)
                true
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconnecting to network device", e)
            false
        }
    }

    private fun checkDeviceStatus(): Boolean {
        try {
            Log.d(TAG, "Checking device status...")
            
            // Send status check command
            val statusCommand = byteArrayOf(
                0x02, // STX
                0x53, // 'S'
                0x54, // 'T'
                0x41, // 'A'
                0x54, // 'T'
                0x55, // 'U'
                0x53, // 'S'
                0x1C, // FS
                0x03  // ETX
            )
            
            Log.d(TAG, "Sending status command: ${statusCommand.joinToString(", ") { String.format("0x%02X", it) }}")
            val statusResult = usbConnection?.bulkTransfer(usbEndpointOut, statusCommand, statusCommand.size, TIMEOUT)
            Log.d(TAG, "Status command result: $statusResult")
            
            if (statusResult != null && statusResult > 0) {
                // Wait for status response
                Thread.sleep(1000)
                
                val responseBuffer = ByteArray(1024)
                val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                Log.d(TAG, "Status response result: $responseResult")
                
                if (responseResult != null && responseResult > 0) {
                    Log.d(TAG, "Status response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                    return true
                }
            }
            
            Log.e(TAG, "Device status check failed")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device status", e)
            return false
        }
    }

    private fun isDeviceReady(): Boolean {
        try {
            Log.d(TAG, "Checking if device is ready...")
            
            // Send ready check command
            val readyCommand = byteArrayOf(
                0x02, // STX
                0x52, // 'R'
                0x45, // 'E'
                0x41, // 'A'
                0x44, // 'D'
                0x59, // 'Y'
                0x1C, // FS
                0x03  // ETX
            )
            
            var attempts = 0
            val maxAttempts = 3
            
            while (attempts < maxAttempts) {
                Log.d(TAG, "Ready check attempt ${attempts + 1}")
                Log.d(TAG, "Sending ready command: ${readyCommand.joinToString(", ") { String.format("0x%02X", it) }}")
                val readyResult = usbConnection?.bulkTransfer(usbEndpointOut, readyCommand, readyCommand.size, TIMEOUT)
                Log.d(TAG, "Ready command result: $readyResult")
                
                if (readyResult != null && readyResult > 0) {
                    // Wait for ready response
                    Thread.sleep(1000)
                    
                    val responseBuffer = ByteArray(1024)
                    val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                    Log.d(TAG, "Ready response result: $responseResult")
                    
                    if (responseResult != null && responseResult > 0) {
                        Log.d(TAG, "Ready response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                        // Check if response indicates device is ready
                        if (responseResult >= 8 && responseBuffer[0] == 0xCD.toByte() && responseBuffer[1] == 0xCD.toByte()) {
                            Log.d(TAG, "Device is ready")
                            return true
                        } else {
                            Log.d(TAG, "Invalid ready response format")
                        }
                    } else {
                        Log.d(TAG, "No response from ready command")
                    }
                } else {
                    Log.d(TAG, "Failed to send ready command")
                }
                
                attempts++
                if (attempts < maxAttempts) {
                    Log.d(TAG, "Retrying ready check...")
                    Thread.sleep(1000) // Wait before retry
                }
            }
            
            // If we get here, we didn't get a proper response
            Log.e(TAG, "Device not ready after $maxAttempts attempts")
            
            // Try to reinitialize the device
            Log.d(TAG, "Attempting device reinitialization")
            if (initializeDevice()) {
                Log.d(TAG, "Device reinitialized successfully")
                // Try one more ready check after reinitialization
                Thread.sleep(1000)
                val finalReadyResult = usbConnection?.bulkTransfer(usbEndpointOut, readyCommand, readyCommand.size, TIMEOUT)
                if (finalReadyResult != null && finalReadyResult > 0) {
                    Thread.sleep(1000)
                    val finalResponseBuffer = ByteArray(1024)
                    val finalResponseResult = usbConnection?.bulkTransfer(usbEndpointIn, finalResponseBuffer, finalResponseBuffer.size, TIMEOUT)
                    if (finalResponseResult != null && finalResponseResult > 0) {
                        Log.d(TAG, "Final ready response bytes: ${finalResponseBuffer.take(finalResponseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                        if (finalResponseResult >= 8 && finalResponseBuffer[0] == 0xCD.toByte() && finalResponseBuffer[1] == 0xCD.toByte()) {
                            Log.d(TAG, "Device is ready after reinitialization")
                            return true
                        }
                    }
                }
            }
            
            Log.e(TAG, "Device not ready and reinitialization failed")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device readiness", e)
            return false
        }
    }

    private fun cancelTransaction(): Boolean {
        try {
            Log.d(TAG, "Cancelling transaction...")
            
            // Send cancel command
            val cancelCommand = byteArrayOf(
                0x02, // STX
                0x43, // 'C'
                0x41, // 'A'
                0x4E, // 'N'
                0x43, // 'C'
                0x45, // 'E'
                0x4C, // 'L'
                0x1C, // FS
                0x03  // ETX
            )
            
            Log.d(TAG, "Sending cancel command: ${cancelCommand.joinToString(", ") { String.format("0x%02X", it) }}")
            val cancelResult = usbConnection?.bulkTransfer(usbEndpointOut, cancelCommand, cancelCommand.size, TIMEOUT)
            Log.d(TAG, "Cancel command result: $cancelResult")
            
            if (cancelResult != null && cancelResult > 0) {
                // Wait for cancel response
                Thread.sleep(1000)
                
                val responseBuffer = ByteArray(1024)
                val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                Log.d(TAG, "Cancel response result: $responseResult")
                
                if (responseResult != null && responseResult > 0) {
                    Log.d(TAG, "Cancel response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                    return true
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling transaction", e)
            return false
        }
    }

    private fun resetDevice(): Boolean {
        try {
            Log.d(TAG, "Resetting device...")
            
            // Send reset command
            val resetCommand = byteArrayOf(
                0x02, // STX
                0x52, // 'R'
                0x45, // 'E'
                0x53, // 'S'
                0x45, // 'E'
                0x54, // 'T'
                0x1C, // FS
                0x03  // ETX
            )
            
            Log.d(TAG, "Sending reset command: ${resetCommand.joinToString(", ") { String.format("0x%02X", it) }}")
            val resetResult = usbConnection?.bulkTransfer(usbEndpointOut, resetCommand, resetCommand.size, TIMEOUT)
            Log.d(TAG, "Reset command result: $resetResult")
            
            if (resetResult != null && resetResult > 0) {
                // Wait for reset response
                Thread.sleep(1000)
                
                val responseBuffer = ByteArray(1024)
                val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                Log.d(TAG, "Reset response result: $responseResult")
                
                if (responseResult != null && responseResult > 0) {
                    Log.d(TAG, "Reset response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                    return true
                }
            }
            
            // If reset command fails, try to reinitialize
            Log.d(TAG, "Reset command failed, trying reinitialization")
            return initializeDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting device", e)
            return false
        }
    }

    private fun startTransaction(): Boolean {
        try {
            Log.d(TAG, "Starting transaction...")
            
            // First, ensure device is ready
            if (!isDeviceReady()) {
                Log.e(TAG, "Device not ready before starting transaction")
                return false
            }
            
            Thread.sleep(2000) // Longer delay before starting transaction
            
            // Send a basic transaction start command
            val startCommand = byteArrayOf(
                0x02, // STX
                0x54, // 'T'
                0x52, // 'R'
                0x41, // 'A'
                0x4E, // 'N'
                0x53, // 'S'
                0x1C, // FS
                0x03  // ETX
            )
            
            Log.d(TAG, "Sending start command: ${startCommand.joinToString(", ") { String.format("0x%02X", it) }}")
            val startResult = usbConnection?.bulkTransfer(usbEndpointOut, startCommand, startCommand.size, TIMEOUT)
            Log.d(TAG, "Start command result: $startResult")
            
            if (startResult != null && startResult > 0) {
                // Wait for start response
                Thread.sleep(2000)
                
                val responseBuffer = ByteArray(1024)
                val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                Log.d(TAG, "Start response result: $responseResult")
                
                if (responseResult != null && responseResult > 0) {
                    Log.d(TAG, "Start response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                    
                    // Check if response is valid
                    if (responseResult >= 8 && responseBuffer[0] == 0xCD.toByte() && responseBuffer[1] == 0xCD.toByte()) {
                        Log.d(TAG, "Transaction started successfully")
                        return true
                    } else {
                        Log.d(TAG, "Invalid transaction start response format")
                    }
                } else {
                    Log.d(TAG, "No response from transaction start command")
                }
            } else {
                Log.d(TAG, "Failed to send transaction start command")
            }
            
            // If we get here, something went wrong - try to reset the device
            Log.d(TAG, "Transaction start failed, attempting device reset")
            if (resetDevice()) {
                Log.d(TAG, "Device reset successful, trying one more time")
                Thread.sleep(2000)
                
                // Try one more time after reset
                val retryResult = usbConnection?.bulkTransfer(usbEndpointOut, startCommand, startCommand.size, TIMEOUT)
                if (retryResult != null && retryResult > 0) {
                    Thread.sleep(2000)
                    val retryResponseBuffer = ByteArray(1024)
                    val retryResponseResult = usbConnection?.bulkTransfer(usbEndpointIn, retryResponseBuffer, retryResponseBuffer.size, TIMEOUT)
                    
                    if (retryResponseResult != null && retryResponseResult > 0) {
                        Log.d(TAG, "Retry response bytes: ${retryResponseBuffer.take(retryResponseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                        if (retryResponseResult >= 8 && retryResponseBuffer[0] == 0xCD.toByte() && retryResponseBuffer[1] == 0xCD.toByte()) {
                            Log.d(TAG, "Transaction started successfully after reset")
                            return true
                        }
                    }
                }
            }
            
            Log.e(TAG, "Transaction start failed after reset attempt")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting transaction", e)
            return false
        }
    }

    private fun setTransactionType(): Boolean {
        try {
            Log.d(TAG, "Setting transaction type...")
            
            // Send transaction type command
            val typeCommand = byteArrayOf(
                0x02, // STX
                0x54, // 'T'
                0x52, // 'R'
                0x41, // 'A'
                0x4E, // 'N'
                0x53, // 'S'
                0x1C, // FS
                0x54, // 'T'
                0x59, // 'Y'
                0x50, // 'P'
                0x45, // 'E'
                0x1C, // FS
                0x53, // 'S'
                0x41, // 'A'
                0x4C, // 'L'
                0x45, // 'E'
                0x1C, // FS
                0x03  // ETX
            )
            
            Log.d(TAG, "Sending type command: ${typeCommand.joinToString(", ") { String.format("0x%02X", it) }}")
            val typeResult = usbConnection?.bulkTransfer(usbEndpointOut, typeCommand, typeCommand.size, TIMEOUT)
            Log.d(TAG, "Type command result: $typeResult")
            
            if (typeResult != null && typeResult > 0) {
                // Wait for type response
                Thread.sleep(1000)
                
                val responseBuffer = ByteArray(1024)
                val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                Log.d(TAG, "Type response result: $responseResult")
                
                if (responseResult != null && responseResult > 0) {
                    Log.d(TAG, "Type response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                    return true
                }
            }
            
            // If we can't get a response, assume success if command was sent
            if (typeResult != null && typeResult > 0) {
                Log.d(TAG, "No type response, assuming success")
                return true
            }
            
            Log.e(TAG, "Transaction type set failed")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting transaction type", e)
            return false
        }
    }

    private fun setPaymentMode(): Boolean {
        try {
            Log.d(TAG, "Setting payment mode...")
            
            // Send a simpler payment mode command
            val modeCommand = byteArrayOf(
                0x02, // STX
                0x4D, // 'M'
                0x4F, // 'O'
                0x44, // 'D'
                0x45, // 'E'
                0x1C, // FS
                0x55, // 'U'
                0x50, // 'P'
                0x49, // 'I'
                0x1C, // FS
                0x03  // ETX
            )
            
            Log.d(TAG, "Sending mode command: ${modeCommand.joinToString(", ") { String.format("0x%02X", it) }}")
            val modeResult = usbConnection?.bulkTransfer(usbEndpointOut, modeCommand, modeCommand.size, TIMEOUT)
            Log.d(TAG, "Mode command result: $modeResult")
            
            if (modeResult != null && modeResult > 0) {
                // Wait for mode response
                Thread.sleep(2000)
                
                val responseBuffer = ByteArray(1024)
                val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, TIMEOUT)
                Log.d(TAG, "Mode response result: $responseResult")
                
                if (responseResult != null && responseResult > 0) {
                    Log.d(TAG, "Mode response bytes: ${responseBuffer.take(responseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                    
                    // Check if response is valid
                    if (responseResult >= 8 && responseBuffer[0] == 0xCD.toByte() && responseBuffer[1] == 0xCD.toByte()) {
                        Log.d(TAG, "Payment mode set successfully")
                        return true
                    } else {
                        Log.d(TAG, "Invalid payment mode response format")
                    }
                } else {
                    Log.d(TAG, "No response from payment mode command")
                }
            } else {
                Log.d(TAG, "Failed to send payment mode command")
            }
            
            // If we get here, something went wrong - try to reset the device
            Log.d(TAG, "Payment mode set failed, attempting device reset")
            if (resetDevice()) {
                Log.d(TAG, "Device reset successful, trying one more time")
                Thread.sleep(2000)
                
                // Try one more time after reset
                val retryResult = usbConnection?.bulkTransfer(usbEndpointOut, modeCommand, modeCommand.size, TIMEOUT)
                if (retryResult != null && retryResult > 0) {
                    Thread.sleep(2000)
                    val retryResponseBuffer = ByteArray(1024)
                    val retryResponseResult = usbConnection?.bulkTransfer(usbEndpointIn, retryResponseBuffer, retryResponseBuffer.size, TIMEOUT)
                    
                    if (retryResponseResult != null && retryResponseResult > 0) {
                        Log.d(TAG, "Retry response bytes: ${retryResponseBuffer.take(retryResponseResult).joinToString(", ") { String.format("0x%02X", it) }}")
                        if (retryResponseResult >= 8 && retryResponseBuffer[0] == 0xCD.toByte() && retryResponseBuffer[1] == 0xCD.toByte()) {
                            Log.d(TAG, "Payment mode set successfully after reset")
                            return true
                        }
                    }
                }
            }
            
            Log.e(TAG, "Payment mode set failed after reset attempt")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting payment mode", e)
            return false
        }
    }

    private fun formatPaymentCommand(amount: Double, paymentType: String = "UPI"): String {
        // PAX A910 payment command format
        // Format: <STX>PAYMENT<FS>AMOUNT<FS>CURRENCY_CODE<FS>PAYMENT_TYPE<FS>TERMINAL_ID<FS>TRANSACTION_TYPE<FS>MERCHANT_ID<FS>MERCHANT_NAME<FS>TRANSACTION_ID<FS>REFERENCE_ID<FS>OPERATOR_ID<FS>APP_ID<FS>MODE<FS>TYPE<FS>CHANNEL<FS>SCREEN<ETX>
        // STX: Start of text (0x02)
        // FS: Field separator (0x1C)
        // ETX: End of text (0x03)
        
        val stx = 0x02.toChar()
        val fs = 0x1C.toChar()
        val etx = 0x03.toChar()
        
        // Format amount to 2 decimal places and convert to paise
        val amountInPaise = (amount * 100).toInt()
        val formattedAmount = String.format("%d", amountInPaise)
        
        // Currency code for INR
        val currencyCode = "INR"
        
        // Terminal ID (you might need to get this from the device or configuration)
        val terminalId = "TERM001"
        
        // Transaction type (SALE for payment)
        val transactionType = "SALE"
        
        // Merchant details
        val merchantId = "MERCH001"
        val merchantName = "TEST MERCHANT"
        
        // Generate a unique transaction ID
        val transactionId = String.format("TXN%08d", System.currentTimeMillis() % 100000000)
        
        // Generate a reference ID
        val referenceId = String.format("REF%08d", System.currentTimeMillis() % 100000000)
        
        // Operator ID
        val operatorId = "OP001"
        
        // App ID
        val appId = "PAXAPP"
        
        // Mode (INTERACTIVE for user interaction)
        val mode = "INTERACTIVE"
        
        // Type (SALE for payment)
        val type = "SALE"
        
        // Channel (UPI for UPI payments)
        val channel = paymentType
        
        // Screen (DISPLAY for showing payment screen)
        val screen = "DISPLAY"
        
        // Create payment command
        val command = "$stx${"PAYMENT"}${fs}${formattedAmount}${fs}${currencyCode}${fs}${paymentType}${fs}${terminalId}${fs}${transactionType}${fs}${merchantId}${fs}${merchantName}${fs}${transactionId}${fs}${referenceId}${fs}${operatorId}${fs}${appId}${fs}${mode}${fs}${type}${fs}${channel}${fs}${screen}${etx}"
        Log.d(TAG, "Formatted command: $command")
        return command
    }

    private fun waitForResponse(timeout: Int = TIMEOUT): ByteArray? {
        try {
            val responseBuffer = ByteArray(1024)
            Log.d(TAG, "Waiting for response with timeout: $timeout")
            val responseResult = usbConnection?.bulkTransfer(usbEndpointIn, responseBuffer, responseBuffer.size, timeout)
            Log.d(TAG, "Response read result: $responseResult")
            
            if (responseResult != null && responseResult > 0) {
                // If we got a response, check if it's valid
                if (responseResult >= 8) {
                    val header1 = responseBuffer[0]
                    val header2 = responseBuffer[1]
                    val status = responseBuffer[2]
                    val responseCode = responseBuffer[6]
                    
                    // Check if this is a valid response
                    if (header1 == 0xCD.toByte() && header2 == 0xCD.toByte() && responseCode == 0x52.toByte()) {
                        Log.d(TAG, "Received valid response")
                        return responseBuffer.copyOf(responseResult)
                    } else {
                        Log.d(TAG, "Received invalid response: header1=0x${String.format("%02X", header1)}, " +
                            "header2=0x${String.format("%02X", header2)}, " +
                            "status=0x${String.format("%02X", status)}, " +
                            "responseCode=0x${String.format("%02X", responseCode)}")
                    }
                } else {
                    Log.d(TAG, "Response too short: $responseResult bytes")
                }
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for response", e)
            return null
        }
    }

    private fun sendCommand(command: ByteArray, waitForResponse: Boolean = true): Boolean {
        try {
            Log.d(TAG, "Sending command: ${command.joinToString(", ") { String.format("0x%02X", it) }}")
            val result = usbConnection?.bulkTransfer(usbEndpointOut, command, command.size, TIMEOUT)
            Log.d(TAG, "Command result: $result")
            
            if (result != null && result > 0) {
                if (waitForResponse) {
                    val response = waitForResponse()
                    if (response != null) {
                        Log.d(TAG, "Response bytes: ${response.joinToString(", ") { String.format("0x%02X", it) }}")
                        return true
                    }
                    return false
                }
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command", e)
            return false
        }
    }

    fun initiatePayment(amount: Double, paymentType: String = "UPI") {
        if (usbConnection == null || usbEndpointOut == null || usbEndpointIn == null) {
            Log.e(TAG, "USB connection or endpoints are null")
            channel.invokeMethod("onError", "Device not connected. Please ensure the device is properly connected.")
            return
        }

        if (!isDeviceInitialized) {
            Log.e(TAG, "Device not initialized")
            channel.invokeMethod("onError", "Device not initialized. Please reconnect the device.")
            return
        }

        try {
            // Log endpoint details
            Log.d(TAG, "Using OUT endpoint: address=${usbEndpointOut?.address}, maxPacketSize=${usbEndpointOut?.maxPacketSize}")
            Log.d(TAG, "Using IN endpoint: address=${usbEndpointIn?.address}, maxPacketSize=${usbEndpointIn?.maxPacketSize}")
            
            // Check if device is ready
            if (!isDeviceReady()) {
                Log.e(TAG, "Device not ready")
                channel.invokeMethod("onError", "Device not ready. Please try again.")
                return
            }
            
            Thread.sleep(2000) // Longer delay after ready check
            
            // Start transaction
            if (!startTransaction()) {
                Log.e(TAG, "Failed to start transaction")
                channel.invokeMethod("onError", "Failed to start transaction. Please try again.")
                return
            }
            
            Thread.sleep(1000) // Wait after transaction start
            
            // Set transaction type
            if (!setTransactionType()) {
                Log.e(TAG, "Failed to set transaction type")
                cancelTransaction() // Try to cancel the transaction
                channel.invokeMethod("onError", "Failed to set transaction type. Please try again.")
                return
            }
            
            Thread.sleep(1000) // Wait after transaction type
            
            // Set payment mode
            if (!setPaymentMode()) {
                Log.e(TAG, "Failed to set payment mode")
                cancelTransaction() // Try to cancel the transaction
                channel.invokeMethod("onError", "Failed to set payment mode. Please try again.")
                return
            }
            
            // Format and send payment command
            val paymentCommand = formatPaymentCommand(amount, paymentType)
            val bytes = paymentCommand.toByteArray()
            
            Log.d(TAG, "Sending payment command: ${bytes.joinToString(", ") { String.format("0x%02X", it) }}")
            val paymentResult = usbConnection?.bulkTransfer(usbEndpointOut, bytes, bytes.size, TIMEOUT)
            Log.d(TAG, "Payment command result: $paymentResult")
            
            if (paymentResult == null || paymentResult <= 0) {
                Log.e(TAG, "Failed to send payment command")
                cancelTransaction() // Try to cancel the transaction
                channel.invokeMethod("onError", "Failed to send payment command. Please try again.")
                return
            }
            
            // Wait for payment response with longer timeout
            val response = waitForResponse(TIMEOUT * 2)
            if (response != null && response.size >= 8) {
                val responseCode = response[6]
                if (responseCode == 0x52.toByte()) {
                    channel.invokeMethod("onPaymentInitiated", mapOf<String, Any>(
                        "amount" to amount,
                        "paymentType" to paymentType,
                        "status" to "success",
                        "response" to "Payment request sent successfully"
                    ))
                } else {
                    cancelTransaction() // Try to cancel the transaction
                    channel.invokeMethod("onPaymentInitiated", mapOf<String, Any>(
                        "amount" to amount,
                        "paymentType" to paymentType,
                        "status" to "error",
                        "error" to "Device returned error code: 0x${String.format("%02X", responseCode)}"
                    ))
                }
            } else {
                cancelTransaction() // Try to cancel the transaction
                channel.invokeMethod("onPaymentInitiated", mapOf<String, Any>(
                    "amount" to amount,
                    "paymentType" to paymentType,
                    "status" to "error",
                    "error" to "No valid response from device"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Payment initiation failed", e)
            cancelTransaction() // Try to cancel the transaction
            channel.invokeMethod("onError", "Payment initiation failed: ${e.message}")
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            try {
                                Log.d(TAG, "USB permission granted for device: ${it.deviceName}")
                                usbConnection = usbManager?.openDevice(it)
                                Log.d(TAG, "USB connection opened: ${usbConnection != null}")

                                // Try to find the correct interface and endpoints
                                var foundInterface: UsbInterface? = null
                                var foundEndpointOut: UsbEndpoint? = null
                                var foundEndpointIn: UsbEndpoint? = null

                                for (i in 0 until it.interfaceCount) {
                                    val currentInterface = it.getInterface(i)
                                    Log.d(TAG, "Checking interface $i: ${currentInterface.name}")
                                    
                                    for (j in 0 until currentInterface.endpointCount) {
                                        val endpoint = currentInterface.getEndpoint(j)
                                        Log.d(TAG, "Endpoint $j: direction=${endpoint.direction}, type=${endpoint.type}")
                                        
                                        if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                                            foundEndpointOut = endpoint
                                            Log.d(TAG, "Found OUT endpoint")
                                        } else if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                            foundEndpointIn = endpoint
                                            Log.d(TAG, "Found IN endpoint")
                                        }
                                        
                                        if (foundEndpointOut != null && foundEndpointIn != null) {
                                            foundInterface = currentInterface
                                            break
                                        }
                                    }
                                    if (foundInterface != null) break
                                }

                                if (usbConnection != null && foundInterface != null && foundEndpointOut != null && foundEndpointIn != null) {
                                    usbInterface = foundInterface
                                    usbEndpointOut = foundEndpointOut
                                    usbEndpointIn = foundEndpointIn
                                    val claimed = usbConnection?.claimInterface(usbInterface, true)
                                    Log.d(TAG, "Interface claimed: $claimed")
                                    
                                    if (claimed == true) {
                                        channel.invokeMethod("onDeviceConnected", mapOf<String, Any>(
                                            "deviceId" to it.deviceId,
                                            "deviceName" to (it.deviceName ?: "Unknown Device")
                                        ))
                                    } else {
                                        Log.e(TAG, "Failed to claim interface")
                                        channel.invokeMethod("onError", "Failed to claim USB interface. Please try reconnecting the device.")
                                    }
                                } else {
                                    Log.e(TAG, "Failed to initialize device connection")
                                    channel.invokeMethod("onError", "Failed to initialize device connection. Please try reconnecting the device.")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error during device initialization", e)
                                channel.invokeMethod("onError", "Error during device initialization: ${e.message}")
                            }
                        }
                    } else {
                        Log.e(TAG, "USB permission denied")
                        channel.invokeMethod("onError", "Permission denied for USB device. Please grant USB permission.")
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.pinelabs.USB_PERMISSION"

        private var permissionCallback: ((UsbDevice) -> Unit)? = null
        private var permissionDeniedCallback: (() -> Unit)? = null

        fun onUSBPermissionGranted(device: UsbDevice) {
            permissionCallback?.invoke(device)
        }

        fun onUSBPermissionDenied() {
            permissionDeniedCallback?.invoke()
        }
    }
} 