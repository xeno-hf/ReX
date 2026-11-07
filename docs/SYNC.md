# Optional desktop synchronization

The JVM receiver binds only to loopback and rejects non-loopback addresses.
Use an authenticated SSH tunnel for remote access; do not expose a raw TCP forward publicly.
The existing wire protocol is compressed, not encrypted or authenticated.
The receiver limits connections, active sessions, message lengths and channel counts.

```sh
./gradlew :rx-session:uplinkServer --args="definitions.xml recordings 127.0.0.1 6680"
```

Forward the phone-side loopback port to that receiver with your SSH client's authenticated local forwarding, then configure ReX synchronization to that loopback endpoint. Keep the tunnel active while syncing.
