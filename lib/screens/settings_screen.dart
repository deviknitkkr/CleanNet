import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/vpn_model.dart';

class SettingsScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<VpnModel>(
      builder: (context, vpnModel, child) {
        return Scaffold(
          appBar: AppBar(
            title: Text('Settings'),
            elevation: 0,
          ),
          body: ListView(
            children: [
              ListTile(
                title: Text('DNS Server'),
                subtitle: Text(vpnModel.dnsServer),
                trailing: Icon(Icons.edit),
                onTap: () => _showDnsServerDialog(context, vpnModel),
              ),
              ListTile(
                title: Text('Blocked Domains'),
                subtitle: Text('${vpnModel.blockedDomains.length} domains'),
                trailing: Icon(Icons.edit),
                onTap: () => _showBlockedDomainsDialog(context, vpnModel),
              ),
            ],
          ),
        );
      },
    );
  }

  void _showDnsServerDialog(BuildContext context, VpnModel vpnModel) {
    final controller = TextEditingController(text: vpnModel.dnsServer);
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Update DNS Server'),
        content: TextField(
          controller: controller,
          decoration: InputDecoration(
            labelText: 'DNS Server',
            hintText: 'Enter DNS server IP (e.g., 8.8.8.8)',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              vpnModel.updateDnsServer(controller.text);
              Navigator.pop(context);
            },
            child: Text('Save'),
          ),
        ],
      ),
    );
  }

  void _showBlockedDomainsDialog(BuildContext context, VpnModel vpnModel) {
    final controller = TextEditingController(
      text: vpnModel.blockedDomains.join('\n'),
    );
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Update Blocked Domains'),
        content: TextField(
          controller: controller,
          maxLines: 10,
          decoration: InputDecoration(
            labelText: 'Blocked Domains',
            hintText: 'Enter domains (one per line)',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              final domains = controller.text
                  .split('\n')
                  .where((d) => d.isNotEmpty)
                  .toList();
              vpnModel.updateBlockedDomains(domains);
              Navigator.pop(context);
            },
            child: Text('Save'),
          ),
        ],
      ),
    );
  }
}