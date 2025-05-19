import 'dart:developer';

import 'package:flutter/services.dart';

class POSService {
  static const MethodChannel _channel = MethodChannel(
    'com.example.pinelabs/pos',
  );
  static final POSService _instance = POSService._internal();
  bool _isPaymentInProgress = false;
  bool _isDeviceConnected = false;
  String? _connectedDeviceType;
  String? _connectedDeviceId;

  factory POSService() {
    return _instance;
  }

  POSService._internal();

  Future<List<Map<String, dynamic>>> scanForDevices() async {
    try {
      log('Invoking scanForDevices method');
      final List<dynamic> devices = await _channel.invokeMethod(
        'scanForDevices',
      );
      log('Received devices from platform: $devices');

      return devices.map((device) {
        final Map<Object?, Object?> rawDevice = device as Map<Object?, Object?>;
        return rawDevice.map((key, value) => MapEntry(key.toString(), value));
      }).toList();
    } on PlatformException catch (e) {
      log('Platform exception in scanForDevices: ${e.message}');
      throw Exception(e.message ?? 'Failed to scan for devices');
    } catch (e, stackTrace) {
      log('Error in scanForDevices: $e');
      log('Stack trace: $stackTrace');
      throw Exception('Failed to scan for devices: $e');
    }
  }

  Future<void> connectToDevice(dynamic deviceId) async {
    try {
      log('Connecting to device: $deviceId');
      await _channel.invokeMethod('connectToDevice', {'deviceId': deviceId});
      log('Successfully connected to device');
      _isDeviceConnected = true;
      _connectedDeviceId = deviceId.toString();
    } on PlatformException catch (e) {
      log('Platform exception in connectToDevice: ${e.message}');
      _isDeviceConnected = false;
      _connectedDeviceId = null;
      throw Exception(e.message ?? 'Failed to connect to device');
    } catch (e, stackTrace) {
      log('Error in connectToDevice: $e');
      log('Stack trace: $stackTrace');
      _isDeviceConnected = false;
      _connectedDeviceId = null;
      throw Exception('Failed to connect to device: $e');
    }
  }

  Future<void> initiatePayment(
    double amount, {
    String paymentType = 'UPI',
  }) async {
    if (!_isDeviceConnected) {
      log('Device not connected');
      throw Exception('Device not connected. Please connect a device first.');
    }

    if (_isPaymentInProgress) {
      log('Payment already in progress');
      throw Exception('A payment is already in progress');
    }

    try {
      _isPaymentInProgress = true;
      log('Initiating payment for amount: $amount, type: $paymentType');
      await _channel.invokeMethod('initiatePayment', {
        'amount': amount,
        'paymentType': paymentType,
      });
      log('Payment initiated successfully');
    } on PlatformException catch (e) {
      log('Platform exception in initiatePayment: ${e.message}');
      _isPaymentInProgress = false;
      throw Exception(e.message ?? 'Failed to initiate payment');
    } catch (e, stackTrace) {
      log('Error in initiatePayment: $e');
      log('Stack trace: $stackTrace');
      _isPaymentInProgress = false;
      throw Exception('Failed to initiate payment: $e');
    }
  }

  void setDeviceConnectedCallback(Function(Map<String, dynamic>) callback) {
    _channel.setMethodCallHandler((call) async {
      log('Received method call: ${call.method}');
      log('Arguments: ${call.arguments}');

      switch (call.method) {
        case 'onDeviceConnected':
          _isDeviceConnected = true;
          final args = call.arguments as Map<String, dynamic>;
          _connectedDeviceType = args['connectionType'] as String?;
          callback(args);
          break;
        case 'onError':
          log('Error from platform: ${call.arguments}');
          _isPaymentInProgress = false;
          throw Exception(call.arguments);
        case 'onPaymentInitiated':
          log('Payment initiated response: ${call.arguments}');
          // Handle payment response
          final response = call.arguments as Map<String, dynamic>;
          if (response['status'] == 'success') {
            log('Payment command was successfully sent to device');
            // Don't throw an exception on success
            return;
          } else {
            log('Payment command failed: ${response['error']}');
            _isPaymentInProgress = false;
            throw Exception(response['error'] ?? 'Payment command failed');
          }
        default:
          log('Unknown method call: ${call.method}');
          _isPaymentInProgress = false;
          throw Exception('Unknown method call: ${call.method}');
      }
    });
  }

  bool get isDeviceConnected => _isDeviceConnected;
  String? get connectedDeviceType => _connectedDeviceType;
  String? get connectedDeviceId => _connectedDeviceId;
}
