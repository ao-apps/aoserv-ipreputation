/*
 * Copyright 2012-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.ipreputation;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.util.PropertiesUtils;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

public class IpReputationDaemon {

    private static final Logger logger = Logger.getLogger(IpReputationDaemon.class.getName());

    private static final long ERROR_SLEEP = 30000L;

    private static final String CONF_RESOURCE = "/com/aoindustries/ipreputation/ipreputation.properties";

    public static void main(String[] args) {
        try {
            // Each monitor will only be started once, even during retry
            final List<IpReputationMonitor> monitors = new ArrayList<IpReputationMonitor>();
            boolean started = false;
            while(!started) {
                try {
                    // Get AOServConnector with settings in properties file
                    AOServConnector conn = AOServConnector.getConnector(logger);

                    // Parse the properties file and start the monitors
                    Properties config = PropertiesUtils.loadFromResource(IpReputationDaemon.class, CONF_RESOURCE);

                    boolean hasError = false;
                    for(int num=1; num<Integer.MAX_VALUE; num++) {
                        String className = config.getProperty("ipreputation.monitor." + num + ".className");
                        if(className==null) break;
                        while(monitors.size()<num) monitors.add(null);
                        if(monitors.get(num-1)==null) {
                            try {
                                Class<? extends IpReputationMonitor> clazz = Class.forName(className).asSubclass(IpReputationMonitor.class);
                                Constructor<? extends IpReputationMonitor> constructor = clazz.getConstructor(AOServConnector.class, Properties.class, Integer.TYPE);
                                IpReputationMonitor monitor = constructor.newInstance(conn, config, num);
                                monitor.start();
                                monitors.set(num-1, monitor);
                            } catch(ThreadDeath TD) {
                                throw TD;
                            } catch(Throwable T) {
                                // Catch any errors on each monitoring, starting-up those that can still start
                                T.printStackTrace(System.err);
                                hasError = true;
                            }
                        }
                    }
                    if(hasError) {
                        try {
                            Thread.sleep(ERROR_SLEEP);
                        } catch(InterruptedException e) {
                            e.printStackTrace(System.err);
                        }
                    } else {
                        // Allow main method to exit
                        started = true;
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    T.printStackTrace(System.err);
                    try {
                        Thread.sleep(ERROR_SLEEP);
                    } catch(InterruptedException e) {
                        e.printStackTrace(System.err);
                    }
                }
            }
            if(monitors.isEmpty()) {
                throw new IllegalStateException("No monitors defined");
            }
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            T.printStackTrace(System.err);
            try {
                Thread.sleep(ERROR_SLEEP);
            } catch(InterruptedException e) {
                e.printStackTrace(System.err);
            }
        }
    }

    /**
     * Make no instances.
     */
    private IpReputationDaemon() {
    }
}
