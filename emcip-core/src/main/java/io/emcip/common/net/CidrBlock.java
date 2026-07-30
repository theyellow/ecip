package io.emcip.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** An IPv4 or IPv6 CIDR block with a prefix-length membership test. Immutable. */
public final class CidrBlock {

    private final byte[] network;
    private final int prefixLen;

    private CidrBlock(byte[] network, int prefixLen) {
        this.network = network;
        this.prefixLen = prefixLen;
    }

    public static CidrBlock parse(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Missing prefix length in CIDR: " + cidr);
        }
        String host = cidr.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix length in CIDR: " + cidr, e);
        }
        byte[] addr;
        try {
            addr = InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid address in CIDR: " + cidr, e);
        }
        int maxBits = addr.length * 8;
        if (prefix < 0 || prefix > maxBits) {
            throw new IllegalArgumentException("Prefix out of range for CIDR: " + cidr);
        }
        return new CidrBlock(addr, prefix);
    }

    public boolean contains(InetAddress ip) {
        byte[] other = ip.getAddress();
        if (other.length != network.length) {
            return false; // different family (IPv4 vs IPv6)
        }
        int fullBytes = prefixLen / 8;
        int remainderBits = prefixLen % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != other[i]) {
                return false;
            }
        }
        if (remainderBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainderBits) & 0xFF;
        return (network[fullBytes] & mask) == (other[fullBytes] & mask);
    }

    @Override
    public String toString() {
        return Arrays.toString(network) + "/" + prefixLen;
    }
}
