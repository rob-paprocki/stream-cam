# Notes for contributors

PROJECT_NAME is a free, open-source app that takes video from a phone's built-in camera or a USB
capture card and records it to MP4, streams it over SRT, RTSP or RTMP, or does both at once. Android
comes first, and iOS and iPadOS follow. Until the app has a name, `PROJECT_NAME` and the package ID
`org.example.projectname` are placeholders.

## Layout

| Path | What it holds |
| --- | --- |
| `android/` | The Android app: a Gradle project with an `app` module (the Compose UI and the platform code) and a `core` module (plain Kotlin with unit tests, no Android needed) |
| `ios/` | The iOS and iPadOS app, not started yet |
| `shared/` | `profile.schema.json`, the stream-profile format both apps use, and example profiles that CI checks against it |
| `docs/` | `TESTPLAN.md`, the hardware tests that close each milestone, and `spikes/`, one short note per spike |
| `test/` | Test-server settings: `mediamtx.yml` makes MediaMTX receive every outbound mode |
| `tests/` | Repository checks, such as `check_repo.py`, which scans published files for personal values |
| `config/` | Settings for the licence and dependency checks, and the pinned Python tools that CI installs |
| `scripts/release/` | The changelog and release tools |

## Build and test

```sh
python3 -m unittest discover -s tests
python3 tests/check_repo.py
cd android && ./gradlew :core:test :app:assembleFossDebug
```

The Gradle build needs the Android SDK, through `ANDROID_HOME` or `sdk.dir` in
`android/local.properties`.

## Rules

- The version lives in `VERSION` and is set by `scripts/release/changelog.py finalize`, not by hand.
- Every visible change gets a line under `## [Unreleased]` in `CHANGELOG.md`, in the same commit. A
  commit with no visible effect says `[no changelog]` in its message instead.
- Requirement IDs (UI-1, REC-4, OUT-3 and so on) go in commit messages, and in the names of the tests
  that cover them, for example `OUT3_backoffFollowsOneTwoFiveThenTen`.
- Published files use example values only. IPv4 addresses come from 192.168.100.0/24 or the
  documentation ranges 192.0.2.0/24, 198.51.100.0/24 and 203.0.113.0/24. IPv6 addresses come from
  2001:db8::/32, host names and email addresses from example.com, and MAC addresses from
  00:00:5E:00:53:xx. Code sorts addresses into kinds (link-local, private and so on) with platform calls
  instead of literal ranges. `tests/check_repo.py` flags anything else, and CI runs it on every push.
- A new dependency needs the maintainer's approval first, with its licence named. CI checks every
  dependency against `config/approved-dependencies.txt`. A library that an approved one pulls in under a
  permissive licence (Apache-2.0, MIT, BSD or ISC) is accepted, but list it in the pull request.
- Declare every dependency and Gradle plugin in `android/gradle/libs.versions.toml` and use its alias.
  CI fails on a dependency written as a string or a plugin applied with `id("...")`. After changing a
  dependency, run `python3 tests/check_dependencies.py` and commit the `config/resolved-dependencies.txt`
  it writes.
