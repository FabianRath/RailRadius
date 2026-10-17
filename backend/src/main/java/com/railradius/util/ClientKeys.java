package com.railradius.util;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * The key per-client limits count under. One IPv6 subscriber gets a whole /64 and can rotate through it freely, so an
 * IPv6 client is keyed by its /64 prefix; an IPv4 client by its full address.
 */
public final class ClientKeys {

    private static final char ZONE_SEPARATOR = '%';
    // The first 64 of an IPv6 address's 128 bits, as four 16-bit groups
    private static final int PREFIX_GROUPS = 4;
    private static final String PREFIX_SUFFIX = "::/64";

    private ClientKeys() {}

    /**
     * The key for a remote address: an IPv4 address unchanged, an IPv6 address as its /64 prefix in one canonical
     * spelling (e.g. {@code 2001:db8:1:2::/64}), an IPv4-mapped IPv6 address as the IPv4 address, anything else
     * unchanged. Parses literals only and never does a DNS lookup.
     */
    public static String rateLimitKey(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.indexOf(':') < 0) return remoteAddr;
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(withoutBracketsAndZone(remoteAddr));
        } catch (IllegalArgumentException notALiteral) {
            return remoteAddr;
        }
        if (address instanceof Inet4Address) return address.getHostAddress();
        return prefix64(address.getAddress());
    }

    private static String withoutBracketsAndZone(String address) {
        String unbracketed = address.startsWith("[") && address.endsWith("]")
                ? address.substring(1, address.length() - 1)
                : address;
        int zoneIdx = unbracketed.indexOf(ZONE_SEPARATOR);
        return zoneIdx < 0 ? unbracketed : unbracketed.substring(0, zoneIdx);
    }

    private static String prefix64(byte[] ipv6Bytes) {
        StringBuilder key = new StringBuilder();
        for (int group = 0; group < PREFIX_GROUPS; group++) {
            if (group > 0) key.append(':');
            int value = ((ipv6Bytes[2 * group] & 0xff) << 8) | (ipv6Bytes[2 * group + 1] & 0xff);
            key.append(Integer.toHexString(value));
        }
        return key.append(PREFIX_SUFFIX).toString();
    }
}
