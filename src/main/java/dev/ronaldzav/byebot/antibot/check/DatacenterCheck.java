package dev.ronaldzav.byebot.antibot.check;

import dev.ronaldzav.byebot.antibot.datacenter.DatacenterUpdater;
import dev.ronaldzav.byebot.lang.Messages;

import java.net.InetAddress;

/** Blocks connections originating from known datacenter IP ranges. */
public final class DatacenterCheck implements AntibotCheck {

    private final DatacenterUpdater updater;

    public DatacenterCheck(DatacenterUpdater updater) {
        this.updater = updater;
    }

    @Override
    public String getName() { return "datacenter"; }

    @Override
    public CheckResult test(InetAddress address, String username) {
        return updater.getList().isBlocked(address)
                ? CheckResult.block(Messages.BLOCK_DATACENTER)
                : CheckResult.PASS;
    }
}
