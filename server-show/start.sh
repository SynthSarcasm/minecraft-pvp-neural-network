#!/bin/zsh
# Show server: fights at normal speed (game: 127.0.0.1:25566, bridge to Python: 25600)
cd "$(dirname "$0")"
exec "$(/usr/libexec/java_home -v 25)/bin/java" -Xms512M -Xmx1G -XX:+UseZGC -Dpvpmost.port=25600 -jar fabric-server-launch.jar nogui
