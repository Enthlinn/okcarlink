package com.okcar.link.carkit;

/**
 * Which {@code ip} answers the wired AirPlay carrier route already knows how to walk past.
 *
 * <p>Pure by design: no Android import, no process, no state -- so the host gate can exercise the
 * decision without a device (see a desktop route harness drives the same table) and the daemon keeps one
 * implementation instead of a second copy in the gate.
 */
public final class IpAnswer {
    private IpAnswer() {
    }

    /**
     * True only for the one benign answer we measured: re-asserting an installed {@code rule add}
     * answers rc=2 "RTNETLINK answers: File exists".  Everything else -- a busy rule table, a
     * missing device, a syntax error, or EEXIST from any other verb -- is still a fault and must
     * keep being reported.  Null tolerates nothing.
     */
    public static boolean isExpectedEexist(String[] args, int status, String stderr) {
        return status == 2 && stderr != null && stderr.contains("File exists")
                && args != null && args.length >= 3 && "rule".equals(args[1]) && "add".equals(args[2]);
    }
}
