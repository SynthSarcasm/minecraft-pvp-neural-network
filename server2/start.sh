#!/bin/zsh
# Training server 2 (Java 25, up to 2 GB RAM; game 25567, bridge to Python 25602)
cd "$(dirname "$0")"
exec nice -n 10 "$(/usr/libexec/java_home -v 25)/bin/java" -Xms1G -Xmx2G -XX:+UseZGC -Dpvpmost.port=25602 -jar fabric-server-launch.jar nogui
