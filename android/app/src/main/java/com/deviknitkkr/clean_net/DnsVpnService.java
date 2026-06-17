package com.deviknitkkr.clean_net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.deviknitkkr.clean_net.blocklist.WildcardTrie;
import com.deviknitkkr.clean_net.utils.SubNetUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class DnsVpnService extends VpnService {
    private static final String TAG = "DnsVpnService";
    private static final String CHANNEL_ID = "clean_net_vpn";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "clean_net_prefs";

    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    public static final String EXTRA_DNS_SERVER = "DNS_SERVER";
    public static final String EXTRA_BLOCKED_DOMAINS = "BLOCKED_DOMAINS";

    public static volatile boolean isRunning = false;
    public static final ConcurrentHashMap<String, AtomicInteger> blockedStats = new ConcurrentHashMap<>();

    private ParcelFileDescriptor vpnInterface = null;
    private WildcardTrie wildcardTrie;
    AtomicInteger count = new AtomicInteger();
    private String rootDns;
    Set<String> blockedDomains;
    private SubNetUtils subNetUtils = new SubNetUtils();
    private Thread notificationUpdater;

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "VPN Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification for ad blocking VPN service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int totalBlocked = 0;
        for (AtomicInteger v : blockedStats.values()) {
            totalBlocked += v.get();
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("CleanNet")
                .setContentText("Ad blocking active — " + totalBlocked + " requests blocked")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private List<InetAddress> getSystemDns() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network activeNetwork = cm.getActiveNetwork();
            return Optional.ofNullable(cm.getLinkProperties(activeNetwork))
                    .map(LinkProperties::getDnsServers)
                    .orElse(Collections.emptyList());
        }
        return Collections.emptyList();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                rootDns = intent.getStringExtra(EXTRA_DNS_SERVER);
                blockedDomains = new HashSet<>(Objects.requireNonNull(
                        intent.getStringArrayListExtra(EXTRA_BLOCKED_DOMAINS)));
                wildcardTrie = new WildcardTrie();
                startVpn();
            } else if (ACTION_STOP.equals(action)) {
                stopVpn();
            }
        }
        return START_STICKY;
    }

    private void startVpn() {
        List<String> localSubnets = subNetUtils.getLocalSubnets();
        Map<String, String> availableSubnets = subNetUtils.findAvailableSubnets(localSubnets);

        if (availableSubnets == null) {
            Log.e(TAG, "No available subnets found!");
            return;
        }

        String ipv4Subnet = availableSubnets.get("ipv4");
        String ipv6Subnet = availableSubnets.get("ipv6");

        Log.d(TAG, "Local subnets: " + localSubnets);
        Log.d(TAG, "Available IPv4 subnet: " + ipv4Subnet);
        Log.d(TAG, "Available IPv6 subnet: " + ipv6Subnet);

        List<InetAddress> systemDns = getSystemDns();
        Log.d(TAG, "System DNS: " + systemDns);

        if (vpnInterface == null) {
            Builder builder = new Builder()
                    .setSession("CleanNet")
                    .setMtu(1500);

            if (ipv4Subnet != null) {
                String ipv4Address = ipv4Subnet.split("/")[0];
                builder.addAddress(ipv4Address, 24);
                for (int i = 1; i <= systemDns.size(); i++) {
                    String alias = subNetUtils.incrementIpAddress(ipv4Address, i);
                    builder.addDnsServer(alias);
                    builder.addRoute(alias, 32);
                }
            }

            if (ipv6Subnet != null) {
                String ipv6Address = ipv6Subnet.split("/")[0];
                builder.addAddress(ipv6Address, 64);
                for (int i = 1; i <= systemDns.size(); i++) {
                    String alias = subNetUtils.incrementIpAddress(ipv6Address, i);
                    builder.addDnsServer(alias);
                    builder.addRoute(alias, 128);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false);
            }

            try {
                vpnInterface = builder.establish();
                FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

                DnsHandler dnsHandler = new DnsHandler.Builder()
                        .dnsServerIp(rootDns == null || rootDns.isBlank() ?
                                systemDns.stream()
                                        .filter(x -> x instanceof Inet4Address)
                                        .map(InetAddress::getHostAddress)
                                        .findFirst()
                                        .orElse("1.1.1.1")
                                : rootDns
                        )
                        .inputStream(in)
                        .outputStream(out)
                        .vpnService(this)
                        .dnsQueryCallback(domain -> {
                            Log.d(TAG, count.getAndIncrement() + ". Received query: " + domain);
                            boolean blocked = wildcardTrie.matches(domain);
                            if (blocked) {
                                blockedStats.computeIfAbsent(domain, k -> new AtomicInteger()).incrementAndGet();
                            }
                            return blocked;
                        })
                        .build();

                blockedDomains.forEach(x -> wildcardTrie.insert(x));
                blockedStats.clear();

                // Foreground service
                createNotificationChannel();
                startForeground(NOTIFICATION_ID, buildNotification());

                new Thread(dnsHandler).start();

                notificationUpdater = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(5000);
                            NotificationManager nm = getSystemService(NotificationManager.class);
                            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
                notificationUpdater.start();

                isRunning = true;
                saveVpnState(true);
            } catch (Exception e) {
                Log.e(TAG, "Error starting VPN: ", e);
                stopVpn();
            }
        }
    }

    private void stopVpn() {
        Log.d(TAG, "Stopping DNS Proxy");
        isRunning = false;
        saveVpnState(false);
        if (notificationUpdater != null) {
            notificationUpdater.interrupt();
            notificationUpdater = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface: ", e);
            }
            vpnInterface = null;
        }
        blockedStats.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void saveVpnState(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean("vpn_enabled", enabled).apply();
    }

    public static boolean isVpnRunning(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean("vpn_enabled", false);
    }

    public static Map<String, Integer> getBlockedStatsSnapshot() {
        Map<String, Integer> snapshot = new HashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : blockedStats.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return snapshot;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }
}
