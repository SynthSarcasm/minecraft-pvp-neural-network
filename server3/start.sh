#!/bin/zsh
# Exam / test server 3 (Java 25, up to 1 GB RAM; game 25568, bridge to Python 25603)
cd "$(dirname "$0")"
exec nice -n 10 "$(/usr/libexec/java_home -v 25)/bin/java" -Xms512M -Xmx1G -XX:+UseZGC -Dpvpmost.port=25603 -jar fabric-server-launch.jar nogui
