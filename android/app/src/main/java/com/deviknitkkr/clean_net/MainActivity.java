package com.deviknitkkr.clean_net;

import static com.deviknitkkr.clean_net.DnsVpnService.PREFS_NAME;
import static com.deviknitkkr.clean_net.DnsVpnService.VPN_RUNNING_KEY;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity {
    private static final String TAG = "MainActivity";
    private static final String CHANNEL = "com.deviknitkkr.clean_net/vpn";
    private static final int VPN_REQUEST_CODE = 100;

    private Intent vpnIntent;
    private MethodChannel channel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate called");
        vpnIntent = new Intent(this, DnsVpnService.class);
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        Log.d(TAG, "configureFlutterEngine called");

        channel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler((call, result) -> {
            Log.d(TAG, "Method call received: " + call.method);
            switch (call.method) {
                case "startVpn":
                    startVpn();
                    result.success(null);
                    break;
                case "stopVpn":
                    stopVpn();
                    result.success(null);
                    break;
                case "updateBlockedDomains":
                    updateBlockedDomains(call.argument("domains"));
                    result.success(null);
                    break;
                case "isVpnRunning":
                    result.success(getVpnStatus());
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        });
    }

    private void startVpn() {
        Log.d(TAG, "startVpn called");
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    private void stopVpn() {
        Log.d(TAG, "stopVpn called from MainActivity");
        Intent intent = new Intent(this, DnsVpnService.class);
        intent.setAction("STOP_VPN");
        startService(intent);
        stopService(vpnIntent);
    }

    private void updateBlockedDomains(Object domains) {
        Log.d(TAG, "updateBlockedDomains called");
        if (domains instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> blockedDomains = (Set<String>) domains;
            Intent intent = new Intent(this, DnsVpnService.class);
            intent.setAction("UPDATE_BLOCKED_DOMAINS");
            intent.putExtra("blockedDomains", new HashSet<>(blockedDomains));
            startService(intent);
        }
    }

    private boolean getVpnStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(VPN_RUNNING_KEY, false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startService(vpnIntent);
        }
    }

}