package com.deviknitkkr.clean_net;

import android.net.VpnService;
import android.util.Log;

import androidx.annotation.NonNull;

import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpSelector;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class DnsHandler implements Runnable {
    private static final String TAG = "DnsHandler";
    private static final int BUFFER_SIZE = 32767;
    private static final int DNS_CACHE_TTL_MS = 30_000;
    private static final int SELECT_TIMEOUT_MS = 1_000;
    private static final int MAX_PENDING = 256;
    private static final int PENDING_CLEANUP_MS = 10_000;

    private final InetAddress dnsServer;
    private final Predicate<String> dnsQueryCallback;
    private final FileInputStream inputStream;
    private final FileOutputStream outputStream;
    private final VpnService vpnService;

    private final DatagramChannel dnsChannel;
    private final Selector selector;
    private final ByteBuffer dnsReceiveBuf = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final Map<String, CachedDnsResponse> dnsCache = new LinkedHashMap<String, CachedDnsResponse>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedDnsResponse> eldest) {
            return size() > 1024;
        }
    };
    private final Map<Integer, PendingQuery> pendingQueries = new LinkedHashMap<Integer, PendingQuery>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, PendingQuery> eldest) {
            return size() > MAX_PENDING;
        }
    };
    private final AppLogBuffer appLog = AppLogBuffer.getInstance();

    private volatile boolean running = true;

    SOARecord blockedSoaResponse;

    {
        try {
            Name name = Name.fromString("blocked.example.com.");
            Name mbox = Name.fromString("admin.example.com.");
            blockedSoaResponse = new SOARecord(name, DClass.IN, 300, name, mbox, 1, 3600, 600, 86400, 300);
        } catch (TextParseException e) {
            throw new RuntimeException(e);
        }
    }

    private DnsHandler(Builder builder) throws IOException {
        this.dnsServer = InetAddress.getByName(builder.dnsServerIp);
        this.dnsQueryCallback = builder.dnsQueryCallback;
        this.inputStream = builder.inputStream;
        this.outputStream = builder.outputStream;
        this.vpnService = builder.vpnService;

        this.dnsChannel = DatagramChannel.open();
        this.dnsChannel.configureBlocking(false);
        this.vpnService.protect(this.dnsChannel.socket());

        this.selector = Selector.open();
        this.dnsChannel.register(this.selector, SelectionKey.OP_READ);
    }

    @Override
    public void run() {
        Thread responseThread = new Thread(this::responseLoop, "dns-response");
        responseThread.start();

        byte[] tunBuf = new byte[BUFFER_SIZE];
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int bytesRead = inputStream.read(tunBuf);
                if (bytesRead <= 0) continue;

                byte[] packetData = Arrays.copyOf(tunBuf, bytesRead);
                handleDnsRequest(packetData);
            }
        } catch (IOException e) {
            Log.e(TAG, "Reader error", e);
        } finally {
            running = false;
            selector.wakeup();
            try { responseThread.join(2000); } catch (InterruptedException ignored) {}
            cleanup();
        }
    }

    private void responseLoop() {
        long lastCleanup = 0;
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int ready = selector.select(SELECT_TIMEOUT_MS);
                if (ready > 0) {
                    Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (key.isReadable()) {
                            handleDnsResponse();
                        }
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastCleanup > PENDING_CLEANUP_MS) {
                    cleanupStalePendingQueries(now);
                    lastCleanup = now;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Selector error", e);
        }
    }

    private void handleDnsResponse() throws IOException {
        dnsReceiveBuf.clear();
        dnsChannel.receive(dnsReceiveBuf);
        dnsReceiveBuf.flip();

        byte[] responseData = new byte[dnsReceiveBuf.remaining()];
        dnsReceiveBuf.get(responseData);
        if (responseData.length < 2) return;

        int txnId = ((responseData[0] & 0xFF) << 8) | (responseData[1] & 0xFF);
        PendingQuery pending = pendingQueries.remove(txnId);
        if (pending == null) return;

        dnsCache.put(pending.queryName, new CachedDnsResponse(responseData));
        appLog.log(TAG, "Response: " + pending.queryName);

        sendResponse(pending.requestPacket, responseData);
    }

    private void handleDnsRequest(byte[] packetData) {
        if (!isDnsPacket(packetData)) return;

        IpPacket parsedPacket = parseIpPacket(packetData);
        if (parsedPacket == null) return;

        UdpPacket parsedUdp = extractUdpPacket(parsedPacket);
        if (parsedUdp == null) return;

        byte[] dnsRawData = parsedUdp.getPayload().getRawData();
        Message dnsMsg = parseDnsMessage(dnsRawData);
        if (dnsMsg == null || dnsMsg.getQuestion() == null) return;

        String dnsQueryName = dnsMsg.getQuestion().getName().toString(true);

        if (dnsQueryCallback.test(dnsQueryName)) {
            appLog.log(TAG, "Blocking: " + dnsQueryName);
            Log.d(TAG, "Blocking: " + dnsQueryName);
            blockDnsQuery(parsedPacket, dnsMsg);
            return;
        }

        CachedDnsResponse cached = dnsCache.get(dnsQueryName);
        if (cached != null) {
            if (cached.isValid()) {
                try {
                    sendResponse(parsedPacket, cached.data);
                } catch (IOException ignored) {}
                return;
            }
            dnsCache.remove(dnsQueryName);
        }

        pendingQueries.values().removeIf(p -> p.queryName.equals(dnsQueryName));

        int txnId = ((dnsRawData[0] & 0xFF) << 8) | (dnsRawData[1] & 0xFF);
        int serverPort = parsedUdp.getHeader().getDstPort().valueAsInt();
        ByteBuffer sendBuffer = ByteBuffer.wrap(dnsRawData);

        try {
            InetSocketAddress target = new InetSocketAddress(dnsServer, serverPort);
            if (dnsChannel.send(sendBuffer, target) > 0) {
                pendingQueries.put(txnId, new PendingQuery(parsedPacket, dnsQueryName, System.currentTimeMillis()));
                Log.d(TAG, "Forwarding: " + dnsQueryName);
            } else {
                appLog.log(TAG, "Drop (send fail): " + dnsQueryName);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error forwarding " + dnsQueryName, e);
        }
    }

    private void sendResponse(IpPacket requestPacket, byte[] responsePayload) throws IOException {
        IpPacket ipOutPacket = generateResponsePacket(requestPacket, responsePayload);
        synchronized (outputStream) {
            outputStream.write(ipOutPacket.getRawData());
        }
    }

    private void blockDnsQuery(IpPacket requestPacket, Message dnsMsg) {
        try {
            dnsMsg.getHeader().setFlag(Flags.QR);
            dnsMsg.getHeader().setRcode(Rcode.NXDOMAIN);
            dnsMsg.addRecord(blockedSoaResponse, Section.AUTHORITY);
            IpPacket ipOutPacket = generateResponsePacket(requestPacket, dnsMsg.toWire());
            synchronized (outputStream) {
                outputStream.write(ipOutPacket.getRawData());
            }
        } catch (IOException e) {
            Log.e(TAG, "Error blocking DNS query", e);
        }
    }

    private void cleanupStalePendingQueries(long now) {
        int before = pendingQueries.size();
        pendingQueries.values().removeIf(p -> now - p.sentAt > PENDING_CLEANUP_MS);
        int removed = before - pendingQueries.size();
        if (removed > 0) {
            appLog.log(TAG, "Cleaned " + removed + " stale pending queries (" + pendingQueries.size() + " remaining)");
        }
    }

    private void cleanup() {
        dnsCache.clear();
        pendingQueries.clear();
        try { selector.close(); } catch (IOException ignored) {}
        try { dnsChannel.close(); } catch (IOException ignored) {}
    }

    private IpPacket parseIpPacket(byte[] packetData) {
        try {
            return (IpPacket) IpSelector.newPacket(packetData, 0, packetData.length);
        } catch (Exception e) {
            return null;
        }
    }

    private UdpPacket extractUdpPacket(IpPacket ipPacket) {
        try {
            if (ipPacket.getPayload() instanceof UdpPacket) {
                return (UdpPacket) ipPacket.getPayload();
            }
        } catch (Exception e) {
            Log.i(TAG, "Discarding unknown packet type", e);
        }
        return null;
    }

    private Message parseDnsMessage(byte[] dnsRawData) {
        try {
            return new Message(dnsRawData);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isDnsPacket(byte[] buf) {
        if (buf.length < 28) return false;
        int version = (buf[0] >> 4) & 0x0F;
        int udpOffset;
        if (version == 4) {
            int ihl = (buf[0] & 0x0F) * 4;
            if (buf.length < ihl + 8) return false;
            if (buf[9] != 17) return false;
            udpOffset = ihl;
        } else if (version == 6) {
            if (buf.length < 48) return false;
            if (buf[6] != 17) return false;
            udpOffset = 40;
        } else {
            return false;
        }
        int dstPort = ((buf[udpOffset + 2] & 0xFF) << 8) | (buf[udpOffset + 3] & 0xFF);
        return dstPort == 53;
    }

    @NonNull
    private static IpPacket generateResponsePacket(IpPacket requestPacket, byte[] responsePayload) {
        UdpPacket udpOutPacket = (UdpPacket) requestPacket.getPayload();
        UdpPacket.Builder udpBuilder = new UdpPacket.Builder(udpOutPacket)
                .srcPort(udpOutPacket.getHeader().getDstPort())
                .dstPort(udpOutPacket.getHeader().getSrcPort())
                .srcAddr(requestPacket.getHeader().getDstAddr())
                .dstAddr(requestPacket.getHeader().getSrcAddr())
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(new UnknownPacket.Builder().rawData(responsePayload));

        if (requestPacket instanceof IpV4Packet) {
            return new IpV4Packet.Builder((IpV4Packet) requestPacket)
                    .srcAddr((Inet4Address) requestPacket.getHeader().getDstAddr())
                    .dstAddr((Inet4Address) requestPacket.getHeader().getSrcAddr())
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true)
                    .payloadBuilder(udpBuilder)
                    .build();
        } else {
            return new IpV6Packet.Builder((IpV6Packet) requestPacket)
                    .srcAddr((Inet6Address) requestPacket.getHeader().getDstAddr())
                    .dstAddr((Inet6Address) requestPacket.getHeader().getSrcAddr())
                    .correctLengthAtBuild(true)
                    .payloadBuilder(udpBuilder)
                    .build();
        }
    }

    private static class CachedDnsResponse {
        final byte[] data;
        final long expiresAt;

        CachedDnsResponse(byte[] data) {
            this.data = data;
            this.expiresAt = System.currentTimeMillis() + DNS_CACHE_TTL_MS;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    private static class PendingQuery {
        final IpPacket requestPacket;
        final String queryName;
        final long sentAt;

        PendingQuery(IpPacket requestPacket, String queryName, long sentAt) {
            this.requestPacket = requestPacket;
            this.queryName = queryName;
            this.sentAt = sentAt;
        }
    }

    public static class Builder {
        private String dnsServerIp;
        private Predicate<String> dnsQueryCallback = query -> true;
        private FileInputStream inputStream;
        private FileOutputStream outputStream;
        private VpnService vpnService;

        public Builder dnsServerIp(String dnsServerIp) {
            this.dnsServerIp = dnsServerIp;
            return this;
        }

        public Builder dnsQueryCallback(Predicate<String> dnsQueryCallback) {
            this.dnsQueryCallback = dnsQueryCallback;
            return this;
        }

        public Builder inputStream(FileInputStream inputStream) {
            this.inputStream = inputStream;
            return this;
        }

        public Builder outputStream(FileOutputStream outputStream) {
            this.outputStream = outputStream;
            return this;
        }

        public Builder vpnService(VpnService vpnService) {
            this.vpnService = vpnService;
            return this;
        }

        public DnsHandler build() throws IOException {
            if (dnsServerIp == null || inputStream == null || outputStream == null || vpnService == null) {
                throw new IllegalStateException("DNS server IP, InputStream, OutputStream, and VpnService are required");
            }
            return new DnsHandler(this);
        }
    }
}
