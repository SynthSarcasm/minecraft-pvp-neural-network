#!/bin/zsh
# Training server 1 (Java 25, up to 2 GB RAM; game 25565, bridge to Python 25599)
cd "$(dirname "$0")"
exec nice -n 10 "$(/usr/libexec/java_home -v 25)/bin/java" -Xms1G -Xmx2G -XX:+UseZGC -jar fabric-server-launch.jar nogui
