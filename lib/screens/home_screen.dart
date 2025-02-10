import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/vpn_model.dart';

class HomeScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<VpnModel>(
      builder: (context, vpnModel, child) {
        return Scaffold(
          appBar: AppBar(
            title: Text('VPN'),
            elevation: 0,
          ),
          body: Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  vpnModel.isVpnEnabled ? 'VPN is ON' : 'VPN is OFF',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                ),
                SizedBox(height: 20),
                Transform.scale(
                  scale: 1.5,
                  child: Switch(
                    value: vpnModel.isVpnEnabled,
                    onChanged: (value) => vpnModel.toggleVpn(),
                    activeColor: Colors.green,
                    activeTrackColor: Colors.green.withOpacity(0.5),
                    inactiveThumbColor: Colors.red,
                    inactiveTrackColor: Colors.red.withOpacity(0.5),
                  ),
                ),
                SizedBox(height: 20),
                Text(
                  'Tap to ${vpnModel.isVpnEnabled ? 'disable' : 'enable'} VPN',
                  style: TextStyle(fontSize: 16, color: Colors.grey),
                ),
                SizedBox(height: 20),
                Text(
                  'Current DNS: ${vpnModel.dnsServer?.isNotEmpty == true ? vpnModel.dnsServer : "System Provided"}',
                  style: TextStyle(fontSize: 16),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}