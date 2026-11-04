# KernelSU module layout

The installable unit is one KernelSU/Magisk module directory staged from
`module/` and packaged as `build/okcarlink-1.0.0.zip`. The directory holds four
kinds of file: the boot hooks and metadata the root manager itself executes, a
handful of `system/bin` helpers reachable once the manager overlays the module, a
webroot that is the only user interface, and the payload directory carrying the
dex and the lockdown identity.

> **Names.** The module id is now `okcarlink`, but every on-device runtime name
> — `okcar-port-ctl`, `okcar-linkd`, the `okcar-port-*` files under
> `/data/local/tmp` — is intentionally unchanged from the internal kit. The
> WebUI still probes the old install ids `okcar-port` and `okcar-port-lite` so an
> existing install keeps working.

## 1. File-by-file

| Path | Purpose | When it runs |
| --- | --- | --- |
| `module/module.prop` | Module identity and description | Read by the manager at install and list time |
| `module/customize.sh` | Installer hook: prints what this build is | Once, while the zip is being flashed |
| `module/post-fs-data.sh` | Early-boot hook: prepares the runtime directory, sets the SDP Device ID | Every boot, before `late_start` |
| `module/service.sh` | Late-boot hook: Bluetooth prep, daemon launch, panel start | Every boot, at `late_start` |
| `module/uninstall.sh` | Hands USB back and wipes the module's runtime state | When the manager removes the module |
| `module/sepolicy.rule` | Three SELinux grants the daemon needs | Once, when the manager installs policy |
| `module/action.sh` | The module's action button | When the user taps it in Magisk |
| `module/system/bin/okcar-linkd` | Daemon launcher and supervisor | Started by `service.sh`; also `start`/`stop`/`status`/`send` from the WebUI |
| `module/system/bin/okcar-port-ctl` | The privileged CLI for switches, Bluetooth and USB actions | Called by `post-fs-data.sh`, `service.sh`, the CGI, the WebUI and operators |
| `module/system/bin/okcar-port-check` | Diagnostics entry point: prints where the module is and what its state is | On demand |
| `module/system/bin/okcar-port-panel` | Local busybox `httpd` panel on `127.0.0.1:8088` | Started by `service.sh` and `action.sh` |
| `module/system/bin/okcar-carkit` | The wired-link helper: gadget rebuild, lockdown identity, FunctionFS server | Called by the wired switch |
| `module/system/bin/okcar-role` | Keeps the Type-C/USB role pinned at 1 Hz | Started by the wired switch |
| `module/system/bin/okcar-carwith-svc` | Gives the CarWith bridge service its own SELinux type | `setup` from `post-fs-data.sh`; detached watchdog afterwards |
| `module/system/usr/idc/okcar-uinput-touch.idc` | Classifies the virtual touch device as a touch screen | Installed into `/data/system/devices/idc` by `service.sh` |
| `module/webroot/` | The WebUI (`index.html`, `app.js`, `ksu-bridge.js`, `style.css`, `variant`, `cgi-bin/ctl`) | Served by the manager or the panel |
| `module/panel/` | Redirect page and CGI forwarder for the `-h module-dir` panel layout | Served by the panel |
| `module/payload/` | The dex and the lockdown pair, added by the packaging script | Read by `okcar-linkd` / `okcar-carkit` |

`module/README.md` is a short bilingual note: what the module is and where its
documentation lives. The packager adds it to the zip when it exists.

## 2. Boot sequence

### 2.1 `post-fs-data.sh` — earliest hook

This hook runs synchronously and blocks the rest of boot, so it does almost
nothing heavy: it creates `/data/local/tmp` and re-`chmod`s it to `755` (the
kernel recreates the directory `0771 shell:shell` every boot and the confs, FIFO
and status file live there), logs which `okcar-port-ctl` it resolved and whether
the module's `system` partition is overlaid, installs the CarWith bridge's
SELinux type, and runs `okcar-port-ctl apply early` — which sets only
`bluetooth.device_id.vendor_id=0x004C` (Apple) so the Bluetooth stack builds the
SDP Device ID record correctly at initialisation. Nothing else may live here: a
bare `apply` here also ran the wired switch's gadget rebind before
`boot_completed`, which cost eight `EBUSY` bind attempts, left the USB port with
a dirty Android identity and then wedged a retry in a configfs `D` state — the
symptom was "plug it in and nothing happens, plug it in many times and it still
will not connect".

### 2.2 `service.sh` — late boot

At `late_start`, in order:

1. On the special `a13` build only, installs the Bluetooth-proxy companion app
   named in `payload/bt-proxy.name`. The mainline release ships no such file, so
   the code is inert.
2. Installs the `.idc` file into `/data/system/devices/idc`, because
   `/system/usr/idc` exists only when the manager really overlays the module —
   and without that classification the uinput virtual touch device is not a
   touch screen as far as the framework is concerned.
3. Waits for `sys.boot_completed` (up to 180 s) and then for the Bluetooth stack
   (`bluetooth_address` present plus a live Bluetooth process, up to 120 s).
   Starting earlier leaves the RFCOMM listener unpublished for minutes.
4. Runs `okcar-port-ctl apply boot`: `did`, `oui` and `trim`, with a Bluetooth
   radio reload only when something actually changed. It is never a bare
   `apply` — a bare apply would have applied the wired switch and rebound the
   USB gadget at boot, which produced a reboot loop in October 2026 (reboot →
   USB rebind → adb and network gone → reboot).
5. Reads the opt-out marker `disable-linkd` and the `linkd` switch, and exits if
   either says the daemon should stay dark this boot; then exits if the payload
   dex is missing.
6. Launches the daemon detached: `setsid "$LINKD" supervise &`. The mode is not
   passed on the command line; it is read from `/data/local/tmp/okcar-port-mode`
   by `okcar-linkd`, which writes the default value when the file is missing.
   Hardcoding it previously clobbered the user's own app-cast choice.
7. Starts the local panel (`127.0.0.1:8088`). Without a busybox that provides
   `httpd` it errors out and boot continues.
8. If the session switch is off, sends `stop` to the daemon so it does not
   listen, and logs the daemon status head after 3 s.
9. Finally spawns the wired boot autostart detached
   (`setsid sh -c "'okcar-port-ctl' wired-boot" &`) — deliberately never on the
   boot critical path, and with its own guard: two consecutive autostarts
   followed by a reboot permanently disable it, which is the balance between the
   October 2026 reboot loop and the opposite failure where boot never touched USB
   and a cable plugged in many times never connected.

### 2.3 The daemon launch

`okcar-linkd supervise` resolves the module directory from its own location (with
fallbacks over the four install layouts after verifying the payload exists),
fills the ART environment — the bootstrap and dexeoat classpaths are sampled from
a live zygote on Android 13+ because ART aborts with an empty classpath — pins
the daemon to a system cgroup so the freezer cannot kill it when the screen turns
off, creates the command FIFO, and execs
`com.okcar.link.daemon.LinkDaemon` inside `app_process` as root. It then watches
the daemon every 2 s: it restarts when the daemon has been absent for about
12 s, re-pins the cgroup, and reconciles mode drift between
`okcar-port-mode` and the daemon's own `--mode` (two consecutive drift readings
restart the daemon). Exactly one supervisor exists at a time, enforced with an
atomic `mkdir` lock whose pid file is the only liveness source.

### 2.4 `uninstall.sh`

Uninstalling the module does not wipe `/data/local/tmp`, so a reinstall used to
inherit the previous user's choices — the measured failure was a machine whose
`okcar-port-mode` still said `app` while the default had moved to `mirror`. The
order matters and is the whole point of the script:

1. **Hand USB back first**: `okcar-carkit stop` (unbinds our gadget, restores the
   framework's USB identity so init rebuilds adb), then
   `okcar-port-ctl switch wired off` and `switch linkd off`.
2. **Then kill**: the daemon, the FunctionFS server and the carkit guard, each
   wrapped in `timeout`. An exception in the USB stack blocks configfs reads
   forever — measured with the guards' `cat` stuck in `D` state and the manager
   spinner never ending — so an unbounded `pkill` can hang uninstallation.
3. **Then wipe the module's state**, prefix-scoped to `okcar-port-*` and
   `okcar-*` so another module's files in `/data/local/tmp` are never touched:
   the switches file, the mode file, the carkit state and guard pid, the USB
   identity archive, the logs, the Bluetooth originals, the animation archive
   and a sweep of `okcar-port-*.conf` — including
   `okcar-port-video.applied`, which must be cleared or the panel shows the
   previous occupant's applied values after a reinstall.

### 2.5 `customize.sh`

The installer hook only sets `SKIPUNZIP=0` and prints what this build is: a
wireless CarPlay test build that may stutter, drop frames or crash; no APK, just
the payload dex plus the launcher; it does one thing — mirror the phone screen to
the head unit, with the car side on its own link app — and adds no debug channel;
control and logs live in the manager WebUI; Bluetooth changes are manual switches
with an explicit restore; and bug reports should name the model, ROM and Android
version plus the two logs under `/data/local/tmp`.

### 2.6 `sepolicy.rule`

Three unrelated grants, written in the space-separated statement syntax: the
file is split on commas before comments are stripped, so a comma inside a
comment fails the whole file. The grants are (1) a dgram/stream socket channel
from `wpa_supplicant` to the module, so the framework's own supplicant can answer
a client on `p2p0` that is outside the framework domain; (2) file and FIFO access
to `/data/local/tmp` for ordinary apps, so the car-screen app can append its
`app` / `app-ui` commands to the daemon's FIFO without `EACCES`; (3) a private
service type for the CarWith bridge service, so `getService('okcar-carwith')`
resolves instead of falling back to the default service type. The stated
trade-off: these rules let any app read and write under `/data/local/tmp` —
that is what a root module talking to an ordinary app over a file in
`/data/local/tmp` costs.

## 3. Helper reference

| Helper | Lines (approx.) | Responsibility |
| --- | --- | --- |
| `okcar-linkd` | 780 | Runs the dex under `app_process` as root; `start`/`stop`/`status`/`send`/`supervise`/`restart`; cgroup pinning, FIFO transport, mode reconciliation, animation handling |
| `okcar-port-ctl` | 870 | Every privileged step: switch state, staged applies, Bluetooth primitives, wired gadget commands, capability report, permission grants |
| `okcar-port-check` | 82 | The one command to run when something is wrong: resolves the module directory including the `modules_update` case, warns when the module has never booted, tails the boot-hook log, prints the daemon and switch state |
| `okcar-port-panel` | 64 | Stops and starts the loopback busybox `httpd`, guards it with a token, prints the URL and posts a notification |
| `okcar-carkit` | 1700 | The wired helper: archives the framework USB identity, builds the Apple gadget, installs the lockdown pair into `/data/local/tmp`, runs the FunctionFS server, and reports which gadget claims to be Apple |
| `okcar-role` | 92 | Pins this phone's USB role at 1 Hz by writing the Type-C data role, so a C-to-C cable cannot flip the roles back and forth |
| `okcar-carwith-svc` | 212 | Mounts a labelled copy of the live `plat_service_contexts` over the original and reloads the policy so the bridge service type resolves; a watchdog unmounts if servicemanager is replaced |
| `okcar-uinputd` (`native/uinputd`) | — | Static Bionic helper that owns the virtual input device and injects touch and key events |
| `okcar-ffs-usbmux` (`native/ffs-usbmux`) | — | Static helper that runs the FunctionFS gadget, the usbmux service and the lockdown handshake for the wired link |

## 4. Switches and safety

All seven switches are privileged: every one of them needs root, because writing
`/data/local/tmp` state is not the action — the action is a `setprop`, a USB
gadget rebind, a cgroup write or a process restart. The states live in
`/data/local/tmp/okcar-port-switches.conf` and survive reboots; a reboot with a
switch off simply does not re-apply that switch's effect.

| Switch | Default | Needs root because it… | Touches USB? | On reboot |
| --- | --- | --- | --- | --- |
| `did` | on | writes a system property | no | re-applied by `service.sh` (`apply boot`) |
| `oui` | off | modifies the Bluetooth address and reloads the radio | no | re-applied by `service.sh`, because the controller file is rebuilt from NV at Bluetooth init |
| `trim` | off | switches off Bluetooth profiles and reloads the radio | no | re-applied by `service.sh`; can be turned off after pairing |
| `linkd` | on | starts or stops the daemon process | no | off suppresses the boot start; the WebUI switch starts it with no reboot |
| `session` | on | stops the RFCOMM listener inside a running daemon | no | read at daemon start |
| `anim` | off | forces animation scales while mirroring and restores them after | no | off is inert; on applies during the next mirror session |
| `wired` | off | rebinds the whole USB gadget as an Apple device | **yes** | never applied at boot; only the detached wired-boot autostart may, and it self-disables after two failed autostart-then-reboot cycles |

Two safety behaviours belong to the switch layer rather than to one switch:
the daemon and the session are bound together (the `linkd` and `session`
switches both stand and fall together), and the master toggle in the WebUI
remembers which switches were on before it turned them all off, in a file, so it
can restore the same set later or after a reboot.

## 5. Scope of this release

The `webroot/variant` marker (`lite`) is the packaging script's stamp, and the
WebUI reads it to hide rows whose companion app does not exist here; the same
marker decides the daemon's default mode when the mode file is missing:
`mirror`. Nothing else in the module directory is conditional on it.
