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
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Handles DNS requests and responses asynchronously for better throughput.
 */
public class DnsHandler implements Runnable {
    private static final String TAG = "DnsHandler";
    private static final int BUFFER_SIZE = 32767;
    private static final int THREAD_POOL_SIZE = 4; // Adjust based on system resources

    private final InetAddress dnsServer;
    private final Predicate<String> dnsQueryCallback;
    private final FileInputStream inputStream;
    private final FileOutputStream outputStream;
    private final VpnService vpnService;
    private final ExecutorService executorService;
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

    private DnsHandler(Builder builder) throws SocketException, UnknownHostException {
        this.dnsServer = InetAddress.getByName(builder.dnsServerIp);
        this.dnsQueryCallback = builder.dnsQueryCallback;
        this.inputStream = builder.inputStream;
        this.outputStream = builder.outputStream;
        this.vpnService = builder.vpnService;
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    @Override
    public void run() {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead <= 0) continue;

                byte[] packetData = Arrays.copyOfRange(buffer, 0, bytesRead);
                executorService.submit(() -> handleDnsRequest(packetData));
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in run loop: " + e.getMessage());
        } finally {
            executorService.shutdown();
        }
    }

    /**
     * Handles a DNS request asynchronously.
     *
     * @param packetData The raw packet data containing the DNS request.
     */
    private void handleDnsRequest(byte[] packetData) {
        IpPacket parsedPacket = parseIpPacket(packetData);
        if (parsedPacket == null) return;

        UdpPacket parsedUdp = extractUdpPacket(parsedPacket);
        if (parsedUdp == null) return;

        byte[] dnsRawData = parsedUdp.getPayload().getRawData();
        Message dnsMsg = parseDnsMessage(dnsRawData);
        if (dnsMsg == null || dnsMsg.getQuestion() == null) return;

        String dnsQueryName = dnsMsg.getQuestion().getName().toString(true);
        if (dnsQueryCallback.test(dnsQueryName)) {
            blockDnsQuery(parsedPacket, dnsMsg);
        } else {
            forwardDnsQueryAsync(parsedPacket, parsedUdp, dnsRawData);
        }
    }

    /**
     * Forwards the DNS query to the DNS server asynchronously.
     *
     * @param requestPacket The original IP packet.
     * @param udpPacket     The UDP packet containing the DNS query.
     * @param dnsRawData    The raw DNS data.
     */
    private void forwardDnsQueryAsync(IpPacket requestPacket, UdpPacket udpPacket, byte[] dnsRawData) {
        CompletableFuture.runAsync(() -> {
            try (DatagramChannel channel = DatagramChannel.open()) {
                vpnService.protect(channel.socket());

                // Send the DNS query
                ByteBuffer sendBuffer = ByteBuffer.wrap(dnsRawData);
                channel.send(sendBuffer, new InetSocketAddress(dnsServer, udpPacket.getHeader().getDstPort().valueAsInt()));

                // Receive the DNS response
                ByteBuffer receiveBuffer = ByteBuffer.allocate(BUFFER_SIZE);
                channel.receive(receiveBuffer);
                receiveBuffer.flip();

                byte[] responsePayload = new byte[receiveBuffer.remaining()];
                receiveBuffer.get(responsePayload);

                // Generate and send the response packet
                IpPacket ipOutPacket = generateResponsePacket(requestPacket, responsePayload);
                outputStream.write(ipOutPacket.getRawData());
            } catch (IOException e) {
                Log.e(TAG, "Error forwarding DNS query asynchronously", e);
            }
        }, executorService);
    }

    /**
     * Blocks the DNS query by sending a blocked response.
     *
     * @param requestPacket The original IP packet.
     * @param dnsMsg        The DNS message to block.
     */
    private void blockDnsQuery(IpPacket requestPacket, Message dnsMsg) {
        try {
            Log.d(TAG, "Blocking: " + dnsMsg.getQuestion().getName().toString(true));
            dnsMsg.getHeader().setFlag(Flags.QR);
            dnsMsg.getHeader().setRcode(Rcode.NXDOMAIN);


            dnsMsg.addRecord(blockedSoaResponse, Section.AUTHORITY);
            IpPacket ipOutPacket = generateResponsePacket(requestPacket, dnsMsg.toWire());
            outputStream.write(ipOutPacket.getRawData());
        } catch (IOException e) {
            Log.e(TAG, "Error blocking DNS query", e);
        }
    }

    /**
     * Parses the raw packet data into an IP packet.
     *
     * @param packetData The raw packet data.
     * @return The parsed IP packet, or null if parsing fails.
     */
    private IpPacket parseIpPacket(byte[] packetData) {
        try {
            return (IpPacket) IpSelector.newPacket(packetData, 0, packetData.length);
        } catch (Exception e) {
            Log.i(TAG, "Discarding invalid IP packet", e);
            return null;
        }
    }

    /**
     * Extracts the UDP packet from the IP packet.
     *
     * @param ipPacket The IP packet.
     * @return The extracted UDP packet, or null if extraction fails.
     */
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

    /**
     * Parses the raw DNS data into a DNS message.
     *
     * @param dnsRawData The raw DNS data.
     * @return The parsed DNS message, or null if parsing fails.
     */
    private Message parseDnsMessage(byte[] dnsRawData) {
        try {
            return new Message(dnsRawData);
        } catch (IOException e) {
            Log.i(TAG, "Discarding non-DNS or invalid packet", e);
            return null;
        }
    }

    /**
     * Generates a response packet based on the original request packet and the response payload.
     *
     * @param requestPacket   The original IP packet.
     * @param responsePayload The response payload.
     * @return The generated response packet.
     */
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

    /**
     * Builder class for constructing a DnsHandler instance.
     */
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

        public DnsHandler build() throws SocketException, UnknownHostException {
            if (dnsServerIp == null || inputStream == null || outputStream == null || vpnService == null) {
                throw new IllegalStateException("DNS server IP, InputStream, OutputStream, and VpnService are required");
            }
            return new DnsHandler(this);
        }
    }
}