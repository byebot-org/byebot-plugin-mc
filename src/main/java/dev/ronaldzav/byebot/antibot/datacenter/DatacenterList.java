package dev.ronaldzav.byebot.antibot.datacenter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Immutable list of blocked IP ranges backed by parsed CIDR blocks.
 * Supports exact IPs ("1.2.3.4") and any CIDR prefix ("1.2.3.0/24", "10.0.0.0/8").
 * IPv6 addresses are never matched.
 */
public final class DatacenterList {

    private static final DatacenterList EMPTY = new DatacenterList(List.of());

    private final List<CidrBlock> blocks;

    private DatacenterList(List<CidrBlock> blocks) {
        this.blocks = blocks;
    }

    public static DatacenterList empty() { return EMPTY; }

    public static DatacenterList fromCidrs(Collection<String> cidrs) {
        List<CidrBlock> parsed = new ArrayList<>(cidrs.size());
        for (String cidr : cidrs) {
            CidrBlock block = CidrBlock.parse(cidr.strip());
            if (block != null) parsed.add(block);
        }
        return new DatacenterList(List.copyOf(parsed));
    }

    public boolean isBlocked(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        int ip = toInt(address.getAddress());
        for (CidrBlock block : blocks) {
            if (block.matches(ip)) return true;
        }
        return false;
    }

    public int     size()    { return blocks.size();    }
    public boolean isEmpty() { return blocks.isEmpty(); }

    // -------------------------------------------------------------------------

    private static int toInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private record CidrBlock(int network, int mask) {

        static CidrBlock parse(String cidr) {
            try {
                String[] parts   = cidr.split("/", 2);
                byte[]   addr    = InetAddress.getByName(parts[0]).getAddress();
                if (addr.length != 4) return null; // skip IPv6

                int prefix  = parts.length > 1 ? Integer.parseInt(parts[1].strip()) : 32;
                int ip      = ((addr[0] & 0xFF) << 24) | ((addr[1] & 0xFF) << 16)
                            | ((addr[2] & 0xFF) <<  8) |  (addr[3] & 0xFF);
                int mask    = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));

                return new CidrBlock(ip & mask, mask);
            } catch (Exception e) {
                return null;
            }
        }

        boolean matches(int ip) { return (ip & mask) == network; }
    }
}
