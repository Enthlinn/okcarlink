# OkCarLink

**Let an Android device stand in for the iPhone in CarPlay / AirPlay.**

[中文说明](README.zh-CN.md) | [English](README.md)

OkCarLink is a KernelSU / Magisk module that runs the whole iPhone-side port stack
on an Android device — no companion phone app. The device
connects to a host: a car head unit running an iPhone-link app, or a second
Android device running a head-unit emulator. The host's screen shows what the
device sends; touches on the host come back to the device.

| Mode | Transport | What the device plays | Status |
| --- | --- | --- | --- |
| Wireless (AirPlay) | Wi-Fi | the iPhone's wireless link: an iAP2 RFCOMM service the host dials, then AirPlay (screen mirroring + audio). This single stack is what a head unit drives as its "wireless CarPlay" — it is AirPlay, not a separate mode | supported |
| Wired CarPlay | USB | an Apple USB device (USBMUX + NCM) that answers lockdown itself | supported (needs a lockdown identity, see below) |

Everything runs inside the module: a headless root daemon
(`payload/okcar-link.dex`, started with `app_process`), two small native binaries,
and a WebUI for switching modes and reading logs.

> **Status:** this is a working port, tested on real head units, but it is not a
> commercial product. Expect rough edges; issues and PRs are welcome.

## Tested with

- **Adaptation phone:** Redmi Note 12 Turbo (Android 15, KernelSU) — the device this port
  was developed and debugged on.
- **Real car head unit:** both the wired CarPlay link (Apple USB enumeration, lockdown,
  iAP2 over FunctionFS) and the wireless AirPlay link were verified end to end against an
  actual car head unit — screen mirroring, audio and the touch back-channel.

## Requirements

- Android 10+ device (the port targets platform behavior up to Android 15-era
  devices; ARM64 for the native binaries), rooted with KernelSU or Magisk
- A peer host: a car head unit running an iPhone-link app (wireless CarPlay /
  AirPlay), or a second Android device running a head-unit emulator
- Wi-Fi for the wireless mode; a USB port that can work in device mode for the
  wired mode — the module re-enumerates the device as an Apple USB device
- To **build from source**: JDK 17 plus either an AOSP source tree or the published
  dependency bundle; CI does it on every push (see [docs/en/building.md](docs/en/building.md))

## What's in this repository

| Path | Contents |
| --- | --- |
| `module/` | The KernelSU/Magisk module: boot hooks, helper scripts, WebUI, SELinux rule |
| `daemon/` | Java sources of the port stack (`com.okcar.link` + shared codec `com.okcar.cast`) compiled into one dex |
| `native/` | C sources of the two native binaries (touch injector, wired-mode FunctionFS/mux/lockdown server) |
| `tools/` | Build scripts: dex builder, native builders, module packager, CI dependency bundle |
| `docs/` | Bilingual developer documentation (`docs/en` + `docs/zh`) |

## Quick start (build)

**Locally**, with an AOSP tree (`TREE=` and the rest: `docs/en/building.md`):

```bash
git clone <this repo> okcarlink && cd okcarlink
export TREE=/path/to/aosp
bash tools/package-module.sh      # -> build/okcarlink-1.0.0.zip
```

**In CI**, `.github/workflows/build.yml` does the same on a hosted runner, using a published
dependency bundle (framework jars + prebuilt natives) instead of an AOSP checkout — the hidden-API
framework classes and the static native toolchain are not reproducible from the public SDK. Both
paths are described in `docs/en/building.md`; a `v*` tag attaches the zip and its SHA-256 to a
release.

Wired mode needs a TLS identity, and the module makes one itself on the first wired start
(`okcar-ffs-usbmux --gen-identity`, RSA-2048 self-signed) — so a clean install needs no key step at
all, and `okcar-port-ctl identity regen` (or the WebUI's *Regenerate the TLS identity* button)
replaces it. Pinning a chosen pair is optional:

```bash
mkdir -p lockdown
# optionally put lockdown-cert.pem + lockdown-key.pem there — see docs/en/crypto.md
```

Then flash `build/okcarlink-1.0.0.zip` in the KernelSU/Magisk manager, reboot, and
open the module's WebUI from the manager (or use `okcar-port-ctl` on the device).

Full instructions: [docs/en/building.md](docs/en/building.md).

## Documentation

| Document | Contents |
| --- | --- |
| [docs/en/architecture.md](docs/en/architecture.md) | Layers, daemon packages, the three modes and their data flows |
| [docs/en/module.md](docs/en/module.md) | KernelSU module layout, boot sequence, helper scripts |
| [docs/en/webui.md](docs/en/webui.md) | WebUI anatomy, the control protocol, adding a switch |
| [docs/en/building.md](docs/en/building.md) | Toolchain, build steps, packaging, install |
| [docs/en/crypto.md](docs/en/crypto.md) | The wired-mode lockdown identity and how to generate one |

Each document exists in Chinese under `docs/zh/` with the same file name.

## Security notes

- This repository deliberately ships **no key material**: wired mode needs a lockdown TLS
  identity, and the module generates a self-signed one on the device at the first wired
  start (`okcar-ffs-usbmux --gen-identity`), then advertises the public key it reads back
  from that certificate. Every install therefore has its own identity. See
  [docs/en/crypto.md](docs/en/crypto.md).
- The release packaging runs a leak gate (private addresses, credentials, key
  material) during `tools/package-module.sh`; it fails the build instead of
  shipping bad content.
- Anything the module writes at runtime lands under `/data/local/tmp`
  (`okcar-port-*` state files) and is cleaned by `uninstall.sh`.

## License

MIT, see [LICENSE](LICENSE).

## Acknowledgments

The module approach mirrors projects such as
[KernelSU](https://github.com/tiann/KernelSU); the reverse-engineered protocol
handlers were built from public documentation and observed traffic of the
technologies they implement (CarPlay, AirPlay, Bluetooth HID), and the code is
organized so each protocol can be read and tested on its own.
