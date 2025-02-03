import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with WidgetsBindingObserver {
  static const methodChannel = MethodChannel('com.deviknitkkr.clean_net/vpn');

  bool isVpnRunning = false;
  Set<String> blockedDomains = {'example.com', 'ads.example.com'};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkVpnStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkVpnStatus();
    }
  }

  Future<void> _checkVpnStatus() async {
    try {
      final bool running = await methodChannel.invokeMethod('isVpnRunning');
      setState(() {
        isVpnRunning = running;
      });
    } catch (e) {
      print("Error checking VPN status: $e");
    }
  }

  Future<void> _toggleVpn() async {
    try {
      if (isVpnRunning) {
        print("Stopping VPN");
        await methodChannel.invokeMethod('stopVpn');
      } else {
        print("Starting VPN");
        await methodChannel.invokeMethod('startVpn');
        await _updateBlockedDomains();
      }
      await _checkVpnStatus();
    } catch (e) {
      print("Error toggling VPN: $e");
    }
  }

  Future<void> _updateBlockedDomains() async {
    try {
      await methodChannel.invokeMethod('updateBlockedDomains', {'domains': blockedDomains.toList()});
      print("Updated blocked domains: $blockedDomains");
    } catch (e) {
      print("Error updating blocked domains: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: Text('DNS VPN App')),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                isVpnRunning ? 'VPN is ON' : 'VPN is OFF',
                style: TextStyle(fontSize: 20),
              ),
              SizedBox(height: 20),
              ToggleButton(
                onPressed: _toggleVpn,
                isOn: isVpnRunning,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class ToggleButton extends StatelessWidget {
  final VoidCallback onPressed;
  final bool isOn;

  const ToggleButton({Key? key, required this.onPressed, required this.isOn}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Switch(
      value: isOn,
      onChanged: (value) => onPressed(),
      activeColor: Colors.green,
      inactiveThumbColor: Colors.red,
    );
  }
}