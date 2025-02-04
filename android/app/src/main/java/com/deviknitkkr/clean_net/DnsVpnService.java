package com.deviknitkkr.clean_net;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.xbill.DNS.Message;
import org.xbill.DNS.SimpleResolver;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DnsVpnService extends VpnService implements Runnable {
    private static final String TAG = "DnsVpnService";
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    public static final String EXTRA_DNS_SERVER = "DNS_SERVER";
    private ParcelFileDescriptor vpnInterface = null;
    private String dnsServer;

    Thread vpnThread;

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
            builder.addDnsServer("8.8.8.8");
            builder.setSession("DnsVpnService");

            try {
                vpnInterface = builder.establish();
                vpnThread = new Thread(this);
                vpnThread.start();

            } catch (Exception e) {
                Log.e(TAG, "Error starting VPN: ", e);
                stopVpn();
            }
        }
    }

    private void stopVpn() {
        Log.e(TAG, "Stopping DNS Proxy");
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface: ", e);
            }
            vpnInterface = null;
        }
        try {
            vpnThread.interrupt();
        } catch (Exception e) {
            Log.d(TAG, "Vpn thread stopped");
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    @Override
    public void run() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

        byte[] packet = new byte[32767];
        while (true) {
            int length;
            try {
                length = in.read(packet);
                if (length > 0) {
                    // Check if this is a DNS query (destination port 53)
                    if (isDnsPacket(packet, length)) {
                        logDnsQuery(packet, length);
                    }
                    // Forward the packet unchanged
                    out.write(packet, 0, length);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from VPN interface", e);
            }
        }
    }

    private boolean isDnsPacket(byte[] packet, int length) {
        // Simple check: UDP packet with destination port 53
        // This assumes the packet is IPv4. For IPv6, you'd need to adjust the offsets.
        return length >= 28 && packet[9] == 17 && packet[22] == 0 && packet[23] == 53;
    }

    private void logDnsQuery(byte[] packet, int length) {
        // Extract and log the DNS query
        // This is a very basic implementation and doesn't handle all DNS packet types
        try {
            byte[] dnsPayload = Arrays.copyOfRange(packet, 28, length);
            Message dnsMessage = new Message(dnsPayload);
            Log.d(TAG, "DNS Query: " + dnsMessage.getQuestion().getName());
        } catch (IOException e) {
            Log.e(TAG, "Error parsing DNS query", e);
        }
    }
}