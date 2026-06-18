import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class VpnModel extends ChangeNotifier {
  final SharedPreferences _prefs;

  bool _isVpnEnabled = false;
  List<String> _blockedDomains = [];
  String _dnsServer = '1.1.1.1';
  Map<String, int> _blockedStats = {};
  List<String> _logs = [];
  bool _isRefreshing = false;

  static const String _vpnChannelName = 'com.deviknitkkr.clean_net/vpn';
  final MethodChannel _vpnChannel = const MethodChannel(_vpnChannelName);

  static const String blocklistUrl =
      'https://raw.githubusercontent.com/deviknitkkr/CleanNet/main/blocklist.txt';

  static const List<Map<String, String>> dnsPresets = [
    {'name': 'System DNS', 'ip': ''},
    {'name': 'Cloudflare', 'ip': '1.1.1.1'},
    {'name': 'Google DNS', 'ip': '8.8.8.8'},
    {'name': 'Quad9', 'ip': '9.9.9.9'},
    {'name': 'OpenDNS', 'ip': '208.67.222.222'},
    {'name': 'Comodo Secure', 'ip': '8.26.56.26'},
    {'name': 'AdGuard DNS', 'ip': '94.140.14.14'},
  ];

  VpnModel(this._prefs) {
    _loadPreferences();
  }

  bool get isVpnEnabled => _isVpnEnabled;
  List<String> get blockedDomains => _blockedDomains;
  int get blockedDomainsCount => _blockedDomains.length;
  String get dnsServer => _dnsServer;
  Map<String, int> get blockedStats => _blockedStats;
  int get totalBlocked =>
      _blockedStats.values.fold(0, (sum, v) => sum + v);
  List<String> get logs => _logs;
  bool get isRefreshing => _isRefreshing;

  void _loadPreferences() {
    _isVpnEnabled = _prefs.getBool('vpn_enabled') ?? false;
    _blockedDomains = _prefs.getStringList('blocked_domains') ?? [];
    _dnsServer = _prefs.getString('dns_server') ?? '';
    final raw = _prefs.getString('blocked_stats');
    if (raw != null) {
      final decoded = jsonDecode(raw);
      if (decoded is Map) {
        _blockedStats = Map<String, int>.from(
            decoded.map((k, v) => MapEntry(k.toString(), (v as num).toInt())));
      }
    }
  }

  Future<void> checkVpnState() async {
    try {
      final enabled = await _vpnChannel.invokeMethod<bool>('getVpnState');
      if (enabled != null && enabled != _isVpnEnabled) {
        _isVpnEnabled = enabled;
        _prefs.setBool('vpn_enabled', _isVpnEnabled);
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<void> refreshStats() async {
    try {
      final stats = await _vpnChannel
          .invokeMethod<Map<Object?, Object?>>('getStats');
      if (stats != null) {
        _blockedStats = stats.map(
            (k, v) => MapEntry(k.toString(), (v as num).toInt()));
        _prefs.setString('blocked_stats', jsonEncode(_blockedStats));
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<void> resetStats() async {
    _blockedStats = {};
    _prefs.setString('blocked_stats', jsonEncode(_blockedStats));
    try {
      await _vpnChannel.invokeMethod('resetStats');
    } catch (_) {}
    notifyListeners();
  }

  Future<void> fetchLogs() async {
    try {
      final logs = await _vpnChannel.invokeMethod<List<Object?>>('getLogs');
      if (logs != null) {
        _logs = logs.map((e) => e.toString()).toList();
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<void> clearLogs() async {
    _logs = [];
    try {
      await _vpnChannel.invokeMethod('clearLogs');
    } catch (_) {}
    notifyListeners();
  }

  Future<void> toggleVpn() async {
    try {
      if (_isVpnEnabled) {
        await _vpnChannel.invokeMethod('stopVpn');
        _isVpnEnabled = false;
      } else {
        await _vpnChannel.invokeMethod('startVpn', {
          'dnsServer': _dnsServer,
          'blockedDomains': _blockedDomains,
        });
      _isVpnEnabled = true;
      // Poll stats immediately after start
      await Future.delayed(const Duration(seconds: 1));
      await refreshStats();
    }
    _prefs.setBool('vpn_enabled', _isVpnEnabled);
      notifyListeners();
    } catch (e) {
      debugPrint('Error toggling VPN: $e');
    }
  }

  Future<void> updateDnsServer(String server) async {
    _dnsServer = server;
    _prefs.setString('dns_server', _dnsServer);
    if (_isVpnEnabled) {
      await _restartVpn();
    }
    notifyListeners();
  }

  Future<void> updateBlockedDomains(List<String> domains) async {
    _blockedDomains = domains;
    _prefs.setStringList('blocked_domains', _blockedDomains);
    if (_isVpnEnabled) {
      await _updateBlocklist();
    }
    notifyListeners();
  }

  Future<void> addBlockedDomain(String domain) async {
    final trimmed = domain.trim().toLowerCase();
    if (trimmed.isEmpty || _blockedDomains.contains(trimmed)) return;
    _blockedDomains.add(trimmed);
    _prefs.setStringList('blocked_domains', _blockedDomains);
    if (_isVpnEnabled) {
      await _updateBlocklist();
    }
    notifyListeners();
  }

  void removeBlockedDomain(String domain) {
    _blockedDomains.remove(domain);
    _prefs.setStringList('blocked_domains', _blockedDomains);
    if (_isVpnEnabled) {
      _updateBlocklist();
    }
    notifyListeners();
  }

  Future<bool> refreshBlocklist() async {
    _isRefreshing = true;
    notifyListeners();

    try {
      final response = await http.get(Uri.parse(blocklistUrl));
      if (response.statusCode == 200) {
        final domains = response.body
            .split('\n')
            .map((l) => l.trim())
            .where((l) => l.isNotEmpty && !l.startsWith('#'))
            .map((l) {
          if (l.startsWith('||')) return l.substring(2);
          if (l.startsWith('|')) return l.substring(1);
          return l;
        }).toList();

        _blockedDomains = domains;
        _prefs.setStringList('blocked_domains', _blockedDomains);
        if (_isVpnEnabled) {
          await _updateBlocklist();
        }
        return true;
      }
    } catch (e) {
      debugPrint('Error refreshing blocklist: $e');
    } finally {
      _isRefreshing = false;
      notifyListeners();
    }
    return false;
  }

  Future<void> _updateBlocklist() async {
    try {
      await _vpnChannel.invokeMethod('updateBlocklist', {
        'blockedDomains': _blockedDomains,
      });
    } catch (_) {}
  }

  Future<void> _restartVpn() async {
    try {
      await _vpnChannel.invokeMethod('stopVpn');
      await Future.delayed(const Duration(milliseconds: 800));
      await _vpnChannel.invokeMethod('startVpn', {
        'dnsServer': _dnsServer,
        'blockedDomains': _blockedDomains,
      });
    } catch (e) {
      debugPrint('Error restarting VPN: $e');
    }
  }
}
