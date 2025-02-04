package com.deviknitkkr.clean_net;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DnsVpnService extends VpnService {
    private static final String TAG = "DnsVpnService";
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    public static final String EXTRA_DNS_SERVER = "DNS_SERVER";

    private ParcelFileDescriptor vpnInterface = null;
    private ExecutorService executorService;
    private String dnsServer;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                dnsServer = intent.getStringExtra(EXTRA_DNS_SERVER);
                startVpn();
            } else if (ACTION_STOP.equals(action)) {
                stopVpn();
            }
        }
        return START_STICKY;
    }

    private void startVpn() {
        if (vpnInterface == null) {
            Builder builder = new Builder();
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer(dnsServer);
            builder.setSession("DnsVpnService");

            try {
                vpnInterface = builder.establish();
                executorService = Executors.newSingleThreadExecutor();
                executorService.submit(this::processPackets);
            } catch (Exception e) {
                Log.e(TAG, "Error starting VPN: ", e);
                stopVpn();
            }
        }
    }

    private void stopVpn() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface: ", e);
            }
            vpnInterface = null;
        }
        stopSelf();
    }

    private void processPackets() {
        // This method will contain the actual packet interception logic
        // For now, we'll just log that it's running
        while (!Thread.interrupted()) {
            Log.d(TAG, "Processing packets...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }
}