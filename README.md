# Haval MQTT Gateway

Standalone Android companion app for the Haval head unit. It consumes the existing Haval App Tool read-only broadcasts and publishes selected vehicle telemetry to an MQTT broker. It does not read CAN/AIDL directly and does not send vehicle commands.

## Requirements

- Android 9 / API 28 or newer (built with compile SDK 36).
- Haval App Tool installed and its vehicle service initialized on the same head unit.
- An MQTT broker reachable from the head unit. Configure TLS with `ssl://host:8883`. Anonymous `tcp://` is allowed only on a trusted local network and is shown as insecure; the gateway rejects TCP when a username or password is configured.

## Setup

1. Install `mqtt-gateway-debug.apk` on the same head unit as Haval App Tool.
2. Open **Haval MQTT Gateway** and enter the broker URI, optional username/password, and topic prefix.
3. Use a unique broker credential for this head unit and grant it publish-only ACLs. The app stores the password encrypted using Android Keystore.
4. Tap **Salvar e iniciar gateway**. Enable auto-start only if the gateway should run after reboots.
5. Check the notification/status. The gateway waits for `app.impulse.api_version=1`, requests `com.haval.vehicle.REQUEST_SNAPSHOT`, and consumes `com.haval.vehicle.EVENT_CHANGED`.

Example topic:

```text
haval/telemetry/<vehicle-id>/car.basic.vehicle_speed
```

Payloads are JSON with `schema_version`, pseudonymous `vehicle_id`, UTC receive timestamp, sequence, source API version, key, raw string value, and `source_trust: "unauthenticated_local_broadcast"`. MQTT QoS is 1; messages are not retained. The app does not subscribe to MQTT command topics.

## Data/privacy limits

- Export scope is `car.*` and `haval.power.*`, with filters for VIN, DMS/FRS/OMS, `app.*`, `sys.*`, keys ending in `_action`, `_command`, `_request`, or `_oneshot`, `.request_` keys, and known command-only keys such as `car.beantechs.sota_start`. The producer does not label events with read/write semantics; this is a conservative name-based filter, not a complete typed allowlist. The gateway only publishes MQTT messages and never subscribes to command topics.
- Coverage is limited to keys emitted by the source app's public bus. It includes its default monitored keys and any user-selected/dynamically monitored keys, but does not add new source subscriptions.
- Per-wheel TPMS and TSR reads used only by the theme WebView are not automatically included in the public broadcast. The source app must expose these through a versioned read-only API/broadcast if they are required.
- The source broadcast is public and has no sender authentication. Do not use this feed for safety-critical decisions. Treat it as untrusted telemetry.
- While offline, the gateway coalesces to the latest value per key in an in-memory queue capped at 2,048 keys and a conservative 1 MiB payload budget, and requests a fresh snapshot after reconnect. It is not a durable event-history store; transitions during an outage may be missed. Input is capped at 500 broadcasts per second; excess events are dropped.

## Build and tests

```sh
./gradlew :mqtt-gateway:testDebugUnitTest
./gradlew :mqtt-gateway:assembleDebug
```

The debug APK is produced at `mqtt-gateway/build/outputs/apk/debug/mqtt-gateway-debug.apk`.
