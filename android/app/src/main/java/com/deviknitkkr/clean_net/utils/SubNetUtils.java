package com.deviknitkkr.clean_net.utils;

import java.net.*;
import java.util.*;

public class SubNetUtils {

    // List of candidate IPv4 subnets to choose from
    private static final String[] CANDIDATE_IPV4_SUBNETS = {
            "10.0.0.0/24", "10.0.1.0/24", "10.0.2.0/24",
            "172.16.0.0/24", "172.16.1.0/24", "172.16.2.0/24",
            "192.168.0.0/24", "192.168.1.0/24", "192.168.2.0/24"
    };

    // List of candidate IPv6 subnets to choose from
    private static final String[] CANDIDATE_IPV6_SUBNETS = {
            "fd00::/64", "fd01::/64", "fd02::/64"
    };

    /**
     * Detects local subnets (both IPv4 and IPv6) on the device.
     *
     * @return List of local subnets in CIDR notation (e.g., "192.168.1.0/24", "fd00::/64").
     */
    public List<String> getLocalSubnets() {
        List<String> subnets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress()) {
                        if (addr instanceof Inet4Address) {
                            String ip = addr.getHostAddress();
                            String subnet = ip.substring(0, ip.lastIndexOf('.') + 1) + "0/24"; // Example: 192.168.1.0/24
                            subnets.add(subnet);
                        } else if (addr instanceof Inet6Address) {
                            String ip = addr.getHostAddress();
                            String subnet = ip.substring(0, ip.lastIndexOf(':') + 1) + "0/64"; // Example: fd00::/64
                            subnets.add(subnet);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return subnets;
    }

    /**
     * Finds non-conflicting subnets for both IPv4 and IPv6.
     *
     * @param localSubnets List of local subnets to avoid conflicts with.
     * @return A map containing "ipv4" and "ipv6" keys with their respective subnets, or null if none are available.
     */
    public Map<String, String> findAvailableSubnets(List<String> localSubnets) {
        Map<String, String> availableSubnets = new HashMap<>();

        // Find an available IPv4 subnet
        for (String candidate : CANDIDATE_IPV4_SUBNETS) {
            boolean conflict = false;
            for (String localSubnet : localSubnets) {
                if (subnetsOverlap(candidate, localSubnet)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) {
                availableSubnets.put("ipv4", candidate);
                break;
            }
        }

        // Find an available IPv6 subnet
        for (String candidate : CANDIDATE_IPV6_SUBNETS) {
            boolean conflict = false;
            for (String localSubnet : localSubnets) {
                if (subnetsOverlap(candidate, localSubnet)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) {
                availableSubnets.put("ipv6", candidate);
                break;
            }
        }

        return availableSubnets.isEmpty() ? null : availableSubnets;
    }

    /**
     * Checks if two subnets overlap.
     *
     * @param subnet1 First subnet in CIDR notation (e.g., "10.0.0.0/24", "fd00::/64").
     * @param subnet2 Second subnet in CIDR notation (e.g., "192.168.1.0/24", "fd01::/64").
     * @return True if the subnets overlap, false otherwise.
     */
    public boolean subnetsOverlap(String subnet1, String subnet2) {
        try {
            CIDRUtils cidr1 = new CIDRUtils(subnet1);
            CIDRUtils cidr2 = new CIDRUtils(subnet2);
            return cidr1.isInRange(cidr2.getNetworkAddress()) || cidr2.isInRange(cidr1.getNetworkAddress());
        } catch (Exception e) {
            e.printStackTrace();
            return true; // Assume overlap if there's an error
        }
    }

    /**
     * Increments an IP address (IPv4 or IPv6) by a specified value.
     *
     * @param ipAddress The base IP address (e.g., "10.0.0.0", "fd00::").
     * @param increment The value to increment by (e.g., 1 for "10.0.0.1", "fd00::1").
     * @return The incremented IP address as a string.
     */
    public String incrementIpAddress(String ipAddress, int increment) {
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            byte[] bytes = address.getAddress();
            if (address instanceof Inet4Address) {
                int lastByte = bytes[3] & 0xFF; // Get the last byte (e.g., 0 in 10.0.0.0)
                lastByte += increment; // Increment the last byte
                bytes[3] = (byte) (lastByte & 0xFF); // Set the incremented byte
            } else if (address instanceof Inet6Address) {
                int lastByte = bytes[15] & 0xFF; // Get the last byte (e.g., 0 in fd00::)
                lastByte += increment; // Increment the last byte
                bytes[15] = (byte) (lastByte & 0xFF); // Set the incremented byte
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Helper class for CIDR calculations.
     */
    private static class CIDRUtils {
        private final InetAddress address;
        private final int networkLength;

        public CIDRUtils(String cidr) throws UnknownHostException {
            String[] parts = cidr.split("/");
            this.address = InetAddress.getByName(parts[0]);
            this.networkLength = Integer.parseInt(parts[1]);
        }

        public String getNetworkAddress() {
            return address.getHostAddress();
        }

        public boolean isInRange(String ipAddress) throws UnknownHostException {
            InetAddress remoteAddress = InetAddress.getByName(ipAddress);
            byte[] remoteBytes = remoteAddress.getAddress();
            byte[] localBytes = address.getAddress();

            int mask = networkLength;
            int fullBytes = mask / 8;
            int remainderBits = mask % 8;

            // Compare full bytes
            for (int i = 0; i < fullBytes; i++) {
                if (remoteBytes[i] != localBytes[i]) {
                    return false;
                }
            }

            // Compare remaining bits
            if (remainderBits > 0) {
                int maskByte = (0xFF << (8 - remainderBits)) & 0xFF;
                return (remoteBytes[fullBytes] & maskByte) == (localBytes[fullBytes] & maskByte);
            }

            return true;
        }
    }
}