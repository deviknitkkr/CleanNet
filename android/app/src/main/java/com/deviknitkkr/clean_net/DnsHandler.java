package com.deviknitkkr.clean_net;

import android.net.VpnService;
import android.util.Log;

import androidx.annotation.NonNull;

import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpSelector;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.function.Predicate;

public class DnsHandler implements Runnable {
    private static final String TAG = "DnsHandler";
    private static final int BUFFER_SIZE = 32767;
    private final DatagramSocket dnsSocket;
    private final InetAddress dnsServer;
    private final Predicate<String> dnsQueryCallback;
    private final FileInputStream inputStream;
    private final FileOutputStream outputStream;

    private VpnService vpnService;

    private DnsHandler(Builder builder) throws SocketException, UnknownHostException {
        this.dnsSocket = new DatagramSocket();
        this.dnsServer = InetAddress.getByName(builder.dnsServerIp);
        this.dnsQueryCallback = builder.dnsQueryCallback;
        this.inputStream = builder.inputStream;
        this.outputStream = builder.outputStream;
        this.vpnService = builder.vpnService;
    }

    public DatagramSocket getDnsSocket() {
        return dnsSocket;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead <= 0) {
                    continue;
                }
                handleDnsRequest(Arrays.copyOfRange(buffer, 0, bytesRead));
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in run loop: " + e.getMessage());
        } finally {
            dnsSocket.close();
        }
    }

    void handleDnsRequest(byte[] packetData) {

        IpPacket parsedPacket = null;
        try {
            parsedPacket = (IpPacket) IpSelector.newPacket(packetData, 0, packetData.length);
        } catch (Exception e) {
            Log.i(TAG, "handleDnsRequest: Discarding invalid IP packet", e);
            return;
        }

        UdpPacket parsedUdp;
        Packet udpPayload = null;

        try {
            if (parsedPacket.getPayload() instanceof UdpPacket) {
                parsedUdp = (UdpPacket) parsedPacket.getPayload();
                udpPayload = parsedUdp.getPayload();
            } else {
                return;
            }
        } catch (Exception e) {
            try {
                Log.i(TAG, "handleDnsRequest: Discarding unknown packet type " + parsedPacket.getHeader(), e);
            } catch (Exception e1) {
                Log.i(TAG, "handleDnsRequest: Discarding unknown packet type, could not log packet info", e1);
            }
            return;
        }

        InetAddress destAddr = dnsServer;
        if (destAddr == null)
            return;


        byte[] dnsRawData = udpPayload.getRawData();
        Message dnsMsg;
        try {
            dnsMsg = new Message(dnsRawData);
        } catch (IOException e) {
            Log.i(TAG, "handleDnsRequest: Discarding non-DNS or invalid packet", e);
            return;
        }
        if (dnsMsg.getQuestion() == null) {
            Log.i(TAG, "handleDnsRequest: Discarding DNS packet with no query " + dnsMsg);
            return;
        }
        String dnsQueryName = dnsMsg.getQuestion().getName().toString(true);
        boolean shouldBlock = dnsQueryCallback.test(dnsQueryName);

        if (!shouldBlock) { // Forward Dns query to root dns server
            DatagramPacket outPacket = new DatagramPacket(dnsRawData, 0, dnsRawData.length, destAddr, parsedUdp.getHeader().getDstPort().valueAsInt());
            try {
                DatagramSocket tunnel = new DatagramSocket();
                vpnService.protect(tunnel);
                tunnel.send(outPacket);

                byte[] responsePayload = new byte[1024];
                DatagramPacket replyPacket = new DatagramPacket(responsePayload, responsePayload.length);
                tunnel.receive(replyPacket);
                tunnel.close();

                IpPacket ipOutPacket = generateResponsePacket(parsedPacket, responsePayload);

                byte[] rawResponse = ipOutPacket.getRawData();
                outputStream.write(rawResponse);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else { // Send block response to device
            try {
                Log.d(TAG, "Blocking:" + dnsQueryName);
                dnsMsg.getHeader().setFlag(Flags.QR);
                dnsMsg.getHeader().setRcode(Rcode.NXDOMAIN);

                Name name = Name.fromString("blocked.example.com.");  // Use a more meaningful domain
                Name mbox = Name.fromString("admin.example.com.");    // Use a valid email address format
                SOARecord soaRecord = new SOARecord(name, DClass.IN, 300,  // 5 minutes TTL
                        name, mbox, 1, 3600, 600, 86400, 300);

                dnsMsg.addRecord(soaRecord, Section.AUTHORITY);
                IpPacket ipOutPacket = generateResponsePacket(parsedPacket, dnsMsg.toWire());
                outputStream.write(ipOutPacket.getRawData());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @NonNull
    private static IpPacket generateResponsePacket(IpPacket requestPacket, byte[] responsePayload) {
        UdpPacket udpOutPacket = (UdpPacket) requestPacket.getPayload();
        UdpPacket.Builder payLoadBuilder = new UdpPacket.Builder(udpOutPacket)
                .srcPort(udpOutPacket.getHeader().getDstPort())
                .dstPort(udpOutPacket.getHeader().getSrcPort())
                .srcAddr(requestPacket.getHeader().getDstAddr())
                .dstAddr(requestPacket.getHeader().getSrcAddr())
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(
                        new UnknownPacket.Builder()
                                .rawData(responsePayload)
                );

        IpPacket ipOutPacket;
        if (requestPacket instanceof IpV4Packet) {
            ipOutPacket = new IpV4Packet.Builder((IpV4Packet) requestPacket)
                    .srcAddr((Inet4Address) requestPacket.getHeader().getDstAddr())
                    .dstAddr((Inet4Address) requestPacket.getHeader().getSrcAddr())
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true)
                    .payloadBuilder(payLoadBuilder)
                    .build();

        } else {
            ipOutPacket = new IpV6Packet.Builder((IpV6Packet) requestPacket)
                    .srcAddr((Inet6Address) requestPacket.getHeader().getDstAddr())
                    .dstAddr((Inet6Address) requestPacket.getHeader().getSrcAddr())
                    .correctLengthAtBuild(true)
                    .payloadBuilder(payLoadBuilder)
                    .build();
        }
        return ipOutPacket;
    }


    public static class Builder {
        private String dnsServerIp;
        private Predicate<String> dnsQueryCallback = query -> true;
        private FileInputStream inputStream;
        private FileOutputStream outputStream;

        private VpnService vpnService;

        public Builder vpnService(VpnService vpnService) {
            this.vpnService = vpnService;
            return this;
        }

        public Builder rootDnsServer(String dnsServerIp) {
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

        public DnsHandler build() throws SocketException, UnknownHostException {
            if (dnsServerIp == null || inputStream == null || outputStream == null || vpnService == null) {
                throw new IllegalStateException("DNS server IP, InputStream, OutputStream and VpnService are required");
            }
            return new DnsHandler(this);
        }
    }

}