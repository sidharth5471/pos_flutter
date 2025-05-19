import 'dart:developer';

import 'package:flutter/material.dart';
import 'services/pos_service.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PineLabs POS Demo',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const PaymentPage(),
    );
  }
}

class PaymentPage extends StatefulWidget {
  const PaymentPage({super.key});

  @override
  State<PaymentPage> createState() => _PaymentPageState();
}

class _PaymentPageState extends State<PaymentPage> {
  final _formKey = GlobalKey<FormState>();
  final _amountController = TextEditingController();
  String _selectedPaymentType = 'UPI';
  final _posService = POSService();
  bool _isLoading = false;
  String? _errorMessage;

  final List<String> _paymentTypes = ['UPI', 'Card'];

  @override
  void initState() {
    super.initState();
    _posService.setDeviceConnectedCallback(_handleDeviceConnected);
  }

  void _handleDeviceConnected(Map<String, dynamic> deviceInfo) {
    setState(() {
      _isLoading = false;
      _errorMessage = null;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Connected to device: ${deviceInfo['deviceName']}'),
        backgroundColor: Colors.green,
      ),
    );
  }

  void _showError(String message) {
    setState(() {
      _isLoading = false;
      _errorMessage = message;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Colors.red,
        duration: const Duration(seconds: 5),
        action: SnackBarAction(
          label: 'Dismiss',
          textColor: Colors.white,
          onPressed: () {
            ScaffoldMessenger.of(context).hideCurrentSnackBar();
          },
        ),
      ),
    );
  }

  Future<void> _handlePayment() async {
    log('handlePayment');
    if (!_formKey.currentState!.validate()) return;

    if (_isLoading) {
      log('Payment already in progress');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      // First scan for devices
      log('Scanning for devices...');
      final devices = await _posService.scanForDevices();
      log('Found ${devices.length} devices');

      if (devices.isEmpty) {
        _showError(
          'No POS devices found. Please connect a device and try again.',
        );
        return;
      }

      // Connect to the first available device
      log('Connecting to device: ${devices[0]}');
      await _posService.connectToDevice(devices[0]['deviceId']);
      log('Connected to device');

      // Initiate payment
      final amount = double.parse(_amountController.text);
      log('Initiating payment for amount: $amount');
      await _posService.initiatePayment(
        amount,
        paymentType: _selectedPaymentType,
      );
      log('Payment initiated successfully');
    } catch (e, stackTrace) {
      log('Error in _handlePayment: $e');
      log('Stack trace: $stackTrace');
      _showError(e.toString());
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PineLabs Payment'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _amountController,
                decoration: const InputDecoration(
                  labelText: 'Amount',
                  prefixText: '₹ ',
                  border: OutlineInputBorder(),
                ),
                keyboardType: TextInputType.number,
                validator: (value) {
                  if (value == null || value.isEmpty) {
                    return 'Please enter an amount';
                  }
                  if (double.tryParse(value) == null) {
                    return 'Please enter a valid amount';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                value: _selectedPaymentType,
                decoration: const InputDecoration(
                  labelText: 'Payment Type',
                  border: OutlineInputBorder(),
                ),
                items:
                    _paymentTypes.map((String type) {
                      return DropdownMenuItem<String>(
                        value: type,
                        child: Text(type),
                      );
                    }).toList(),
                onChanged: (String? newValue) {
                  if (newValue != null) {
                    setState(() {
                      _selectedPaymentType = newValue;
                    });
                  }
                },
              ),
              const SizedBox(height: 24),
              if (_errorMessage != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(
                    _errorMessage!,
                    style: const TextStyle(color: Colors.red),
                  ),
                ),
              ElevatedButton(
                onPressed: _isLoading ? null : _handlePayment,
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                ),
                child:
                    _isLoading
                        ? const CircularProgressIndicator()
                        : const Text('Pay Now', style: TextStyle(fontSize: 18)),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    _amountController.dispose();
    super.dispose();
  }
}
