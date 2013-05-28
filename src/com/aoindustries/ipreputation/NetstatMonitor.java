/*
 * Copyright 2012-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.ipreputation;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.IpReputationSet;
import com.aoindustries.lang.ProcessResult;
import com.aoindustries.util.StringUtility;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

public class NetstatMonitor extends IpReputationMonitor {

    private static final Logger logger = Logger.getLogger(NetstatMonitor.class.getName());

    private static final String[] windowsCommand = {
        "netstat",
        "-n",
        "-p",
        "TCP"
    };

    private static final String[] nonWindowsCommand = {
        "netstat",
        "-n",
        "-t"
    };

    private final String setName;
    private final Set<Integer> localPorts;
    private final boolean debug;
    private final long checkInterval;
    private final long errorSleep;
    private final IpReputationSet.ConfidenceType confidenceType;
    private final IpReputationSet.ReputationType reputationType;
    private final short score;

    public NetstatMonitor(AOServConnector conn, Properties config, int num) {
        super(conn, config, num);
        // setName
        String setNameProperty = "ipreputation.monitor." + num + ".setName";
        setName = config.getProperty(setNameProperty);
        if(setName==null) throw new IllegalArgumentException(setNameProperty + " required");
        // localPorts
        String localPortsProperty = "ipreputation.monitor." + num + ".localPorts";
        String localPortsValue = config.getProperty(localPortsProperty);
        if(localPortsValue==null) throw new IllegalArgumentException(localPortsProperty + " required");
        Set<Integer> newLocalPorts = new LinkedHashSet<Integer>();
        for(String value : StringUtility.splitStringCommaSpace(localPortsValue)) {
            newLocalPorts.add(Integer.parseInt(value.trim()));
        }
        if(newLocalPorts.isEmpty()) throw new IllegalArgumentException(localPortsProperty + " required");
        localPorts = Collections.unmodifiableSet(newLocalPorts);
        // debug
        debug = Boolean.parseBoolean(
            config.getProperty(
                "ipreputation.monitor." + num + ".debug",
                "false"
            )
        );
        // checkInterval
        checkInterval = Long.parseLong(
            config.getProperty(
                "ipreputation.monitor." + num + ".checkInterval",
                "30000"
            )
        );
        // errorSleep
        errorSleep = Long.parseLong(
            config.getProperty(
                "ipreputation.monitor." + num + ".errorSleep",
                "30000"
            )
        );
        // confidenceType
        confidenceType = IpReputationSet.ConfidenceType.valueOf(
            config.getProperty(
                "ipreputation.monitor." + num + ".confidenceType",
                IpReputationSet.ConfidenceType.UNCERTAIN.name()
            ).toUpperCase(Locale.ENGLISH)
        );
        // reputationType
        reputationType = IpReputationSet.ReputationType.valueOf(
            config.getProperty(
                "ipreputation.monitor." + num + ".reputationType",
                IpReputationSet.ReputationType.GOOD.name()
            ).toUpperCase(Locale.ENGLISH)
        );
        // score
        score = Short.parseShort(
            config.getProperty(
                "ipreputation.monitor." + num + ".score",
                "1"
            )
        );
    }

    @Override
    public void start() {
        final boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows");
        Thread thread = new Thread(
            new Runnable() {
                @Override
                public void run() {
                    final Set<Integer> uniqueIPs = new LinkedHashSet<Integer>();
                    final List<IpReputationSet.AddReputation> newReputations = new ArrayList<IpReputationSet.AddReputation>();
                    while(true) {
                        try {
                            // Get AOServConnector with settings in properties file
                            AOServConnector conn = AOServConnector.getConnector(logger);

                            // Find the reputation set
                            IpReputationSet reputationSet = conn.getIpReputationSets().get(setName);
                            if(reputationSet==null) throw new NullPointerException("IP Reputation Set not found: " + setName);
                            while(true) {
                                ProcessResult result = ProcessResult.exec(isWindows ? windowsCommand : nonWindowsCommand);
                                int exitVal = result.getExitVal();
                                if(exitVal!=0) throw new IOException("Non-zero exit value: " + exitVal +".  stderr=" + result.getStderr());
                                uniqueIPs.clear();
                                for(String line : StringUtility.splitLines(result.getStdout())) {
                                    line = line.trim();
                                    if(
                                        line.length()>0
                                        && !line.startsWith("Active ")
                                        && !line.startsWith("Proto ")
                                    ) {
                                        final String proto;
                                        final String localAddress;
                                        final String foreignAddress;
                                        final String state;
                                        {
                                            String[] values = StringUtility.splitString(line);
                                            if(values.length==4) {
                                                proto = values[0];
                                                localAddress = values[1];
                                                foreignAddress = values[2];
                                                state = values[3];
                                            } else if(values.length==6) {
                                                proto = values[0];
                                                localAddress = values[3];
                                                foreignAddress = values[4];
                                                state = values[5];
                                            } else {
                                                System.err.println(num+": Warning, cannot parse line: " + line);
                                                proto = null;
                                                localAddress = null;
                                                foreignAddress = null;
                                                state = null;
                                            }
                                        }
                                        if(
                                            proto!=null
                                            && proto.equalsIgnoreCase("TCP")
                                            && state.equalsIgnoreCase("ESTABLISHED")
                                        ) {
                                            int colonPos = localAddress.lastIndexOf(':');
                                            if(colonPos!=-1) {
                                                int localPort = Integer.parseInt(localAddress.substring(colonPos+1));
                                                if(localPorts.contains(localPort)) {
                                                    colonPos = foreignAddress.lastIndexOf(':');
                                                    if(colonPos!=-1) {
                                                        String ip = foreignAddress.substring(0, colonPos);
                                                        if(debug) System.out.println(num+": Parsing " + ip);
                                                        uniqueIPs.add(IPAddress.getIntForIPAddress(ip));
                                                    } else {
                                                        System.err.println(num+": Warning, cannot parse line: " + line);
                                                    }
                                                }
                                            } else {
                                                System.err.println(num+": Warning, cannot parse line: " + line);
                                            }
                                        }
                                    }
                                }
                                // Make API call to add reputations
                                if(debug) System.out.println(num+": Adding " + uniqueIPs.size() + " new reputations to "+setName);
                                newReputations.clear();
                                for(Integer ip : uniqueIPs) {
                                    newReputations.add(
                                        new IpReputationSet.AddReputation(
                                            ip,
                                            confidenceType,
                                            reputationType,
                                            score
                                        )
                                    );
                                }
                                reputationSet.addReputation(newReputations);
                                // Sleep and then repeat
                                Thread.sleep(checkInterval);
                            }
                        } catch(ThreadDeath TD) {
                            throw TD;
                        } catch(Throwable T) {
                            T.printStackTrace(System.err);
                            try {
                                Thread.sleep(errorSleep);
                            } catch(InterruptedException e) {
                                e.printStackTrace(System.err);
                            }
                        }
                    }
                }
            },
            NetstatMonitor.class.getName()+" " + localPorts +" -> " + setName
        );
        thread.start();
    }
}
