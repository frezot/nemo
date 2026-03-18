# NEMO

NEMO is an Android network conditioner for testing apps under degraded mobile and Wi-Fi conditions.

It runs as a local `VpnService`, forwards traffic through a userspace datapath, and applies configurable network degradation without requiring root.

## Features

- preset network profiles
- one custom profile
- latency
- jitter
- uplink and downlink bandwidth limiting
- packet loss
- temporary traffic stalls
- per-app targeting

## Build

Clone with submodules:

```bash
git clone --recurse-submodules <repo-url>
cd adnroid-network-link-condition
```

If you already cloned the repo:

```bash
git submodule update --init --recursive
```

Build a debug APK:

```bash
./gradlew :app:buildDebug
```

The APK will be produced under:

```text
app/build/outputs/apk/debug/
```

## Project Notes

- Android app code lives in `app/src/main/java`
- native datapath and conditioner code lives in `app/src/main/jni`
- `zdtun` is included as a git submodule in `submodules/zdtun`

## Acknowledgements

NEMO builds on ideas explored in:

- [PCAPdroid](https://github.com/emanuele-f/PCAPdroid)
- [Network Link Conditioner](https://developer.apple.com/download/all/?q=Additional%20Tools) by Apple

It also depends on:

- [zdtun](https://github.com/emanuele-f/zdtun)
- [CustomActivityOnCrash](https://github.com/Ereza/CustomActivityOnCrash)
