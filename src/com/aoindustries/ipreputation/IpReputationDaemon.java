package com.aoindustries.ipreputation;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.IpReputationSet;
import com.aoindustries.lang.ProcessResult;
import com.aoindustries.util.StringUtility;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class IpReputationDaemon {

	private static final long CHECK_INTERVAL = 30000L;
	private static final long ERROR_SLEEP = 30000L;
	private static final String SET_NAME = "xlite_users_43594";
	private static final int LOCAL_PORT = 43594;
	private static final IpReputationSet.ConfidenceType CONFIDENCE_TYPE = IpReputationSet.ConfidenceType.UNCERTAIN;
	private static final IpReputationSet.ReputationType REPUTATION_TYPE = IpReputationSet.ReputationType.GOOD;
	private static final short SCORE = 1;

	private static final Logger logger = Logger.getLogger(IpReputationDaemon.class.getName());

	private static final String[] command = {
		"netstat",
		"-n",
		"-p",
		"TCP"
	};

	public static void main(String[] args) {
		final String portEnding = ":" + LOCAL_PORT;
		final Set<Integer> uniqueIPs = new LinkedHashSet<Integer>();
		final List<IpReputationSet.AddReputation> newReputations = new ArrayList<IpReputationSet.AddReputation>();
		while(true) {
			try {
				// Get AOServConnector with settings in properties file
				AOServConnector conn = AOServConnector.getConnector(logger);
				
				// Find the reputation set
				IpReputationSet reputationSet = conn.getIpReputationSets().get(SET_NAME);
				if(reputationSet==null) throw new NullPointerException("IP Reputation Set not found: " + SET_NAME);
				while(true) {
					ProcessResult result = ProcessResult.exec(command);
					int exitVal = result.getExitVal();
					if(exitVal!=0) throw new IOException("Non-zero exit value: " + exitVal +".  stderr=" + result.getStderr());
					uniqueIPs.clear();
					for(String line : StringUtility.splitLines(result.getStdout())) {
						line = line.trim();
						if(
							line.length()>0
							&& !line.startsWith("Active Connections")
							&& !line.startsWith("Proto")
						) {
							String[] values = StringUtility.splitString(line);
							if(values.length==4) {
								String proto = values[0];
								String localAddress = values[1];
								String foreignAddress = values[2];
								String state = values[3];
								if(
									proto.equals("TCP")
									&& localAddress.endsWith(portEnding)
									&& state.equals("ESTABLISHED")
								) {
									int colonPos = foreignAddress.indexOf(':');
									if(colonPos!=-1) {
										uniqueIPs.add(IPAddress.getIntForIPAddress(foreignAddress.substring(0, colonPos)));
									} else {
										System.err.println("Warning, cannot parse line: " + line);
									}
								}
							} else {
								System.err.println("Warning, cannot parse line: " + line);
							}
						}
					}
					// Make API call to add reputations
					System.out.println("Adding " + uniqueIPs.size() + " new reputations");
					newReputations.clear();
					for(Integer ip : uniqueIPs) {
						newReputations.add(
							new IpReputationSet.AddReputation(
								ip,
								CONFIDENCE_TYPE,
								REPUTATION_TYPE,
								SCORE
							)
						);
					}
					reputationSet.addReputation(newReputations);
					// Sleep and then repeat
					Thread.sleep(CHECK_INTERVAL);
				}
			} catch(ThreadDeath TD) {
				throw TD;
			} catch(Throwable T) {
				T.printStackTrace();
				try {
					Thread.sleep(ERROR_SLEEP);
				} catch(InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
