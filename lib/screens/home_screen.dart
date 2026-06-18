import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/vpn_model.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<VpnModel>(
      builder: (context, model, child) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('CleanNet'),
            centerTitle: true,
          ),
          body: Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  width: 160,
                  height: 160,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: model.isVpnEnabled
                        ? Colors.green.withOpacity(0.15)
                        : Colors.red.withOpacity(0.1),
                    border: Border.all(
                      color: model.isVpnEnabled ? Colors.green : Colors.red,
                      width: 4,
                    ),
                  ),
                  child: Icon(
                    model.isVpnEnabled ? Icons.shield : Icons.shield_outlined,
                    size: 72,
                    color: model.isVpnEnabled ? Colors.green : Colors.red,
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  model.isVpnEnabled ? 'Protected' : 'Not Protected',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: model.isVpnEnabled ? Colors.green : Colors.red,
                      ),
                ),
                const SizedBox(height: 8),
                Text(
                  model.isVpnEnabled
                      ? 'Ad blocking is active'
                      : 'Tap to enable ad blocking',
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        color: Colors.grey,
                      ),
                ),
                const SizedBox(height: 32),
                FilledButton.tonalIcon(
                  onPressed: () => model.toggleVpn(),
                  icon: Icon(model.isVpnEnabled ? Icons.power_settings_new : Icons.play_arrow),
                  label: Text(model.isVpnEnabled ? 'Disable VPN' : 'Enable VPN'),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                    textStyle: const TextStyle(fontSize: 16),
                  ),
                ),
                const SizedBox(height: 24),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.dns, size: 18, color: Colors.grey),
                        const SizedBox(width: 8),
                        Text(
                          'DNS: ${model.dnsServer.isEmpty ? "System" : model.dnsServer}',
                          style: const TextStyle(color: Colors.grey),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
