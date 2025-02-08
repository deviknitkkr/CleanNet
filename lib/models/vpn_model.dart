import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/services.dart';

class VpnModel extends ChangeNotifier {
  final SharedPreferences _prefs;
  
  bool _isVpnEnabled = false;
  List<String> _blockedDomains = [];
  String _dnsServer = '8.8.8.8';
  Map<String, int> _blockedStats = {};
  static const String VPN_CHANNEL = 'com.deviknitkkr.clean_net/vpn';
  final MethodChannel _vpnChannel = const MethodChannel(VPN_CHANNEL);

  VpnModel(this._prefs) {
    _loadPreferences();
  }

  bool get isVpnEnabled => _isVpnEnabled;
  List<String> get blockedDomains => _blockedDomains;
  String get dnsServer => _dnsServer;
  Map<String, int> get blockedStats => _blockedStats;

  void _loadPreferences() {
    _isVpnEnabled = _prefs.getBool('vpn_enabled') ?? false;
    _blockedDomains = _prefs.getStringList('blocked_domains') ?? [];
    _dnsServer = _prefs.getString('dns_server') ?? '8.8.8.8';
    _blockedStats = Map<String, int>.from(
      _prefs.getString('blocked_stats') != null
          ? Map<String, dynamic>.from(
              _prefs.getString('blocked_stats') as Map)
          : {}
    );
    notifyListeners();
  }

  void toggleVpn() {
    try {
      if (_isVpnEnabled) {
         _vpnChannel.invokeMethod('stopVpn');
      } else {
         _vpnChannel.invokeMethod('startVpn', {'dnsServer': _dnsServer, 'blockedDomains': _blockedDomains});
      }
      _isVpnEnabled = !_isVpnEnabled;
      _prefs.setBool('vpn_enabled', _isVpnEnabled);
      notifyListeners();
    } catch (e) {
      print('Error toggling VPN: $e');
    }
  }

  void updateBlockedDomains(List<String> domains) {
    _blockedDomains = domains;
    _prefs.setStringList('blocked_domains', _blockedDomains);
    notifyListeners();
  }

  void updateDnsServer(String server) {

    _dnsServer = server;
    _prefs.setString('dns_server', _dnsServer);
    if (_isVpnEnabled) {
      // Restart VPN with new DNS server
       _vpnChannel.invokeMethod('stopVpn');
       _vpnChannel.invokeMethod('startVpn', {'dnsServer': _dnsServer});
    }
    notifyListeners();
  }

  void updateBlockedStats(String domain) {
    if (_blockedStats.containsKey(domain)) {
      _blockedStats[domain] = (_blockedStats[domain] ?? 0) + 1;
    } else {
      _blockedStats[domain] = 1;
    }
    _prefs.setString('blocked_stats', _blockedStats.toString());
    notifyListeners();
  }
}




