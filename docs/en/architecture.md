# Code architecture

How the OkCarLink module is put together: the device-side layer (KernelSU/Magisk module, root helpers, native binaries), the headless Java daemon that speaks the CarPlay/AirPlay protocols, and the phone-side protocols each of them drives. Written for someone who wants to read the source, rebuild the module, or extend it.

## 0. What changed in the public rename

The product is the public release of an internal kit. The public identity is:

| Thing | Value |
| --- | --- |
| Product name | OkCarLink |
| Module id | `okcarlink` (`module/module.prop`) |
| Version | `1.0.0` (`versionCode=100`) |
| Author | `okcarlink` |

The **internal runtime file names are intentionally unchanged** from the internal kit: `okcar-port-ctl`, `okcar-linkd`, `okcar-carkit`, the `okcar-port-*` state files, and the `okcar-carkit` / `okcar-cast` socket names all keep their original names. Nothing underneath the new product name was renamed, and this documentation does not claim otherwise.

There is no companion APK; the whole iPhone-side port stack is one dex.

## 1. Why there is no APK

The stack used to live in an Android application: an activity drove it (`LinkActivity`) and a `Service` hosted it (`OkCarLinkService`). Running as an ordinary app cost real capability on the OEM ROMs this module targets: background activity starts, per-network MAC randomisation, `MediaProjection` consent dialogs, app-scoped capture, and install signature checks all decide what a port stack may do. The daemon header (`daemon/src/com/okcar/link/daemon/LinkDaemon.java`, top comment) records this as the reason for the current shape: the same compiled classes run as a `root` `app_process`, so there is no manifest, no activity, no permission prompt, and the privileged pieces (virtual display, encoder, input injection, Wi-Fi/Bluetooth control) are in the process itself.

Consequences of that decision, which everything else follows from:

- **One dex.** `tools/build-daemon.sh` compiles all of `daemon/src` into a single `build/okcar-link.dex` with no manifest and no APK packaging step, because a manifest is exactly the app-side contract the design avoids.
- **A supervisor, not a Service.** `module/system/bin/okcar-linkd supervise` is the process manager: it builds the right `app_process` environment, pins the daemon out of freezable cgroups, checks it every 2 s, and replaces it after a time-limited absence.
- **One source root.** App-side and daemon-side classes live in one source tree, so the two cannot drift apart (see §8).

## 2. Layer diagram (text)

```text
┌────────────────────── phone / USB + radio side ───────────────────────┐
│  Wi-Fi (SoftAP join or Wi-Fi Direct group)  Bluetooth BR/EDR  USB    │
│        │                                          │                  │
│  ┌─────▼───────── root module layer (KernelSU/Magisk) ─▼────────────┐  │
│  │ service.sh · post-fs-data.sh · uninstall.sh · action.sh          │  │
│  │ module.prop · sepolicy.rule                                     │  │
│  │ system/bin helpers: okcar-port-ctl (switches), okcar-linkd       │  │
│  │   (supervisor), okcar-port-panel (loopback UI), okcar-carkit     │  │
│  │   (USB bring-up), okcar-carwith-svc, okcar-port-check/role       │  │
│  │ native: okcar-uinputd            okcar-ffs-usbmux                │  │
│  │ webroot/ (WebUI)  panel/         system/usr/idc/*.idc            │  │
│  └───────┬──────────────────────────────┬───────────────────────────┘  │
│          │ CLASSPATH=payload/…dex        │ helper protocol              │
│          ▼                              ▼                              │
│  ┌────────── headless Java daemon (root app_process) ──────────────┐    │
│  │ com.okcar.link.daemon.LinkDaemon     entry + command surface   │    │
│  │ com.okcar.link.port.*      backends: display/capture/input/wifi│    │
│  │ com.okcar.link.wireless.*  RFCOMM listener, iAP2, Wi-Fi handoff│    │
│  │ com.okcar.link.airplay.*   AirPlay-2 pairing + RTSP + senders  │    │
│  │ com.okcar.link.iap2.*      iAP2 protocol (device role)         │    │
│  │ com.okcar.link.media.*     screen capture + H.264/HEVC + audio │    │
│  │ com.okcar.link.carkit.*    wired Java half (socket host)       │    │
│  │ com.okcar.link.stream2.*   display-2 cast pipeline             │    │
│  │ com.okcar.link.net.*       mDNS discovery / responder          │    │
│  │ com.okcar.link.cast.*      shared wire codec (app ↔ daemon)    │    │
│  └───────┬──────────────────────────────┬───────────────────────────┘    │
│          │ stdin commands (FIFO)         │ native stdio protocol        │
│          ▼                              ▼                              │
│  okcar-linkd.in ──► LinkDaemon   UinputTouchBackend ──► okcar-uinputd │
│                                        /dev/uinput virtual touchscreen  │
│  okcar-ffs-usbmux ──► abstract socket "okcar-carkit" ──► CarkitHost    │
└────────────────────────────────────────────────────────────────────────┘
            ▲                                          │
            └──────── phone-side protocols (CarPlay/AirPlay) ◀┘
```

The phone is always the **iPhone side** of the link: it plays the device role in every mode, and the head unit (or any host) is the accessory. In wireless mode it publishes the iAP2 service record and waits for the host to dial it; in wired mode the FunctionFS gadget presents an iPhone-compatible USB device to the host, which enumerates it as an Apple device and speaks usbmux. Nothing about the phone's real platform identity is ever claimed to the host — identity is negotiated per protocol (§7, and `docs/en/crypto.md` for the wired lockdown identity).

## 3. The module directory

`module/` is the KernelSU/Magisk module that ships inside the release zip (`tools/package-module.sh` lists every file that must ship; `sepolicy.rule` and `uninstall.sh` are not optional).

| Path | Role |
| --- | --- |
| `module/module.prop` | Module identity (`id=okcarlink`, `version=1.0.0`, EN+ZH description) |
| `module/post-fs-data.sh` | Early boot hook: asserts the SDP Device ID vendor (Apple) before the Bluetooth stack initialises, and runs `okcar-carwith-svc setup` |
| `module/service.sh` | Late-boot orchestration: `chmod 755` on `/data/local/tmp`, wait for boot + Bluetooth, install the `.idc`, `okcar-port-ctl apply boot`, start `okcar-linkd supervise` and the panel |
| `module/customize.sh` | Installer hook; prints the lite-variant description. Nothing is compiled at install time |
| `module/uninstall.sh` | Hands USB back to the framework first, then deletes only the module's own `/data/local/tmp` state. Uninstalling does **not** clear `/data/local/tmp`, which is why this script exists |
| `module/sepolicy.rule` | Two grants: the framework supplicant may answer our p2p0 control socket, and untrusted apps may append to the daemon FIFO under `/data/local/tmp` |
| `module/action.sh` | Module button (Magisk): starts the panel, opens its URL, posts a notification |
| `module/system/bin/okcar-linkd` | The supervisor (§4, §5) |
| `module/system/bin/okcar-port-ctl` | Single root entry point for switches, BT identity actions, and self-checks (§9) |
| `module/system/bin/okcar-carkit` | Wired bring-up and USB hand-back (§7) |
| `module/system/bin/okcar-port-panel` | Loopback (`127.0.0.1`) mini panel whose document root is `webroot/` |
| `module/system/bin/okcar-carwith-svc`, `okcar-port-check`, `okcar-port-role` | SELinux service helper, capability/platform self-check, role-node helper |
| `module/payload/okcar-link.dex` | The whole port stack (one dex); `tools/package-module.sh` stages it here |
| `module/system/usr/idc/okcar-uinput-touch.idc` | Input class for the injected virtual touchscreen; `service.sh` also copies it into `/data/system/devices/idc`, because the `/system/usr` overlay is absent where the manager does not really mount modules |
| `module/webroot/` | The WebUI (`index.html`, `app.js`, `ksu-bridge.js`, `style.css`, `cgi-bin/ctl`) |
| `module/panel/` | Front door that redirects to `webroot/` with the panel token |

## 4. Boot chain: who starts what

1. **`post-fs-data` (synchronous, early).** One job: assert the SDP Device ID vendor before the Bluetooth stack builds its SDP record, because a `setprop` after stack init only lands after a radio restart. It also runs `okcar-carwith-svc setup` (the SELinux type for the `okcar-carwith` binder name; an unlisted name falls back to `default_android_service` and no app may find it).
2. **`service.sh`.** `/data/local/tmp` is recreated with tight permissions every boot, so the script relaxes them before writing anything there. It resolves the helpers against the module directory (with a `/system/bin` fallback), installs the `.idc`, waits for `sys.boot_completed` and a live Bluetooth adapter, then runs `okcar-port-ctl apply boot`.
3. **`okcar-port-ctl apply boot`** applies only the switches that default on (the `did` `setprop`). It deliberately does **not** run a bare `apply`, which would also apply the wired switch and rebind the USB gadget during early boot; that combination has produced a boot loop on field devices, so each early hook uses its own scoped apply.
4. **`okcar-linkd supervise`** starts the dex (§5). `service.sh` also starts the loopback panel and, if the session switch is off, tells the daemon to stop. Wired auto-start runs in a detached background job, deliberately off the boot critical path, with its own repeated-auto-start guard.
5. The daemon writes a status file and serves a one-line-per-command FIFO (`/data/local/tmp/okcar-linkd.in`), which is how the WebUI, `okcar-port-ctl`, and a car app talk to it.

## 5. How the daemon starts and how it finds the module

`module/system/bin/okcar-linkd` is a shell supervisor. Its job is to make this line work every time:

```sh
CLASSPATH=$MODDIR/payload/okcar-link.dex app_process com.okcar.link.daemon.LinkDaemon
```

- **Module directory.** Derived from `$0` (with a candidate list when invoked by bare name); the result is verified by the payload file actually existing, because a bare-name invocation used to resolve the dex to a non-existent path and the daemon silently exited.
- **Environment repair.** `app_process` aborts with "ANDROID_DATA environment variable unset" from a poor-environment shell, so `ANDROID_DATA` / `ANDROID_ROOT` are filled when missing. On Android 13+ the ART variables (`BOOTCLASSPATH`, `DEX2OATBOOTCLASSPATH`, `ANDROID_ART_ROOT`, `ANDROID_I18N_ROOT`, `ANDROID_TZDATA_ROOT`, `ANDROID_ASSETS`, `ANDROID_STORAGE`) are filled by sampling one live system process, because a hand-built classpath cannot track every ROM. Only missing variables are filled: a caller that already has a complete environment is untouched.
- **From inside the dex**, `PortBootstrap.ownModuleDir()` parses `java.class.path` for the `/payload/` prefix under `/data/adb/modules` — the daemon's classpath identifies its exact module directory whatever the installed id is. `PortBootstrap.locate(name)` (`daemon/src/com/okcar/link/port/PortBootstrap.java:348`) then searches, in order: `<module>/system/bin/<name>`, `/system/bin/<name>`, `/data/local/tmp/<name>`, and the same path under every id in the fallback list `{"okcar-port", "okcar-port-lite", "okcarlink"}` (both `/data/adb/modules` and `/data/adb/modules_update`).
- **Runtime files** live in `/data/local/tmp` (the supervisor honours an `OKCAR_TMP` override used by the host self-tests). The daemon reads its commands from the FIFO written to its stdin; it writes `okcar-linkd.status` and a log for humans.

## 6. Daemon package map

One dex, all of `daemon/src`. Package roles and the classes worth reading first:

| Package | Files | Responsibility | Notable classes |
| --- | --- | --- | --- |
| `com.okcar.cast` | 2 | Shared wire codec, pure Java, no Android dependencies (§8) | `LinkCodec`, `LinkStreamReader` |
| `com.okcar.link` | 4 | Top-level state, service facade, app-side host | `LinkState`, `NativeBridge`, `OkCarLinkService`, `LinkActivity` |
| `com.okcar.link.airplay` | 26 | AirPlay-2 sender side: SRP + X25519/Ed25519 pairing, sealed channels, RTSP session machine, senders | `AirPlayPairing`, `AirPlaySession`, `WirelessAirPlayClient`, `ScreenStreamSender`, `VideoPacer`, `Ed25519Pure`, `X25519Pure` |
| `com.okcar.link.carkit` | 11 | CarWith/carwith-side bridge and the wired Java host | `CarkitServiceHost`, `CarwithBridge`, `CarwithCastClient`, `CarwithInput` |
| `com.okcar.link.castlink` | 1 | LocalServerSocket `okcar-cast`: accepts encoded frames from an APK and forwards them (§8) | `CastLinkServer` |
| `com.okcar.link.daemon` | 1 | Headless entry point and command surface | `LinkDaemon` |
| `com.okcar.link.hid` | 1 | HID report decoding (touch/key input from the head unit) | `HidReportDecoder` |
| `com.okcar.link.iap2` | 10 | iAP2 protocol, device role | `Iap2Link`, `Iap2SessionHandler`, `Iap2Frame`, `UsbIap2Transport`, `Iap2SelfTest` |
| `com.okcar.link.input` | 2 | Touch/key injection into the phone | `InputInjector`, `TouchGeometry` |
| `com.okcar.link.media` | 10 | Screen capture, H.264/HEVC encode, audio, QoS | `MirrorVideoSource`, `VideoQos`, `OpusAudioEncoder`, `FrameWatch` |
| `com.okcar.link.net` | 3 | mDNS discovery and the IPv4-only responder | `MdnsDiscovery`, `MdnsV4Responder`, `LinkNetworkMonitor` |
| `com.okcar.link.plist` | 1 | Binary plist codec (host-verified) | `BinaryPlist` |
| `com.okcar.link.port` | 19 | Root/device-facing backends and policies | `PortBootstrap`, `RootAppDisplayFactory`, `UinputTouchBackend`, `AppPriorityPolicy`, `MiuiSynergyMode`, `CastCooldown` |
| `com.okcar.link.rtsp` | 1 | RTSP framing primitives | `RtspMessage` |
| `com.okcar.link.stream2` | 11 | Display-2 cast pipeline: app on display 2 → encoder → RTP/RTSP | `Cast2Controller`, `Rtsp2Server`, `RtpPacketizer`, `SdpBuilder`, `FrameHub` |
| `com.okcar.link.usb` | 3 | USB mode/topology helpers | `UsbModeController`, `UsbTopology`, `PcIntents` |
| `com.okcar.link.util` | 1 | Logging-safe hex | `Hex` |
| `com.okcar.link.wireless` | 13 | The wireless session: RFCOMM listener, joiner, session client | `WirelessCarPlaySession`, `WifiGroupJoiner`, `WifiP2pGroupJoiner`, `AccessoryMemory`, `RfcommIap2Transport`, `WirelessSelfTest` |

Largest sources, if you want a reading order: `WirelessAirPlayClient.java` (~151 KB), `WirelessCarPlaySession.java` (~129 KB), `LinkDaemon.java` (~115 KB), `AirPlayPairingSelfTest.java` (~102 KB), `RootAppDisplayFactory.java` (~87 KB), `WifiGroupJoiner.java` (~82 KB), `OkCarLinkService.java` (~80 KB).

Two design rules recur in the package map and are worth copying:

- **Pure-Java cores.** The classes that carry protocol semantics (`com.okcar.cast`, `AirPlayPairing`, `CastCooldown`, `BinaryPlist`, the `*Pure` crypto helpers) use no `android.` imports, so the same source compiles on the host JVM and can be asserted by the `*SelfTest` classes without a device.
- **Backends behind interfaces.** `com.okcar.link.port` defines the device-facing contracts (display / capture / input / Wi-Fi) and the root implementations (`RootAppDisplayFactory`, `UinputTouchBackend`, `RootWmSizeBackend`, `ShellWifiOps`), so the session code above never branches on "am I root".

### 6.1 How the native binaries are invoked

- **`okcar-uinputd`** (source `native/uinputd/okcar-uinputd.c`, static arm64 bionic) is started as a child process by `UinputTouchBackend` and speaks a one-command-per-line stdin protocol: `D <slot> <x> <y>`, `M <slot> <x> <y>`, `U <slot>`, `K <linux-key> <0|1>`, `Q`. It opens `/dev/uinput`, registers a direct-input multitouch screen (5 slots) plus a key range, and declares the ABS range from `--width`/`--height`. `PortBootstrap` launches it with the panel's **unrotated** size, because the input stack rotates a touchscreen's raw range itself; `TouchGeometry` converts every injected point into that space.
- **`okcar-ffs-usbmux`** (sources `native/ffs-usbmux/`) is started by `okcar-carkit`, writes the FunctionFS descriptors for the Apple USBMUX interface, then serves usbmux + lockdown TLS and bridges the carkit byte pipe to the abstract socket `okcar-carkit`, where `CarkitServiceHost` listens on the Java side (§7).

## 7. The three modes at a glance

### 7.1 Wireless CarPlay (default)

1. `post-fs-data` has already asserted the SDP Device ID; `service.sh` applied the default-on switches and started the supervisor.
2. `LinkDaemon` installs root backends (`PortBootstrap.installDefaults`) and arms `WirelessCarPlaySession`, which publishes the RFCOMM service record for `00000000-deca-fade-deca-deafdecacafe`. The **accessory dials us**; that dial is the "come and connect" notification.
3. The accept loop hands each socket to `RfcommIap2Transport` + `Iap2Link` + `WirelessCarPlayDriver`; the device-role link switches (the accessory sends SYN first, acknowledge the sequence just received) are set here and restored on stop.
4. The accessory hands over its network inside iAP2 (`0x5703` / `0x4301`): SSID, passphrase, security type, channel. `WifiP2pGroupJoiner` (a real Wi-Fi Direct group) or `WifiGroupJoiner` (station + network suggestion) joins and **verifies** the association — "request accepted" is not success. Cleanup records the joined network on connect and removes it on disconnect.
5. On the joined link, `WirelessAirPlayClient` opens the AirPlay session on the accessory: `GET /info` → `POST /pair-setup` (M1–M6, SRP) → `POST /pair-verify` (M1–M4) → a sealed control channel, all through `AirPlayPairing` (pure Java). Then real-iPhone behaviour: sealed `POST /command` `{"type":"disableBluetooth"}`, a type-130 stream SETUP, and a second TCP connection to the accessory's data port, sealed with the DataStream output key, carrying raw iAP2 inside 32-byte APTransportPackage records. iAP2 moves off Bluetooth onto Wi-Fi.
6. Video: `MirrorVideoSource` mirrors the phone into a Surface and encodes H.264 (default) or HEVC with `MediaCodec`; `WirelessVideoSender`/`VideoPacer` pace the stream to the accessory. Audio: `OpusAudioEncoder` → `WirelessAudioSender`. `MdnsV4Responder` publishes an IPv4-only mDNS record on the link, because `NsdManager`'s dual-stack answers resolve to IPv6 addresses these head units cannot route.
7. Touch from the head unit arrives on the encrypted event/HID path (`HidReportDecoder` → `InputInjector`, or `UinputTouchBackend` → `okcar-uinputd`) and is injected on the phone.
8. Stop restores the link switches; a session that starved the system suspends new casts for a cooldown window (`com.okcar.link.port.CastCooldown`) while keeping the listener up, so the head unit never sees the phone disappear.

### 7.2 Wireless AirPlay mirroring

The AirPlay sender primitives are the shared half of the wireless stack; a head unit that is only an AirPlay receiver (no iAP2 credential handoff) is served by the same pairing and session machinery pointed at a discovered receiver:

1. `MdnsDiscovery.browseForAirPlay` resolves the receiver on its interface.
2. `AirPlaySession` walks the RTSP control channel as an explicit step machine — `/info` → `/auth-setup` → `/pair-setup` → `/pair-verify` → stream setup (`streams` dictionaries with their port keys) → record — and reports what the peer actually answered instead of assuming an order (the endpoints are known; the exact sequence is not).
3. `ScreenStreamSender` pushes the encoded screen to the negotiated data port, paced by `VideoPacer`; audio has its own stream.
4. There is no iAP2 and no lockdown on this path; the pairing state is the AirPlay identity (Ed25519 long-term keys), which is a different identity from the wired lockdown pair — see `docs/en/crypto.md`.
5. Stated plainly: in this headless distribution the AirPlay-sender path is reached through the wireless CarPlay session, which is built from the same shared source as `com.okcar.link`; a receiver-only deployment uses the identical pairing and sender primitives.

### 7.3 Wired CarPlay over USB (switch off by default)

1. The user turns the wired switch on (WebUI or `okcar-port-ctl switch wired on`) → `okcar-carkit start [full|ncm]`, detached before it touches `sys.usb.config`.
2. `okcar-carkit` creates the FunctionFS function, mounts it, starts `okcar-ffs-usbmux` so the descriptors exist, links `ffs` first and then NCM, and only then binds the UDC. It also installs the lockdown identity into `/data/local/tmp` (cert `0644`, key `0600`).
3. The head unit enumerates the gadget as an iPhone-compatible device and speaks usbmux: 16-byte big-endian frames, a version handshake, TCP-over-usbmux to the lockdown port.
4. `okcar-ffs-usbmux` answers the plaintext `GetValue`/`SetValue` requests first, then `StartSession` with `EnableSessionSSL=true` and upgrades to a TLS server driven through a BoringSSL BIO pair, then answers `StartService com.apple.carkit.service` with a port and **no** `EnableServiceSSL` — a raw iAP2 byte pipe.
5. Each mux connection is bridged to the abstract socket `okcar-carkit`; on the Java side `CarkitServiceHost` accepts and `UsbIap2Transport` + `Iap2Link` own the device role, with the field-verified wired link settings.
6. Media and touch use exactly the same planes as §7.1 steps 6–7; the difference is only the transport.
7. Teardown hands the port back in a fixed order (unbind ours → `sys.usb.config=adb` → configfs re-enabled), with a breaker that distinguishes a held port, a stuck USB stack, and a reboot-needed state, and a limited self-heal.

## 8. The shared wire codec (`com.okcar.cast`)

`daemon/src/com/okcar/cast/` is the one place the frame format is defined.

| Direction | Frame |
| --- | --- |
| out: video | `V pts flags len\n` + Annex-B H.264 payload |
| out: codec config | `C spsLen ppsLen\n` + payload |
| out: audio | `A pts rate ch len\n` + payload |
| in: touch | `T action x y displayId\n` |

`LinkStreamReader` is the receiving state machine: an LF-terminated header line (an overlong header is dropped and the reader resets instead of growing), a payload accumulated to exactly the declared length, and an over-cap frame skipped as bytes so the stream cannot desync. Frames are bounded (`MAX_PAYLOAD` 4 MiB, `MAX_HEADER` 512).

Why it must stay one source of truth:

- The app side and the daemon side are the same protocol. A second copy of the format — even a compatible-looking one — is how a field device ends up with a working stream and dead touch, with nothing on either side reporting an error.
- `CastLinkServer` (package `com.okcar.link.castlink`) accepts these frames from an APK pushing them over the local socket `okcar-cast` and hands them to the AirPlay screen sender with no capture or encode of its own; touch lines travel back on the same connection. The whole path is opt-in (`/data/local/tmp/okcar-cast.enable`) so the module's own mirror encode path stays the default and untouched.
- `tools/build-daemon.sh` keeps the codec in the single source root on purpose ("so the app and the daemon cannot drift apart"), and its classes are pure Java so they run on a host JVM in tests.

## 9. Runtime state, and how to extend the module

### 9.1 Runtime state

All runtime state lives in `/data/local/tmp` on a device. The file set is part of the contract between the helpers, the daemon, the WebUI, and a car app, so the names are stable:

| File | Written by / role |
| --- | --- |
| `okcar-port-switches.conf` | `okcar-port-ctl`: the seven switches (`did`, `oui`, `trim`, `linkd`, `session`, `anim`, `wired`) with their persisted state; defaults land on first run |
| `okcar-port-mode` | `okcar-port-ctl` / WebUI: the casting mode (mirror / app / carwith / carplaypp) |
| `okcar-port-app.conf` | Shared app package for the head-unit screen (the daemon remembers it because it is started with no arguments after a reboot) |
| `okcar-port-app-display.conf`, `okcar-port-app-host.conf` | App UI activity choice; whether the app's own instance may live on the phone |
| `okcar-port-video.conf`, `okcar-port-video.applied` | Bitrate/fps wishes (wired and wireless kept separate: a Wi-Fi link bears far less bitrate than USB) and the clamped values actually applied — constants at `daemon/src/com/okcar/link/media/VideoQos.java:32` and `:44` |
| `okcar-port-priority.conf` | Per-package nice priority for the casting app — constant at `daemon/src/com/okcar/link/port/AppPriorityPolicy.java:24` |
| `okcar-port-keeper.conf` | Which apps the keeper protects |
| `okcar-port-net.log`, `okcar-port.log` | Network bring-up log, module log |
| `okcar-wifi-selftest.conf` | Optional `ssid=`/`passphrase=` pair the Wi-Fi joiner's self-test reads (host self-tests point it elsewhere) |
| `bt.mac.orig` | Backup of the controller MAC taken before an OUI rewrite; `restore-bt` writes it back |
| `okcar-carkit.state`, `okcar-carkit.wanted` | Wired intent and state (started / not), including the daemon's wanted bit across restarts |
| `okcar-g1-orig.conf`, `okcar-usb-quiet` | Original gadget identity archive; marker that init's USB actions were silenced |
| `okcar-cast.enable` | Opt-in flag for the local cast link (§8) |
| `okcar-apps.tsv`, `okcar-activities.tsv` | Package/activity inventory for the WebUI picker |
| `okcar-port-lang`, `okcar-port-theme` | WebUI language and theme choices |
| `lockdown-cert.pem`, `lockdown-key.pem` | The wired lockdown identity — see `docs/en/crypto.md` |
| `okcar-linkd.*` | Supervisor bookkeeping: log, pid, the command FIFO `okcar-linkd.in`, the supervise/pid/lock markers |

`module/uninstall.sh` deletes exactly the files the module itself writes (by name) and nothing else — `/data/local/tmp` is shared with everything else on the device.

### 9.2 Adding a protocol handler

Put the protocol semantics in a new package that uses no `android.` imports (copy `com.okcar.cast` or `AirPlayPairing`), drive the transport from a `com.okcar.link.port` backend or a `wireless`/`airplay` session class, and expose it through the daemon's command surface (`LinkDaemon.handle()`, which is also the FIFO protocol) rather than reading files ad hoc. Add a `*SelfTest` class next to it; that is the pattern the existing protocol code follows and it is what makes a change reviewable without a car.

### 9.3 Adding a config switch

Add it to `module/system/bin/okcar-port-ctl`: a default in `sw_default`, handling in `sw_set`, the apply action in `apply_all`, and one verb in the dispatch. State persists in `okcar-port-switches.conf`. Decide the default deliberately: only the `did` `setprop` defaults on, because the boot path may only run actions that cannot break early boot. Longer settings (bitrate/fps, priorities) are conf files with their `CONF_PATH`-style constant plus an "applied" shadow, so the UI can show wish versus applied (see `VideoQos.java`).

### 9.4 Adding a native helper

Add `native/<name>/build.sh` following the existing static-bionic recipe (§10), stage the binary in `tools/package-module.sh` into `stage/system/bin/`, and locate it at runtime through `PortBootstrap.locate(name)` — never `/system/bin` directly. Keep it a stdin-line protocol like `okcar-uinputd`, and keep the `0755`/`0644` discipline the packaging script enforces: a `0600` helper is not executable and a `0600` `.idc` misclassifies the injected touchscreen.

## 10. Building and packaging

| Step | What it needs / does |
| --- | --- |
| `tools/build-daemon.sh` | Compiles all of `daemon/src` to `build/okcar-link.dex`. Needs an AOSP tree (`TREE`) for the platform framework jars including hidden-API jars, and a JDK 17; `javac --release 11`, then `d8 --min-api 29`. The release target is deliberately conservative: newer class file versions crashed ART on field devices |
| `native/uinputd/build.sh`, `native/ffs-usbmux/build.sh` | Assemble static bionic arm64 binaries from the same AOSP tree (no NDK in this repo): AOSP clang, bionic headers, `crtbegin_static`/`libc.a`/`crtend`, `-target aarch64-linux-android21 -static -nostdinc -nostdlib`. `okcar-ffs-usbmux` additionally links static BoringSSL from the tree, because the lockdown handshake requires TLS |
| `tools/package-module.sh` | Runs the three builds, stages `module/`, copies the dex and both native binaries in, copies the builder-supplied `lockdown/lockdown-{cert,key}.pem` into `payload/` (see `docs/en/crypto.md`), stamps `webroot/variant`, applies the mode discipline, runs the private-information leak gate over every staged file, and produces the release zip |
| `module/sepolicy.rule` | Must ship: without it the framework supplicant cannot answer our p2p0 control socket and app→daemon FIFO commands fail silently |

Why static binaries: the module must run on any Android 11+ arm64 head-unit phone without shipping or installing a runtime, and a dynamic dependency on a vendor library is exactly the thing a different ROM does not have.

## 11. Notes and honest gaps

- The Wi-Fi self-test conf and the switch-state machine are host-drivable through `OKCAR_TMP` / `OKCAR_BT_MAC`-style environment overrides; nothing about them is required to read the production flow.
- The AirPlay-receiver-only path in §7.2 is described from the shared source; which entry point arms it in a given deployment is stated plainly rather than measured.
