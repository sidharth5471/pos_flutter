import 'package:flutter/material.dart';

import '../services/pos_service.dart';

class DeviceListScreen extends StatefulWidget {
  const DeviceListScreen({super.key});

  @override
  State<DeviceListScreen> createState() => _DeviceListScreenState();
}

class _DeviceListScreenState extends State<DeviceListScreen> {
  final POSService _posService = POSService();
  List<Map<String, dynamic>> _devices = [];
  bool _isLoading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _scanForDevices();
  }

  Future<void> _scanForDevices() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final devices = await _posService.scanForDevices();
      setState(() {
        _devices = devices;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString();
        _isLoading = false;
      });
    }
  }

  Future<void> _connectToDevice(Map<String, dynamic> device) async {
    try {
      await _posService.connectToDevice(device['deviceId']);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Successfully connected to device')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to connect: ${e.toString()}')),
        );
      }
    }
  }

  List<Map<String, dynamic>> _getDevicesByType(String type) {
    return _devices
        .where((device) => device['connectionType'] == type)
        .toList();
  }

  Widget _buildDeviceList(String type, List<Map<String, dynamic>> devices) {
    if (devices.isEmpty) {
      return Center(child: Text('No $type devices found'));
    }

    return ListView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: devices.length,
      itemBuilder: (context, index) {
        final device = devices[index];
        return Card(
          margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: ListTile(
            leading: Icon(
              type == 'USB' ? Icons.usb : Icons.wifi,
              color: Theme.of(context).primaryColor,
            ),
            title: Text(device['deviceName'] ?? 'Unknown Device'),
            subtitle: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (type == 'USB') ...[
                  Text('Vendor ID: ${device['vendorId']}'),
                  Text('Product ID: ${device['productId']}'),
                ] else ...[
                  Text('IP: ${device['ipAddress']}'),
                  Text('Port: ${device['port']}'),
                ],
              ],
            ),
            trailing: ElevatedButton(
              onPressed: () => _connectToDevice(device),
              child: const Text('Connect'),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Available Devices'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _isLoading ? null : _scanForDevices,
          ),
        ],
      ),
      body:
          _isLoading
              ? const Center(child: CircularProgressIndicator())
              : _error != null
              ? Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      'Error: $_error',
                      style: const TextStyle(color: Colors.red),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 16),
                    ElevatedButton(
                      onPressed: _scanForDevices,
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              )
              : RefreshIndicator(
                onRefresh: _scanForDevices,
                child: SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'USB Devices',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 8),
                        _buildDeviceList('USB', _getDevicesByType('USB')),
                        const SizedBox(height: 24),
                        const Text(
                          'Network Devices',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 8),
                        _buildDeviceList(
                          'NETWORK',
                          _getDevicesByType('NETWORK'),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
    );
  }
}
