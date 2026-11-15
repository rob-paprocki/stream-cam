# Hand-written AboutLibraries entries

Nothing is here yet. The first entries arrive in milestone M0b, with the app's first native libraries.

The app's build reads this folder for library and licence entries that Maven metadata can't supply.
Mostly that means native code such as libsrt or OpenSSL, which comes without a POM. The entries feed the
licence list on the About screen (LIC-4) and the licence check, which stops the build when a library's
licence isn't on the allowed list in `android/app/build.gradle.kts` (LIC-2).

- `libraries/` takes one JSON file per library. An entry whose `uniqueId` matches a Maven library is
  merged into it.
- `licenses/` takes one JSON file per licence the plugin doesn't already know.

The file format is in the
[AboutLibraries README](https://github.com/mikepenz/AboutLibraries#modify-libraries--licenses).
