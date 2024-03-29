/*
 * aoserv-ipreputation - Daemon that feeds IP reputation into the AOServ Platform.
 * Copyright (C) 2012, 2013, 2020, 2021, 2022  AO Industries, Inc.
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

import com.aoindustries.aoserv.client.AoservConnector;
import java.util.Properties;

/**
 * Monitors some external resource to gain IP reputation information.
 */
public abstract class IpReputationMonitor {

  protected final AoservConnector conn;
  protected final int num;

  /**
   * All implementations must have a public constructor with these same parameters.
   */
  protected IpReputationMonitor(AoservConnector conn, Properties config, int num) {
    this.conn = conn;
    this.num = num;
  }

  /**
   * Starts this monitor, must return quickly while monitor runs in non-daemon
   * background thread.  Monitors are never stopped.
   */
  // TODO: Make abstract on next version
  public void start() {
    // Do nothing
  }
}
