package com.deviknitkkr.clean_net;

import android.content.Intent;
import android.net.VpnService;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity {
    private static final String CHANNEL = "com.deviknitkkr.clean_net/vpn";
    private static final int VPN_REQUEST_CODE = 1;

    private String pendingDnsServer = null;
    private List<String> pendingBlockedDomains = null;

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL)
                .setMethodCallHandler((call, result) -> {
                    switch (call.method) {
                        case "startVpn":
                            String dnsServer = call.argument("dnsServer");
                            List<String> blockedDomains = call.argument("blockedDomains");
                            startVpn(dnsServer, blockedDomains);
                            result.success(null);
                            break;
                        case "stopVpn":
                            stopVpn();
                            result.success(null);
                            break;
                        default:
                            result.notImplemented();
                    }
                });
    }

    private void startVpn(String dnsServer, List<String> blockedDomains) {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            pendingDnsServer = dnsServer;
            pendingBlockedDomains = blockedDomains;
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onVpnPermissionGranted(dnsServer, blockedDomains);
        }
    }

    private void stopVpn() {
        Intent intent = new Intent(this, DnsVpnService.class);
        intent.setAction(DnsVpnService.ACTION_STOP);
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            onVpnPermissionGranted(pendingDnsServer, pendingBlockedDomains);
        }
    }

    private void onVpnPermissionGranted(String dnsServer, List<String> blockedDomains) {
        Intent intent = new Intent(this, DnsVpnService.class);
        intent.setAction(DnsVpnService.ACTION_START);
        intent.putExtra(DnsVpnService.EXTRA_DNS_SERVER, dnsServer);
        intent.putStringArrayListExtra(DnsVpnService.EXTRA_BLOCKED_DOMAINS, new ArrayList<>(blockedDomains));
        startService(intent);
    }
}