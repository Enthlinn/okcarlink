# WebUI and control surface

Three control surfaces sit on one pair of module-side entry points: `okcar-port-ctl`
owns configuration and the privileged side effects (Bluetooth switches, USB gadget
rebinding, cgroup pinning, permissions), and `okcar-linkd` owns the daemon process
and its `start` / `stop` / `status` / `send` / `supervise` / `restart` verbs.
Paths in backticks are repo-relative; paths starting with a slash are on-device,
under `/data/local/tmp`. The WebUI is a rendering of the CLI, not a second
implementation.

> **Names.** The internal runtime names (`okcar-port-ctl`, `okcar-linkd`, the
> `okcar-port-*` files) are intentionally unchanged from the internal kit; only the module id is now `okcarlink`.

## 1. The three control surfaces

| Surface | Served by | Transport | Intended user |
| --- | --- | --- | --- |
| Manager WebUI | `module/webroot/` (`index.html`, `app.js`) | KernelSU/Magisk manager WebView bridge | KernelSU users (recommended) |
| Local panel | `okcar-port-panel` (busybox `httpd` on `127.0.0.1:8088`) | `module/webroot/cgi-bin/ctl` + `ksu-bridge.js` | Magisk users, or any install without a manager WebUI |
| CLI | `okcar-port-ctl`, `okcar-linkd` | plain root shell | operators, scripts, bug reports |

**Manager WebUI.** The root manager loads `module/webroot/index.html` in a WebView
whose origin is the module directory and injects a bridge named `kernelsu`
exposing `exec` — the only path by which the page runs privileged commands, which
is why it is inert in a plain browser unless the local panel runs. The skeleton holds
status lines, the category list and a detail page, mode tabs, pickers and a log panel.

**Local panel.** `okcar-port-panel start` runs
`busybox httpd -f -p 127.0.0.1:8088 -h <module>/webroot` (`PORT` /
`OKCAR_PANEL_PORT` override the port; the address is always loopback). The URL is
`http://127.0.0.1:8088/?token=<token>` — 16 random hex bytes in
`/data/local/tmp/okcar-port-panel.token`, printed by `okcar-port-panel url`. The
same `webroot` is served, so the two surfaces differ only in transport, and
`module/action.sh` opens the URL and posts it as a notification for Magisk users
who would otherwise never see it.

**`ksu-bridge.js`** makes the page work in a plain browser against the panel by
implementing the three-argument `kernelsu.exec(command, optionsJson, callbackName)`
over the CGI: the command becomes
`fetch('cgi-bin/ctl?do=exec&token=...&b64=<base64url(cmd)>')` and the result
reaches `window[callbackName](errno, stdout, stderr)`. base64url keeps `+`, `/` and `=` from
mangling, the payload is UTF-8 so Chinese commands survive, and the shim installs
itself only when no native bridge is present: overriding one would route commands
through an HTTP path that does not exist in the WebView.

## 2. `okcar-port-ctl` — the privileged CLI

One script, the single root entry point for every privileged step, at
`module/system/bin/okcar-port-ctl` (also `/system/bin/okcar-port-ctl` under an overlay).

### 2.1 Verbs

| Verb | What it does |
| --- | --- |
| `switches` / `switch-state <key>` / `switch <key> on｜off` | Print all switches as `<key> <state> default=<state> - description`, or set one of the seven and apply it |
| `apply [boot｜early｜manual]`, `wired-boot` | Apply the switches in one stage (2.3); the detached wired-boot autostart |
| `caps` / `status` / `where` / `platform` | USB and capability facts (manager, configfs, UDC, Type-C node, uinput, functionfs, kernel modules), the status head, module paths, overlay state, Android version, root provider |
| `oui` / `spoof-bt` / `restore-bt` / `bt-did` / `bt-name` / `bt-sdp` | Historical Bluetooth verbs still used by the WebUI: MAC OUI, SDP Device ID, device name, SDP trim and restores |
| `wm <WxH>` / `grant` / `net-recover` / `stall-clear` | Display size hint, grant the daemon's runtime permissions, bounce Wi-Fi (always detached), clear a long stall window |

An unknown verb prints the script's usage block — a usable reference on a phone.

### 2.2 The seven switches

State lives in `/data/local/tmp/okcar-port-switches.conf`, one `key=value` line per
switch, written on first read so nothing is a hidden in-memory default, and rewritten
atomically under a `mkdir` lock in a fixed key order — the boot hook, the WebUI and
manual commands all write concurrently.

| Switch | Default | Effect and safety |
| --- | --- | --- |
| `did` | on | Advertise the SDP Device ID vendor as Apple (`bluetooth.device_id.vendor_id=0x004C`): one `setprop`, no radio cycle, the only default-on Bluetooth action |
| `oui` | off | Rewrite the Bluetooth MAC OUI segment to Apple. **Changes the MAC** — every pairing is invalidated and the controller file is rebuilt from NV at Bluetooth init, so it must be re-applied every boot |
| `trim` | off | Switch off five unused Bluetooth profile records so the SDP response fits the head unit's discovery database (~1600 B). Pairing-phase only |
| `linkd` | on | The daemon process; off stops it and suppresses the boot start |
| `session` | on | The wireless session; off leaves the process running but not listening |
| `anim` | off | Whether mirror sessions force animation scales to 0; off restores the saved scales immediately |
| `wired` | off | This phone re-enumerates over USB as an Apple device (`05ac:12a8`, USBMUX + NCM). **Takes the whole USB port** — USB adb stops, TCP adb on 5555 is kept. The only switch that reconfigures USB |

The defaults follow one logic: `did` is a single reversible property head units
use to select the phone, so it is on; `oui` and `wired` each cost the user
something dear, so they are off and confirm-protected; `anim` moved to off after a
default-on value silently took effect on builds whose panel had no row for it.

### 2.3 Apply stages

| Stage | Applies | Called by |
| --- | --- | --- |
| `early` | `did` only | `module/post-fs-data.sh`, before the Bluetooth stack initialises |
| `boot` | `did`, `oui`, `trim`, radio reload only if something changed | `module/service.sh` |
| `manual` | everything, including `wired` | the WebUI, `action.sh`, an operator |

A bare `apply` means `manual`, wired switch included: the boot hooks always name their stage.

## 3. `okcar-linkd` — the daemon interface

The launcher and supervisor runs the single dex `payload/okcar-link.dex` under
`app_process` as root, pins the daemon to a system cgroup so the freezer cannot
kill it, creates the command FIFO and reconciles mode drift. `send` persists first
and speaks second: every choice is written to its conf file before the command goes
through the FIFO (the boot hook starts the daemon with no arguments, so the next
launch must reattach the same choice), and the FIFO is opened read-write because a
plain open blocks forever while no reader exists.

| `send` command | Meaning |
| --- | --- |
| `mode mirror` / `desktop` | Modes and the trip home (the WebUI writes the mode file and restarts — 5.7) |
| `app <pkg>` / `app-ui <pkg>/<activity>` | App to cast, or its activity (`-` clears) |
| `keeper on｜off` / `app-host on｜off` | Keep-alive and app-host flags |
| `size auto｜WxH` / `codec auto｜h264｜hevc` | Pin the touch space (fixes `MISMATCH` / `touch=-1`) / encoder preference |
| `place` / `display up｜down` | Retry placing the cast surface; pre-build or release the virtual display |
| `start` / `stop` / `status` / `apps` / `stall-clear` | Session control, snapshot refresh, app-list export, clear the stall window |

## 4. `webroot/cgi-bin/ctl` — the panel CGI

The CGI helper busybox `httpd` executes as `/cgi-bin/ctl`, and it is the only
privileged path the panel has: it re-checks the token on every request against
`/data/local/tmp/okcar-port-panel.token`, answers `bad token` otherwise, sets `PATH`
explicitly and resolves the module directory from its own location.

| Query | Behaviour |
| --- | --- |
| `do=exec&b64=...` | Decode the base64url command, run `sh -c` with stdout/stderr captured, return `{"errno","stdout","stderr"}` base64url-encoded |
| `do=status` | Who the CGI runs as, daemon status head, switches, mode, QoS conf, priority conf |
| `do=switch&key=...&value=...` | Run `okcar-port-ctl switch <key> <value>` |
| `do=mode` | Write the mode file, re-enable the session switch, restart the daemon |
| `do=qos` | Rewrite `okcar-port-video.conf` (bitrate/fps per transport, delay, render scale) |
| `do=apps` / `do=app` | Read the exported app list / set the app to cast |

The `exec` decoder fixes base64url padding by hand (`tr` of `-_` to `+/`, then
re-pad) and never substitutes `+` to space: `+` is a legal base64 character and
that substitution silently breaks the command.

## 5. The manager WebUI (`webroot/app.js`)

`app.js` (about 2360 lines) is a single-file page application. The rules at its
head are the ones to preserve when editing it: normalise every bridge result shape
before reading fields (managers return `{errno, stdout, stderr}`, a plain string or
`{code, out, err}`); never call a module helper by bare name, because the `system`
overlay is not mounted on every install; keep one cheap shell probe per status tick;
delegate clicks on `document` and re-enable each button in a `finally`; and treat
the variant as data, not a second page.

**5.1 Resolving the module.** `MODULE_CANDIDATES` enumerates `/data/adb/modules`,
`/data/adb/modules_update` and the old `okcar-port` / `okcar-port-lite` ids;
`resolvePaths()` runs one probe that picks the candidate holding an executable
`okcar-linkd`, reads `webroot/variant` into the `lite` flag, and returns `dir` /
`linkd` / `ctl` / `carkit` — preferring `/system/bin/okcar-linkd` when the overlay
is mounted.

**5.2 The transport.** The page detects the exec protocol once and sticks to it:
KernelSU 3.x passes the callback *name* as a string, older managers a function, and
mixing the two wedges the bridge. `run()` races every command against a deadline
(4 s probes, 40 s actions); two consecutive probe timeouts raise the "bridge
silent" banner naming three remedies: open the page from the manager, tap the daemon
row's test-exec-bridge action, or run `su -c 'id'` in a terminal.

**5.3 The switch/status model.** `CATS` holds one entry per subsystem (daemon,
session, display, target, did, oui, anim, trim, wired, carplaypp) with a name, a
switch key, operator-facing warnings, health and raw values, and action buttons that
expand into concrete shell commands. A row renders a toggle and a coloured value;
tapping it opens a second-level page — a sub-page, not a popup. Dangerous toggles
(`oui`) confirm first, and an unknown switch state stays enabled with a tooltip
rather than looking broken.

**5.4 The status probe.** `probeCommand()` builds ONE shell line: a fresh `status`
send first (so the snapshot is never stale), then the status file, log tail,
switches, Bluetooth and USB/wired facts, caps, platform, the mode file, the daemon's
own `--mode`, the theme file, the applied QoS values, the `persist.okcar.max_fps` cap
and a `probe_end=1` sentinel. `mode_running` is the daemon's own `--mode`;
`mode_file` is intent — the file is intent, the process is fact. "Accessory
connected" needs the accessory field *and* an AirPlay channel ready, since the
accessory fields survive a drop. A missing sentinel flags the probe truncated; a
total failure does not fabricate default switch states.

**5.5 Runtime i18n.** `/data/local/tmp/okcar-port-lang` (default `zh`) drives a
header `<select>`, read before any bridge check so the selector works in a plain
browser too. Static text lives in `data-en` / `data-zh` attributes (placeholders in
`data-en-placeholder` / `data-zh-placeholder`), and `setLang` writes the file, sets
`document.documentElement.lang` and immediately re-renders everything rather than
waiting for the next 5 s tick. The Chinese table is authoritative — the English table
is generated by reversing it, so the two cannot drift apart; long dynamic values
(logs, package names, property dumps) pass through untranslated.

**5.6 Master switch, live/die binding, watchdog.** The master toggle (`#sw-master`)
remembers which switches were on into
`/data/local/tmp/okcar-port-master-memory.conf` before turning them all off, and
restores from that file (falling back to `linkd` + `session` + `did`) when turned on
— a file, not memory, because the page closes and the phone reboots. `linkd` and
`session` are bound in both directions so they stand and fall together. The watchdog
runs every 5 s and does exactly two corrections, each rate-limited to one attempt per
key per 60 s: start the daemon when the `linkd` switch is on but the process is gone,
and re-apply `did` when the property is no longer `0x4c`. It never restarts the daemon
on a vanished listener: a live cast rewrites the status file to a description that no
longer starts with `listening as`, and that misreading once killed live sessions.

**5.7 Modes.** Four mode tabs: `mirror` (default), `app`, `carplaypp` (only when that
module is installed) and `carwith` (only when the app exists). Because the mode is a
daemon *start* argument read from `/data/local/tmp/okcar-port-mode` at launch, every
mode change writes the mode file first **and** restarts the daemon — writing only the
file is how a panel came to claim app casting while the car showed a mirror. A confirm
dialog says the cast will drop, `mirror` also clears the remembered app, and app
casting picks the app and activity through pickers refilled from the conf files on
boot (default navigation match: Baidu, AutoNavi, Tencent).

**5.8 QoS.** Slider and number-box rows, double-bound and persisted to
`/data/local/tmp/okcar-port-video.conf`: `wireless_bitrate` 1000..30000,
`wired_bitrate` 2000..100000 kbps, `wireless_fps` / `wired_fps` 15..60,
`video_delay_ms` 0..2000 step 10 (default 0 — 0 means "no delay", not "no override")
and `app_render_scale` 25..100 step 5 (default 100). Clamping matches the daemon
exactly: 0 stays 0, other values snap to the step inside the range. The note under the
rows shows the *applied* values from `/data/local/tmp/okcar-port-video.applied`, so
"saved but the session still uses the old value" is distinguishable from "clamped"
and from "capped"; a low applied fps means a `persist.okcar.max_fps` cap, the head
unit's `/info maxFPS`, or the encoder.

**5.9 Priority.** `/data/local/tmp/okcar-port-priority.conf` lists one package per
line; the daemon reads it every 3 s and renices those processes into the top-app cpuset.

## 6. Runtime files behind the switches

| File | Controls (written by) |
| --- | --- |
| `okcar-port-mode` | The daemon start mode; the daemon writes the default when the file is missing (CGI, `app.js`, `okcar-linkd start`) |
| `okcar-port-switches.conf` | The seven switches (`okcar-port-ctl`) |
| `okcar-port-video.conf` / `okcar-port-video.applied` | Requested QoS / what the current session actually applies (the CGI, the daemon) |
| `okcar-port-app.conf`, `okcar-port-app-display.conf`, `okcar-port-app-host.conf` | App to cast, activity to place, app-host flag (`okcar-linkd send`, the CGI, `app.js`) |
| `okcar-port-priority.conf` | Priority package list (the WebUI) |
| `okcar-linkd.status` / `okcar-linkd.in` | The status snapshot the WebUI parses / the command FIFO, `0666` so an ordinary app can write to it (the daemon) |
| `okcar-linkd.log` / `okcar-port.log` | Daemon launcher and supervisor log / boot-hook log (`okcar-port-check` tails the latter) |
| `okcar-port-lang` / `okcar-port-theme` / `okcar-port-master-memory.conf` | UI language / theme / which switches the master toggle restores (`app.js`) |
| `okcar-port-panel.token` / `okcar-port-panel.url` | Panel token / URL (`okcar-port-panel`) |
| `okcar-port-apps.tsv` / `okcar-port-activities.tsv` | Exported app and activity lists (the daemon, `send apps`) |
| `bt.mac.orig` / `okcar-animations.saved` | Original Bluetooth MAC before an OUI change / animation scales to restore after a mirror session |
| `okcar-carkit.wanted` | Requested wired link state; the daemon polls it every 2 s (`okcar-port-ctl wired`) |

The supported hand-driven equivalents of the WebUI rows:

```
# what the mode buttons do
echo mirror > /data/local/tmp/okcar-port-mode
okcar-port-ctl switch session on && okcar-linkd restart

# cast one app, then cap the wireless bitrate
echo com.example.app > /data/local/tmp/okcar-port-app.conf
okcar-linkd send app com.example.app
printf 'wireless_bitrate=8000' >> /data/local/tmp/okcar-port-video.conf
```

Do not write to `okcar-linkd.in` directly: a plain shell redirect blocks until a
reader exists and can hang the caller — use `okcar-linkd send`.

## 7. Adding a new switch end to end

1. **Decide where the value lives.** If the daemon must act on it every session,
   give it a conf file read at startup (or a `send` verb) with a default that stands
   in — the boot hook starts the daemon with no arguments, so anything remembered
   must be on disk. A property-only switch still lives in `okcar-port-switches.conf`.
2. **`okcar-port-ctl`**: add the key to the default migration list, to the fixed
   rewrite order of the atomic write and to the `show_switches` description; decide
   which `apply` stage applies it (or that none does); add its warning line.
3. **`webroot/cgi-bin/ctl`**: nothing to do for a plain switch — `do=switch`
   forwards to the CLI and `do=status` prints the whole switches file, so the new
   key shows up in both surfaces automatically. Add an action only when the switch
   needs extra arguments.
4. **`app.js`**: add the key to the probe's switch defaults, add a `CATS` entry (or
   set its `switchKey`) with warnings and actions, add its label line, and — if the
   switch needs a *live* readback — extend `probeCommand`. The master switch joins by
   adding the key to its key list.
5. **Variant gating**: if the lite build should hide the row, mark the category
   entry so it is dropped when `webroot/variant` is `lite`.
6. **Test both surfaces** — from the manager (bridge path) and from the panel URL
   (CGI path): a command that works in one and not the other is usually the
   base64url or the callback-name detail.
7. **Precedents worth copying**: the `anim` switch (every switch needs a panel row, or
   its default must be inert); the `oui` confirm; the `did` watchdog correction.

Two honest limitations: how the daemon's self-test classes run is undocumented in the
tree (see `docs/en/building.md`), and the wired row reports the USB stack, not the car screen.
