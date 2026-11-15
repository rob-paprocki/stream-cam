# stream-cam

A free, open-source Android and iOS app that shows video from a built-in or USB camera and records it, streams it over the network, or does both at once.

[![Release](https://img.shields.io/github/v/release/rob-paprocki/stream-cam)](https://github.com/rob-paprocki/stream-cam/releases)
[![CI](https://github.com/rob-paprocki/stream-cam/actions/workflows/ci.yml/badge.svg)](https://github.com/rob-paprocki/stream-cam/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/rob-paprocki/stream-cam)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/rob-paprocki/stream-cam)](https://github.com/rob-paprocki/stream-cam/commits/main)

> **Status: first test build.** The Android app can record a test pattern (color bars, a time-of-day clock and a tone) to an MP4 file and export its debug log. It can't use a camera or stream yet. The first release (0.1.0) set up the repository.

The app has one main screen: a preview, a source picker and three buttons (Record, Stream, Both). Android ships first, and iOS follows from the same spec. The app has no name yet, so "stream-cam" is the repository name only.

## What it aims to do

- Cost nothing: no ads, no paid tier, no telemetry, and every dependency in the default build under an OSI-approved license.
- Use USB (UVC) cameras and HDMI capture dongles that send MJPEG, without root.
- Stream with SRT (caller and listener), RTSP and RTMP, either pushing to a server or hosting a small server that receivers connect to. NDI® is an optional build flavor, off by default. That build bundles NDI's own library, so it isn't offered on F-Droid. NDI® is a registered trademark of Vizrt NDI AB.
- Let you pin each output to cellular, Wi-Fi or Ethernet.
- Record locally to a file while streaming.

**Does not:** put USB cameras on iPhone (Apple doesn't allow it, so iPad only), pull a remote stream in as a camera source in version 1, or send more than one recording plus one live destination at a time in version 1.

## What each platform can do

| Capability | Android | iPad (USB-C) | iPhone |
|------------|---------|--------------|--------|
| Built-in cameras | Yes | Yes | Yes |
| USB UVC cameras and capture cards | Yes, over the USB host API | Yes, iPadOS 17 or later | No |
| Bind an output to cellular, Wi-Fi or Ethernet | Yes | Yes | Yes |
| Keep capturing in the background | Yes, with a foreground service | No | No |

## Requirements

| Item | Supported |
|------|-----------|
| Android | Android 10 (API 29) or later |
| iOS and iPadOS | Planned after Android |
| License | Apache-2.0, with no GPL or AGPL dependencies |

## Install

There's no release to install yet. Testers install the debug build that CI makes, as [the test plan](docs/TESTPLAN.md) describes. To follow along or run the repository's own checks:

```bash
git clone https://github.com/rob-paprocki/stream-cam.git
cd stream-cam
python3 -m unittest discover -s tests
```

## Quick start

The planned flow, once the app exists:

1. Pick a camera: built-in, or a USB camera or capture card.
2. Tap Record, Stream or Both. For a stream, choose a protocol and destination.
3. Optionally pin the stream to a network.

## Help and contributing

Questions, bug reports and ideas go in [Issues](https://github.com/rob-paprocki/stream-cam/issues). Releases come from a separate development repository, so a pull request here is not merged directly. A good one gets copied over by hand and ships with the next release.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

[Apache-2.0](LICENSE) (c) 2026 Rob Paprocki
