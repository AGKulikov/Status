# KX11 Bluetooth collector

`KX11_Bluetooth_Collect.command` makes a read-only evidence package from the
head unit over ADB. It does not remount or edit the head unit, toggle Bluetooth,
restart a Bluetooth service, clear caches, install anything, or change Android
settings.

Normal collection:

```bash
./tools/KX11_Bluetooth_Collect.command
```

Protected collection, including the Bluetooth pairing databases/link keys and
private btsnoop files when present:

```bash
./tools/KX11_Bluetooth_Collect.command --root
```

`--root` is explicit because `adb root` restarts `adbd`. It does not restart
Bluetooth, but it briefly interrupts the ADB connection. The script verifies
that adbd actually returned with UID 0 before reading protected paths.

Useful optional arguments:

```bash
./tools/KX11_Bluetooth_Collect.command \
  --serial 1234567 \
  --output "$HOME/Desktop"
```

The result is a timestamped directory, a matching `.tar.gz`, and a
`.tar.gz.sha256` sidecar. Files are created with private permissions. Treat the
entire result as sensitive: it can contain Bluetooth addresses, proprietary
system components, logs, pairing records and link keys. Do not upload it to a
public repository.

The collector includes:

- build fingerprint and Android properties;
- Bluetooth service/package dumps, service list and package paths;
- Bluetooth config directories from `system`, `vendor`, `odm`, `product` and
  `system_ext`, plus discovered `nForeBluetooth` backups;
- the system Bluetooth APK and focused ECARX/Geely/DM phone/Bluetooth APKs;
- discovered Bluetooth/vendor API JARs and native libraries;
- a Bluetooth-focused logcat slice;
- public btsnoop files when present;
- with `--root` only: protected Bluetooth data directories and private btsnoop;
- with `--root` only: the narrowly scoped `com.ts.dm.service` device database directory,
  including WAL/SHM files when present, to diagnose duplicate phone UI rows;
- remote `ls -laZ`/SHA-256 manifests and a local SHA-256 manifest.
