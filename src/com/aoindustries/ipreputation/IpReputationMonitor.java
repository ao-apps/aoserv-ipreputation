/*
 * Copyright 2012 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.ipreputation;

import com.aoindustries.aoserv.client.AOServConnector;
import java.util.Properties;

abstract public class IpReputationMonitor {

    final protected AOServConnector conn;

    /**
     * All implementations must have a public constructor with these same parameters.
     */
    protected IpReputationMonitor(AOServConnector conn, Properties config, int num) {
        this.conn = conn;
    }

    /**
     * Starts this monitor, must return quickly while monitor runs in non-daemon
     * background thread.  Monitors are never stopped.
     *
     * @throw  Exception if failed to start.
     */
    public void start() {
    }
}
