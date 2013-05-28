/*
 * Copyright 2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.ipreputation;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.IpReputationSet;
import com.aoindustries.io.LogFollower;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses log files line-by-line with regular expression to extract reputation.
 */
public class LogMonitor extends IpReputationMonitor {

    private static final Logger logger = Logger.getLogger(LogMonitor.class.getName());

    private final String setName;
    private final String path;
    private final Pattern pattern;
    private final int group;
    private final boolean debug;
    private final int pollInterval;
    private final int commitInterval;
    private final boolean coalesce;
    private final Charset charset;
    private final long errorSleep;
    private final IpReputationSet.ConfidenceType confidenceType;
    private final IpReputationSet.ReputationType reputationType;
    private final Short score;

    public LogMonitor(AOServConnector conn, Properties config, int num) {
        super(conn, config, num);
        // setName
        String setNameProperty = "ipreputation.monitor." + num + ".setName";
        setName = config.getProperty(setNameProperty);
        if(setName==null) throw new IllegalArgumentException(setNameProperty + " required");
        // path
        String pathProperty = "ipreputation.monitor." + num + ".path";
        path = config.getProperty(pathProperty);
        if(path==null) throw new IllegalArgumentException(pathProperty + " required");
        // pattern
        String patternProperty = "ipreputation.monitor." + num + ".pattern";
        String patternValue = config.getProperty(patternProperty);
        if(patternValue==null) throw new IllegalArgumentException(patternProperty + " required");
        pattern = Pattern.compile(patternValue);
        // group
        group = Integer.parseInt(
            config.getProperty(
                "ipreputation.monitor." + num + ".group",
                "0"
            )
        );
        // debug
        debug = Boolean.parseBoolean(
            config.getProperty(
                "ipreputation.monitor." + num + ".debug",
                "false"
            )
        );
        // pollInterval
        pollInterval = Integer.parseInt(
            config.getProperty(
                "ipreputation.monitor." + num + ".pollInterval",
                "5000"
            )
        );
        // commitInterval
        commitInterval = Integer.parseInt(
            config.getProperty(
                "ipreputation.monitor." + num + ".commitInterval",
                "30000"
            )
        );
        // coalesce
        coalesce = Boolean.parseBoolean(
            config.getProperty(
                "ipreputation.monitor." + num + ".coalesce",
                "false"
            )
        );
        // charset
        String charsetValue = config.getProperty("ipreputation.monitor." + num + ".charset");
        charset = charsetValue==null ? Charset.defaultCharset() : Charset.forName(charsetValue);
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
        score = Short.valueOf(
            config.getProperty(
                "ipreputation.monitor." + num + ".score",
                "1"
            )
        );
    }

    /**
     * The information passed between thread reading log file to thread committing to master.
     */
    private static class QueueEntry {
        private final int ip;

        private QueueEntry(int ip) {
            this.ip = ip;
        }
    }

    /**
     * The thread that reads from the log file and puts into the queue.
     */
    private class LogReaderThread extends Thread {

        private final List<QueueEntry> buffer;

        private LogReaderThread(List<QueueEntry> buffer) {
            super(LogMonitor.class.getName()+"(\"" + path +"\" -> \"" + setName+"\").LogReaderThread");
            this.buffer = buffer;
        }

        @Override
        public void run() {
            int matchCount = 0;
            while(true) {
                try {
                    // Open the log for following
                    BufferedReader log = new BufferedReader(new InputStreamReader(new BufferedInputStream(new LogFollower(path, pollInterval)), charset));
                    try {
                        // Read one line at a time
                        String line;
                        while((line=log.readLine())!=null) {
                            Matcher m = pattern.matcher(line);
                            if(m.matches()) {
                                String matched = m.group(group);
                                matchCount++;
                                if(debug) System.out.println(num+": Matched "+matchCount+": "+matched);
                                try {
                                    QueueEntry entry = new QueueEntry(
                                        IPAddress.getIntForIPAddress(matched)
                                    );
                                    synchronized(buffer) {
                                        buffer.add(entry);
                                    }
                                } catch(IllegalArgumentException e) {
                                    e.printStackTrace(System.err);
                                }
                            }
                        }
                    } finally {
                        log.close();
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
    }

    private class CommitThread extends Thread {

        private final List<QueueEntry> buffer;

        private CommitThread(List<QueueEntry> buffer) {
            super(LogMonitor.class.getName()+"(\"" + path +"\" -> \"" + setName+"\").CommitThread");
            this.buffer = buffer;
        }

        @Override
        public void run() {
            final Map<Integer,Short> ipScores = new LinkedHashMap<Integer,Short>();
            final List<QueueEntry> snapshot = new ArrayList<QueueEntry>();
            final List<IpReputationSet.AddReputation> newReputations = new ArrayList<IpReputationSet.AddReputation>();
            while(true) {
                try {
                    // Get AOServConnector with settings in properties file
                    AOServConnector conn = AOServConnector.getConnector(logger);

                    // Find the reputation set
                    IpReputationSet reputationSet = conn.getIpReputationSets().get(setName);
                    if(reputationSet==null) throw new NullPointerException("IP Reputation Set not found: " + setName);

                    while(true) {
                        // Sleep for commit interval
                        Thread.sleep(commitInterval);

                        // Get snapshot and clear buffer
                        snapshot.clear();
                        synchronized(buffer) {
                            snapshot.addAll(buffer);
                            buffer.clear();
                        }

                        // Create new reputation entries, grouping by ip and summing scores (unless coalesce, then score once)
                        ipScores.clear();
                        for(QueueEntry entry : snapshot) {
                            Integer ip = entry.ip;
                            Short existingScore = ipScores.get(ip);
                            if(existingScore==null) {
                                ipScores.put(ip, score);
                            } else if(!coalesce) {
                                int newScore = existingScore + score;
                                ipScores.put(ip, newScore>Short.MAX_VALUE ? Short.MAX_VALUE : (short)newScore);
                            }
                        }

                        // Make API call to add reputations
                        if(debug) System.out.println(num+": Adding " + ipScores.size() + " new reputations to "+setName);
                        newReputations.clear();
                        for(Map.Entry<Integer,Short> entry : ipScores.entrySet()) {
                            newReputations.add(
                                new IpReputationSet.AddReputation(
                                    entry.getKey(),
                                    confidenceType,
                                    reputationType,
                                    entry.getValue()
                                )
                            );
                        }
                        reputationSet.addReputation(newReputations);
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
    }

    @Override
    public void start() {
        List<QueueEntry> buffer = new ArrayList<QueueEntry>();
        new LogReaderThread(buffer).start();
        new CommitThread(buffer).start();
    }
}
