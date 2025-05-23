/*
 * aoserv-ipreputation - Daemon that feeds IP reputation into the AOServ Platform.
 * Copyright (C) 2012, 2013, 2018, 2020, 2021, 2022, 2023, 2025  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-ipreputation.
 *
 * aoserv-ipreputation is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-ipreputation is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-ipreputation.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.ipreputation;

import com.aoapps.lang.ProcessResult;
import com.aoapps.lang.Strings;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.net.reputation.Set;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Monitors netstat for active connections, increasing their reputation the longer connected.
 */
@SuppressWarnings("UseOfSystemOutOrSystemErr")
public class NetstatMonitor extends IpReputationMonitor {

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
  private final java.util.Set<Integer> localPorts;
  private final boolean debug;
  private final long checkInterval;
  private final long errorSleep;
  private final Set.ConfidenceType confidenceType;
  private final Set.ReputationType reputationType;
  private final short score;

  /**
   * Creates a new netstat monitor.
   */
  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  public NetstatMonitor(AoservConnector conn, Properties config, int num) {
    super(conn, config, num);
    // setName
    String setNameProperty = "ipreputation.monitor." + num + ".setName";
    setName = config.getProperty(setNameProperty);
    if (setName == null) {
      throw new IllegalArgumentException(setNameProperty + " required");
    }
    // localPorts
    String localPortsProperty = "ipreputation.monitor." + num + ".localPorts";
    String localPortsValue = config.getProperty(localPortsProperty);
    if (localPortsValue == null) {
      throw new IllegalArgumentException(localPortsProperty + " required");
    }
    java.util.Set<Integer> newLocalPorts = new LinkedHashSet<>();
    for (String value : Strings.splitCommaSpace(localPortsValue)) {
      newLocalPorts.add(Integer.parseInt(value.trim()));
    }
    if (newLocalPorts.isEmpty()) {
      throw new IllegalArgumentException(localPortsProperty + " required");
    }
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
    confidenceType = Set.ConfidenceType.valueOf(
        config.getProperty(
            "ipreputation.monitor." + num + ".confidenceType",
            Set.ConfidenceType.UNCERTAIN.name()
        ).toUpperCase(Locale.ENGLISH)
    );
    // reputationType
    reputationType = Set.ReputationType.valueOf(
        config.getProperty(
            "ipreputation.monitor." + num + ".reputationType",
            Set.ReputationType.GOOD.name()
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
    @SuppressWarnings({"AssignmentToForLoopParameter", "UseSpecificCatch", "TooBroadCatch", "SleepWhileInLoop"})
    Thread thread = new Thread(
        () -> {
          final java.util.Set<Integer> uniqueIps = new LinkedHashSet<>();
          final List<Set.AddReputation> newReputations = new ArrayList<>();
          while (!Thread.currentThread().isInterrupted()) {
            try {
              // Get AoservConnector with settings in properties file
              AoservConnector myConn = AoservConnector.getConnector();

              // Find the reputation set
              Set reputationSet = myConn.getNet().getReputation().getSet().get(setName);
              if (reputationSet == null) {
                throw new NullPointerException("IP Reputation Set not found: " + setName);
              }
              while (!Thread.currentThread().isInterrupted()) {
                ProcessResult result = ProcessResult.exec(isWindows ? windowsCommand : nonWindowsCommand);
                int exitVal = result.getExitVal();
                if (exitVal != 0) {
                  throw new IOException("Non-zero exit value: " + exitVal + ".  stderr=" + result.getStderr());
                }
                uniqueIps.clear();
                for (String line : Strings.split(result.getStdout())) {
                  line = line.trim();
                  if (
                      line.length() > 0
                          && !line.startsWith("Active ")
                          && !line.startsWith("Proto ")
                  ) {
                    final String proto;
                    final String localAddress;
                    final String foreignAddress;
                    final String state;
                    {
                      String[] values = Strings.split(line);
                      if (values.length == 4) {
                        proto = values[0];
                        localAddress = values[1];
                        foreignAddress = values[2];
                        state = values[3];
                      } else if (values.length == 6) {
                        proto = values[0];
                        localAddress = values[3];
                        foreignAddress = values[4];
                        state = values[5];
                      } else {
                        System.err.println(num + ": Warning, cannot parse line: " + line);
                        proto = null;
                        localAddress = null;
                        foreignAddress = null;
                        state = null;
                      }
                    }
                    if (
                        "TCP".equalsIgnoreCase(proto)
                            && state != null
                            && "ESTABLISHED".equalsIgnoreCase(state)
                    ) {
                      assert localAddress != null;
                      int colonPos = localAddress.lastIndexOf(':');
                      if (colonPos != -1) {
                        int localPort = Integer.parseInt(localAddress.substring(colonPos + 1));
                        if (localPorts.contains(localPort)) {
                          assert foreignAddress != null;
                          colonPos = foreignAddress.lastIndexOf(':');
                          if (colonPos != -1) {
                            String ip = foreignAddress.substring(0, colonPos);
                            if (debug) {
                              System.out.println(num + ": Parsing " + ip);
                            }
                            uniqueIps.add(IpAddress.getIntForIpAddress(ip));
                          } else {
                            System.err.println(num + ": Warning, cannot parse line: " + line);
                          }
                        }
                      } else {
                        System.err.println(num + ": Warning, cannot parse line: " + line);
                      }
                    }
                  }
                }
                // Make API call to add reputations
                if (debug) {
                  System.out.println(num + ": Adding " + uniqueIps.size() + " new reputations to " + setName);
                }
                newReputations.clear();
                for (Integer ip : uniqueIps) {
                  newReputations.add(
                      new Set.AddReputation(
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
            } catch (InterruptedException e) {
              e.printStackTrace(System.err);
              // Restore the interrupted status
              Thread.currentThread().interrupt();
            } catch (ThreadDeath td) {
              throw td;
            } catch (Throwable t) {
              t.printStackTrace(System.err);
              try {
                Thread.sleep(errorSleep);
              } catch (InterruptedException e) {
                e.printStackTrace(System.err);
                // Restore the interrupted status
                Thread.currentThread().interrupt();
              }
            }
          }
        },
        NetstatMonitor.class.getName() + " " + localPorts + " → " + setName
    );
    thread.start();
  }
}
