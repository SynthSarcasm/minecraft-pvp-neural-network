#!/bin/zsh
# Assistant test server: a normal world WITHOUT the pvpmost mod (like a third-party server), only Carpet for /player bots. Port 25570.
cd "$(dirname "$0")"
exec "$(/usr/libexec/java_home -v 25)/bin/java" -Xms512M -Xmx1G -XX:+UseZGC -jar fabric-server-launch.jar nogui
