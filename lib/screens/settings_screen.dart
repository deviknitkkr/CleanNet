import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/vpn_model.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<VpnModel>(
      builder: (context, model, child) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('Settings'),
            centerTitle: true,
          ),
          body: ListView(
            children: [
              _sectionHeader('DNS Server'),
              ListTile(
                leading: const Icon(Icons.dns),
                title: Text(model.dnsServer),
                subtitle: Text(_dnsLabel(model.dnsServer)),
                trailing: const Icon(Icons.arrow_forward_ios, size: 16),
                onTap: () => _showDnsPicker(context, model),
              ),

              const Divider(),
              _sectionHeader('Blocklist'),
              ListTile(
                leading: const Icon(Icons.download),
                title: const Text('Refresh from GitHub'),
                subtitle: Text('${model.blockedDomainsCount} domains loaded'),
                trailing: model.isRefreshing
                    ? const SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.refresh),
                onTap: model.isRefreshing
                    ? null
                    : () => _refreshBlocklist(context, model),
              ),
              ListTile(
                leading: const Icon(Icons.add_circle_outline),
                title: const Text('Add Domain'),
                subtitle: const Text('Manually add a domain to block'),
                onTap: () => _showAddDomainDialog(context, model),
              ),
              ListTile(
                leading: const Icon(Icons.list),
                title: const Text('Edit Blocked Domains'),
                subtitle: Text('${model.blockedDomainsCount} domains'),
                onTap: () => _showDomainList(context, model),
              ),

              const Divider(),
              _sectionHeader('Statistics'),
              ListTile(
                leading: const Icon(Icons.delete_sweep),
                title: const Text('Reset Statistics'),
                onTap: () => model.resetStats(),
              ),
            ],
          ),
        );
      },
    );
  }

  String _dnsLabel(String ip) {
    final match = VpnModel.dnsPresets.cast<Map<String, String>?>().firstWhere(
          (p) => p!['ip'] == ip,
          orElse: () => null,
        );
    return match != null ? match['name']! : 'Custom';
  }

  Widget _sectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w600,
          color: Colors.teal,
          letterSpacing: 0.5,
        ),
      ),
    );
  }

  void _showDnsPicker(BuildContext context, VpnModel model) {
    showDialog(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('DNS Server'),
        children: [
          ...VpnModel.dnsPresets.map((p) => RadioListTile<String>(
                title: Text(p['name']!),
                subtitle: Text(p['ip']!),
                value: p['ip']!,
                groupValue: model.dnsServer,
                onChanged: (v) {
                  model.updateDnsServer(v!);
                  Navigator.pop(ctx);
                },
              )),
          ListTile(
            leading: const Icon(Icons.edit),
            title: const Text('Custom'),
            subtitle: Text(model.dnsServer),
            onTap: () {
              Navigator.pop(ctx);
              _showDnsDialog(context, model);
            },
          ),
        ],
      ),
    );
  }

  void _showDnsDialog(BuildContext context, VpnModel model) {
    final controller = TextEditingController(text: model.dnsServer);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Custom DNS Server'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            labelText: 'DNS Server IP',
            hintText: 'e.g., 1.1.1.1',
          ),
          keyboardType: TextInputType.url,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              model.updateDnsServer(controller.text.trim());
              Navigator.pop(ctx);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }

  Future<void> _refreshBlocklist(BuildContext context, VpnModel model) async {
    final ok = await model.refreshBlocklist();
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(ok
              ? 'Blocklist updated: ${model.blockedDomainsCount} domains'
              : 'Failed to refresh blocklist'),
          backgroundColor: ok ? Colors.green : Colors.red,
        ),
      );
    }
  }

  void _showAddDomainDialog(BuildContext context, VpnModel model) {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add Domain'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            labelText: 'Domain',
            hintText: 'e.g., ads.example.com',
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              model.addBlockedDomain(controller.text.trim());
              Navigator.pop(ctx);
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('Domain added'),
                  duration: Duration(seconds: 1),
                ),
              );
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }

  void _showDomainList(BuildContext context, VpnModel model) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Blocked Domains'),
        content: SizedBox(
          width: double.maxFinite,
          child: model.blockedDomains.isEmpty
              ? const Text('No domains loaded')
              : ListView.builder(
                  shrinkWrap: true,
                  itemCount: model.blockedDomains.length,
                  itemBuilder: (_, i) => ListTile(
                    dense: true,
                    title: Text(
                      model.blockedDomains[i],
                      style: const TextStyle(fontSize: 13),
                    ),
                    trailing: IconButton(
                      icon: const Icon(Icons.remove_circle_outline,
                          size: 20, color: Colors.red),
                      onPressed: () {
                        model.removeBlockedDomain(model.blockedDomains[i]);
                      },
                    ),
                  ),
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Done'),
          ),
        ],
      ),
    );
  }
}
