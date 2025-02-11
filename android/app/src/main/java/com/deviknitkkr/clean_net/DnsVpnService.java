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

import com.deviknitkkr.clean_net.blocklist.WildcardTrie;
import com.deviknitkkr.clean_net.utils.SubNetUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class DnsVpnService extends VpnService {
    private static final String TAG = "DnsVpnService";
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    public static final String EXTRA_DNS_SERVER = "DNS_SERVER";
    public static final String EXTRA_BLOCKED_DOMAINS = "BLOCKED_DOMAINS";
    private ParcelFileDescriptor vpnInterface = null;
    private WildcardTrie wildcardTrie;

    AtomicInteger count = new AtomicInteger();
    private String rootDns;

    Set<String> blockedDomains;
    private SubNetUtils subNetUtils = new SubNetUtils();

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
                blockedDomains = new HashSet<>(Objects.requireNonNull(intent.getStringArrayListExtra(EXTRA_BLOCKED_DOMAINS)));
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
                    .setSession("DnsVpnService")
                    .setMtu(1500);

            // Add IPv4 address and routes
            if (ipv4Subnet != null) {
                String ipv4Address = ipv4Subnet.split("/")[0];
                builder.addAddress(ipv4Address, 24); // Add IPv4 address with prefix length
                for (int i = 1; i <= systemDns.size(); i++) {
                    String alias = subNetUtils.incrementIpAddress(ipv4Address, i);
                    Log.d(TAG, "IPv4 DNS alias: " + alias);
                    builder.addDnsServer(alias); // Add IPv4 DNS server
                    builder.addRoute(alias, 32); // Add IPv4 route
                }
            }

            // Add IPv6 address and routes
            if (ipv6Subnet != null) {
                String ipv6Address = ipv6Subnet.split("/")[0];
                builder.addAddress(ipv6Address, 64); // Add IPv6 address with prefix length
                for (int i = 1; i <= systemDns.size(); i++) {
                    String alias = subNetUtils.incrementIpAddress(ipv6Address, i);
                    Log.d(TAG, "IPv6 DNS alias: " + alias);
                    builder.addDnsServer(alias); // Add IPv6 DNS server
                    builder.addRoute(alias, 128); // Add IPv6 route
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false);
            }

            try {
                vpnInterface = builder.establish();
                FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());


                Log.d(TAG, "Setting up DNS handler");
                DnsHandler dnsHandler = new DnsHandler.Builder()
                        .dnsServerIp(rootDns == null || rootDns.isBlank() ?
                                systemDns.stream()
                                        .filter(x -> x instanceof Inet4Address)
                                        .map(InetAddress::getHostAddress)
                                        .findFirst()
                                        .orElse("1.1.1.1") // In case no system dns
                                : rootDns
                        )
                        .inputStream(in)
                        .outputStream(out)
                        .vpnService(this)
                        .dnsQueryCallback(domain -> {
                            System.out.println(count.getAndIncrement() +". Received query for domain: " + domain);
                            return wildcardTrie.matches(domain);
                        })
                        .build();
                new Thread(dnsHandler).start();
                blockedDomains.forEach(x -> wildcardTrie.insert(x)); // Costly so doing it after starting the vpn
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
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }
}
