package dev.ronaldzav.byebot.antibot.check;

import java.net.InetAddress;

/** Contract for every antibot check module. */
public interface AntibotCheck {

    /** Human-readable name shown in logs. */
    String getName();

    /**
     * Tests whether this connection should be allowed.
     *
     * @param address  remote IP of the connecting client
     * @param username username provided during the handshake (may be null on pre-login)
     */
    CheckResult test(InetAddress address, String username);
}
