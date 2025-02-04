package com.deviknitkkr.clean_net;

import android.content.Intent;
import android.net.VpnService;

import androidx.annotation.NonNull;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity {
    private static final String CHANNEL = "com.deviknitkkr.clean_net/vpn";
    private static final int VPN_REQUEST_CODE = 1;

    private String pendingDnsServer = null;

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL)
                .setMethodCallHandler((call, result) -> {
                    switch (call.method) {
                        case "startVpn":
                            String dnsServer = call.argument("dnsServer");
                            startVpn(dnsServer);
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

    private void startVpn(String dnsServer) {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            pendingDnsServer = dnsServer;
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onVpnPermissionGranted(dnsServer);
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
            onVpnPermissionGranted(pendingDnsServer);
        }
    }

    private void onVpnPermissionGranted(String dnsServer) {
        Intent intent = new Intent(this, DnsVpnService.class);
        intent.setAction(DnsVpnService.ACTION_START);
        intent.putExtra(DnsVpnService.EXTRA_DNS_SERVER, dnsServer);
        startService(intent);
    }
}