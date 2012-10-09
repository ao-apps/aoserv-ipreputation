Windows Installation:

TODO

Linux Installation:

1) useradd -d /var/opt/ipreputation --no-create-home --system --shell /bin/false --user-group ipreputation
2) cd /opt
3) tar xzf ipreputation.tgz
4) chown -R root.root ipreputation
5) apt-get install openjdk-6-jre-headless
6) chmod 750 /opt/ipreputation
7) chgrp ipreputation /opt/ipreputation
8) cp -a /opt/ipreputation/conf/com/aoindustries/aoserv/client/aoserv-client.properties.template /opt/ipreputation/conf/com/aoindustries/aoserv/client/aoserv-client.properties
9) vi /opt/ipreputation/conf/com/aoindustries/aoserv/client/aoserv-client.properties
10) cp -a /opt/ipreputation/conf/com/aoindustries/ipreputation/ipreputation.properties.template /opt/ipreputation/conf/com/aoindustries/ipreputation/ipreputation.properties
11) vi /opt/ipreputation/conf/com/aoindustries/ipreputation/ipreputation.properties
12) mkdir -m 750 /var/opt/ipreputation
13) chown root.ipreputation /var/opt/ipreputation
14) chmod 700 /opt/ipreputation/init.d/ipreputation
15) ln -s /opt/ipreputation/init.d/ipreputation /etc/init.d/ipreputation
16) update-rc.d ipreputation defaults
17) /etc/init.d/ipreputation start
18) tail -n 1000 -f /var/opt/ipreputation/ipreputation.log
