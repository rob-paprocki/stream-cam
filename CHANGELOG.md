# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-11-15
**Upgrading:** nothing to do.

### Added

- A first test build. It shows a color-bar test pattern with a time-of-day clock and a test tone, and
  records it to an MP4 file in the phone's Movies folder.
- Export log, which shares the app's debug log as a text file, with private details such as passwords
  and addresses taken out.
- A recording that couldn't be finished isn't copied to Movies. The app says so and removes the
  unfinished file: at once if writing it failed, otherwise the next time the app starts.

### Changed

- The README no longer says NDI is waiting on a license check. NDI output is an optional build
  flavor, off by default, and that build isn't offered on F-Droid.

## [0.1.0] - 2026-11-14

**Upgrading:** nothing to do.

### Added

- First release: the repository, its license and README, and the release tooling. There is no app yet.

[Unreleased]: https://github.com/rob-paprocki/stream-cam/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rob-paprocki/stream-cam/releases/tag/v0.1.0
