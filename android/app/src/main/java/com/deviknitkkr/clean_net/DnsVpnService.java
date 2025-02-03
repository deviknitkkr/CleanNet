package com.deviknitkkr.clean_net;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import org.xbill.DNS.*;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DnsVpnService extends VpnService {
    private static final String TAG = "DnsVpnService";
    public static final String PREFS_NAME = "VpnPreferences";
    public static final String VPN_RUNNING_KEY = "vpn_running";
    private static final int DNS_QUERY_PORT = 53;
    private static final String GOOGLE_DNS_SERVER = "8.8.8.8";

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executorService;
    private Set<String> blockedDomains;
    private boolean isRunning;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");
        executorService = Executors.newSingleThreadExecutor();
        blockedDomains = new HashSet<>();
        isRunning = false;
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        if (intent != null) {
            String action = intent.getAction();
            if ("UPDATE_BLOCKED_DOMAINS".equals(action)) {
                Set<String> newBlockedDomains = (Set<String>) intent.getSerializableExtra("blockedDomains");
                if (newBlockedDomains != null) {
                    updateBlockedDomains(newBlockedDomains);
                }
            } else if ("STOP_VPN".equals(action)) {
                Log.d(TAG, "Stopping VPN from onStartCommand");
                stopVpn();
                stopSelf(); // This will eventually call onDestroy
                return START_NOT_STICKY;
            } else if (!isRunning) {
                isRunning = true;
                startVpn();
            }
        }
        return START_STICKY;
    }

    private void startVpn() {
        Log.d(TAG, "startVpn called");
        Builder builder = new Builder();
        builder.addAddress("10.0.0.1", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer(GOOGLE_DNS_SERVER);
        builder.setSession("CleanNet DNS");
        vpnInterface = builder.establish();

        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface.");
            showToast("Failed to start VPN");
            return;
        }

        showToast("VPN Started");
        setVpnStatus(true);
        executorService.submit(this::handleDnsRequests);
    }

    private void handleDnsRequests() {
        Log.d(TAG, "handleDnsRequests started");
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

        byte[] packet = new byte[32767];
        while (isRunning) {
            try {
                int length = in.read(packet);
                if (length > 0) {
                    ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length);
                    if (isUdpDnsPacket(buffer)) {
                        handleDnsPacket(buffer, out);
                    } else {
                        // Forward non-DNS packets
                        out.write(packet, 0, length);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error handling packet", e);
            }
        }
        Log.d(TAG, "handleDnsRequests stopped");
    }

    private boolean isUdpDnsPacket(ByteBuffer buffer) {
        return buffer.getShort(0) == 0 && buffer.get(9) == 17 && buffer.getShort(20) == DNS_QUERY_PORT;
    }

    private void handleDnsPacket(ByteBuffer buffer, FileOutputStream out) throws IOException {
        byte[] dnsPayload = new byte[buffer.limit() - 28];
        buffer.position(28);
        buffer.get(dnsPayload);

        String domain = parseDnsDomain(dnsPayload);
        Log.d(TAG, "DNS query for domain: " + domain);

        if (blockedDomains.contains(domain)) {
            Log.d(TAG, "Blocking domain: " + domain);
            sendBlockedResponse(buffer, out);
        } else {
            forwardToGoogleDns(dnsPayload, buffer, out);
        }
    }

    private String parseDnsDomain(byte[] dnsPayload) {
        StringBuilder domain = new StringBuilder();
        int i = 12;
        while (dnsPayload[i] != 0) {
            int length = dnsPayload[i++];
            for (int j = 0; j < length; j++) {
                domain.append((char) dnsPayload[i++]);
            }
            domain.append('.');
        }
        return domain.toString();
    }

    private void sendBlockedResponse(ByteBuffer originalPacket, FileOutputStream out) throws IOException {
        ByteBuffer response = ByteBuffer.allocate(originalPacket.limit());
        response.put(originalPacket);
        response.putShort(2, (short) (response.getShort(2) | 0x8000)); // Set QR bit to 1 (response)
        response.putShort(6, (short) 0); // ANCOUNT = 0
        response.putShort(8, (short) 0); // NSCOUNT = 0
        response.putShort(10, (short) 0); // ARCOUNT = 0

        out.write(response.array(), 0, response.limit());
    }

    private void forwardToGoogleDns(byte[] dnsPayload, ByteBuffer originalPacket, FileOutputStream out) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        socket.connect(InetAddress.getByName(GOOGLE_DNS_SERVER), DNS_QUERY_PORT);

        DatagramPacket outPacket = new DatagramPacket(dnsPayload, dnsPayload.length);
        socket.send(outPacket);

        byte[] responseData = new byte[1024];
        DatagramPacket inPacket = new DatagramPacket(responseData, responseData.length);
        socket.receive(inPacket);

        ByteBuffer response = ByteBuffer.allocate(originalPacket.limit());
        response.put(originalPacket);
        response.position(28);
        response.put(responseData, 0, inPacket.getLength());

        out.write(response.array(), 0, response.limit());
        socket.close();
    }

    private void stopVpn() {
        Log.d(TAG, "stopVpn called");
        isRunning = false;
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
        }
        executorService.shutdownNow();
        setVpnStatus(false);
        showToast("VPN Stopped");
    }

    private void setVpnStatus(boolean running) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(VPN_RUNNING_KEY, running);
        editor.apply();
    }

    public void updateBlockedDomains(Set<String> newBlockedDomains) {
        Log.d(TAG, "Updating blocked domains: " + newBlockedDomains);
        blockedDomains = newBlockedDomains;
        showToast("Blocked domains updated");
    }

    private void showToast(final String message) {
        mainHandler.post(() -> Toast.makeText(DnsVpnService.this, message, Toast.LENGTH_SHORT).show());
    }
}