/*
 * aoserv-ipreputation - Daemon that feeds IP reputation into the AOServ Platform.
 * Copyright (C) 2012, 2013, 2018, 2020, 2021, 2022  AO Industries, Inc.
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

import com.aoapps.lang.util.PropertiesUtils;
import com.aoindustries.aoserv.client.AoservConnector;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * IP reputation daemon.  Runs any number of {@linkplain IpReputationMonitor} to gather and feed IP reputation data
 * into the AOServ Platform.
 */
public final class IpReputationDaemon {

  /** Make no instances. */
  private IpReputationDaemon() {
    throw new AssertionError();
  }

  private static final long ERROR_SLEEP = 30000L;

  private static final String CONF_RESOURCE = "/com/aoindustries/ipreputation/ipreputation.properties";

  /**
   * Runs the IP reputation daemon.
   */
  @SuppressWarnings({"UseOfSystemOutOrSystemErr", "SleepWhileInLoop", "TooBroadCatch", "UseSpecificCatch"})
  public static void main(String[] args) {
    try {
      // Each monitor will only be started once, even during retry
      final List<IpReputationMonitor> monitors = new ArrayList<>();
      boolean started = false;
      while (!started && !Thread.currentThread().isInterrupted()) {
        try {
          // Get AoservConnector with settings in properties file
          AoservConnector conn = AoservConnector.getConnector();

          // Parse the properties file and start the monitors
          Properties config = PropertiesUtils.loadFromResource(IpReputationDaemon.class, CONF_RESOURCE);

          boolean hasError = false;
          for (int num = 1; num < Integer.MAX_VALUE; num++) {
            String className = config.getProperty("ipreputation.monitor." + num + ".className");
            if (className == null) {
              break;
            }
            while (monitors.size() < num) {
              monitors.add(null);
            }
            if (monitors.get(num - 1) == null) {
              try {
                Class<? extends IpReputationMonitor> clazz = Class.forName(className).asSubclass(IpReputationMonitor.class);
                Constructor<? extends IpReputationMonitor> constructor = clazz.getConstructor(AoservConnector.class, Properties.class, Integer.TYPE);
                IpReputationMonitor monitor = constructor.newInstance(conn, config, num);
                monitor.start();
                monitors.set(num - 1, monitor);
              } catch (ThreadDeath td) {
                throw td;
              } catch (Throwable t) {
                // Catch any errors on each monitoring, starting-up those that can still start
                t.printStackTrace(System.err);
                hasError = true;
              }
            }
          }
          if (hasError) {
            try {
              Thread.sleep(ERROR_SLEEP);
            } catch (InterruptedException e) {
              e.printStackTrace(System.err);
              // Restore the interrupted status
              Thread.currentThread().interrupt();
            }
          } else {
            // Allow main method to exit
            started = true;
          }
        } catch (ThreadDeath td) {
          throw td;
        } catch (Throwable t) {
          t.printStackTrace(System.err);
          try {
            Thread.sleep(ERROR_SLEEP);
          } catch (InterruptedException e) {
            e.printStackTrace(System.err);
            // Restore the interrupted status
            Thread.currentThread().interrupt();
          }
        }
      }
      if (monitors.isEmpty()) {
        throw new IllegalStateException("No monitors defined");
      }
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      try {
        Thread.sleep(ERROR_SLEEP);
      } catch (InterruptedException e) {
        e.printStackTrace(System.err);
        // Restore the interrupted status
        Thread.currentThread().interrupt();
      }
    }
  }
}
