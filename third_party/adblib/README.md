# adblib 1.3

Source: https://repo.maven.apache.org/maven2/com/tananaev/adblib/1.3/adblib-1.3-sources.jar

BSD-3-Clause; copyright Cameron Gutman. The complete license is retained here and in the APK assets.

Source archive SHA-256: `2bdbb9cc32d68d70756c3461bd2b2e97b3fa6d3728287842e20844ae59cf1078`.

Six original Java files replace the Gradle dependency. Local change: persist the OPEN acknowledgement in AdbStream and wait on that predicate in AdbConnection.open. This prevents an early OKAY notification from being lost and rejects spurious wakeups. Natro owns operation deadlines separately.
