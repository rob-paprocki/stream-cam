# Test plan

These are the tests that decide when a milestone is done. They run on real hardware, with only
open-source tools on the receiving end, and they're written as steps you can follow without reading any
code. They come from the acceptance tests in the project's spec. The IDs in brackets, such as (REC-4),
name the spec requirement a step checks.

The app has no name yet. `PROJECT_NAME` stands in for it, and `org.example.projectname` for its package
ID.

## How a test session works

1. A session starts with an issue titled `Test session: <milestone>`. It holds that milestone's steps
   from this file as a checklist, along with the kit you need, a rough time and a link to the build.
2. Install the build. It's a single APK file named `PROJECT_NAME-foss-debug-<commit>.apk`, where
   `<commit>` is the first seven characters of the commit it was built from. You can install it from a
   computer or on the phone itself.
   - From a computer: plug the phone in with USB debugging on, download the APK, then run
     `adb install -r PROJECT_NAME-foss-debug-<commit>.apk` with the file's real name. The `-r` replaces
     the copy already on the phone and keeps its settings. To turn USB debugging on, tap Settings >
     About phone > Build number seven times, then turn on Settings > System > Developer options > USB
     debugging.
   - On the phone: open the build link while signed in to github.com and download the APK. Open it from
     the download notification or in Files, and tap Install. Android asks once whether to allow
     installs from that app.
3. Work through the checklist and tick each box in the issue. If a step fails, write down what you saw.
   If the test pattern or an HDMI clock is on screen, note the time it showed. Then carry on with the
   next step if you can.
4. When you're done, tap **Export log** in the app. It shares the log as a `.txt` file. Save it, then
   attach it to a comment on the issue.

A build you make yourself is at `android/app/build/outputs/apk/foss/debug/app-foss-debug.apk`.

Button and setting names below follow the spec. If the app labels something differently, follow the app
and mention the difference in the issue.

## What you need

- An Android phone running Android 10 or later.
- A computer on the same network, with [VLC](https://www.videolan.org/vlc/),
  [ffmpeg](https://ffmpeg.org/) (which includes ffplay), and `adb` from Android's
  [platform tools](https://developer.android.com/tools/releases/platform-tools).
- From M2 on, [MediaMTX](https://github.com/bluenviron/mediamtx) v1.21 on that computer, started with
  this repository's `test/mediamtx.yml`. From M4 on, [OBS Studio](https://obsproject.com/) as well.

Each milestone below lists any other kit at the top of its section.

## Addresses used here

Every address in this file is an example. Use your own in their place, and leave the real ones out of
anything you post in public. The exported log already removes them.

| What | Example address |
| --- | --- |
| The phone, on Wi-Fi | 192.168.100.20 |
| The phone's USB-C Ethernet adapter (M3) | 192.168.100.30 |
| The computer running MediaMTX, ffplay and VLC | 192.168.100.10 |
| A second computer or tablet (M4) | 192.168.100.11 |
| A server on the internet (M3) | relay.example.com |

## Notes on the tools

- Start MediaMTX from the top folder of this repository with `mediamtx test/mediamtx.yml`, and leave it
  running in its own window. The file lists every URL it serves. MediaMTX logs a line for each client
  that connects, with the client's address, and some tests below read those lines. The computer's
  firewall must let the phone in: TCP 8554 and 1935, and UDP 8000, 8001 and 8890.
- MediaMTX's SRT server needs a stream ID that says what the client wants: `streamid=publish:test` to
  send the stream, `streamid=read:test` to watch it.
- ffmpeg and ffplay take SRT latency in microseconds, so `latency=120000` is 120 ms.
- VLC ignores options in an SRT URL, so it can't send the stream ID or passphrase MediaMTX asks for. VLC
  also can't play RTSPS. Use ffplay for both.
- Keep the quotes around SRT and password URLs in the commands below. Without them, the shell takes `&`
  and `?` as its own.
- Press `q` in an ffplay window to close it.

## M0a: foundations

You need the phone, and the computer with VLC (plus `adb` if you install from the computer). It takes
about 30 minutes.

### 1. Install the build

1. Install the build as described in [How a test session works](#how-a-test-session-works).
2. Open PROJECT_NAME. If Android asks for a permission, allow it, and note in the issue what it asked
   for.

Pass: the app opens and shows no error.

### 2. Preview the test pattern

1. Look at the preview. It should show color bars and a clock with the time of day: hours, minutes,
   seconds and a frame count.
2. Watch it for 10 seconds.

Pass: the color bars fill the preview. The clock shows about the same time as the phone's status bar
and keeps counting without stopping or jumping.

### 3. Record 30 seconds

1. Tap **Record**. The button now reads **Stop** (UI-1).
2. Wait 30 seconds by the clock in the picture, then tap **Stop**.

Pass: the button reads **Record** again, and the app shows no error.

### 4. Find the recording

1. On the phone, open the Files app, go to the internal storage, then Movies, then PROJECT_NAME.

Pass: the folder holds one new file. Its name is `PROJECT_NAME_` followed by the local date and time,
for example `PROJECT_NAME_20261114_153000.mp4` (REC-3, REC-4).

### 5. Play it on the computer

1. Copy the file to the computer. With `adb`, `adb pull /sdcard/Movies/PROJECT_NAME/` copies the whole
   folder into the current folder. Without it, connect the phone by USB in file transfer mode and copy
   the file across.
2. Open the file in VLC.
3. Open Tools > Codec Information in VLC (on macOS, Window > Media Information, then Codec Details).

Pass: the file plays for about 30 seconds and shows the color bars and the running clock. You hear a
steady 1 kHz tone, a high, even beep. The codec information lists H.264 video and AAC audio (REC-1).

### 6. Close the app while it saves

1. Tap **Record**, wait about 5 seconds, then tap **Stop**.
2. While the status reads "Saving to Movies…", open the app switcher (Recents) and swipe PROJECT_NAME
   away. If the status changes to "Not recording" before you get there, record for a minute this time
   and try again.
3. Open PROJECT_NAME again.
4. In the Files app, open Movies, then PROJECT_NAME.
5. Copy the new file to the computer and open it in VLC, the same way as before.

Pass: each recording you made in this step left exactly one new file in the folder, and the file plays
(REC-2).

### 7. Export the log

1. In the app, tap **Export log**. Android's share menu opens with the log as a `.txt` file. Save it
   somewhere you can find it again, such as Drive or the phone's Downloads folder.
2. Open the file and read it before you post it. Settings > About phone shows the phone's IP address
   and serial number, so you know what to look for.
3. Attach the file to a comment on the test-session issue. On github.com, use the paperclip button in
   the comment box, or drag the file onto the box.

Pass: you get the log as a `.txt` file. It holds no passwords, stream keys, IP or MAC addresses, or
serial numbers. Where one of those would have been, the log shows a placeholder such as `<ip>` or
`<redacted>` (OUT-6).

## M0b: Android spikes

M0b runs spikes instead of acceptance tests. Each spike session gets its own issue, with a checklist
built from the spike's question. The answers end up as short notes in [docs/spikes/](spikes/README.md).

## M1a: sources

You need the phone, a USB (UVC) HDMI capture card, and an HDMI source showing a test pattern or a clock
at 1080p. To connect them, use a USB-C adapter or a powered USB-C hub. You also need a computer to read
the card's USB name. Expect two sessions of one to two hours each.

### Attach a capture card fed 1080p video (SRC-2, SRC-3, SRC-6, SRC-10, UI-4, UI-5)

1. Find the card's USB product name. Plug the card into the computer and look it up: on Linux run
   `lsusb`, on macOS look in System Information under USB, and on Windows look in Device Manager under
   Cameras. Write the name down, then unplug the card.
2. Connect the HDMI source to the card and set the source to 1080p.
3. On the phone, open the app and open the source picker.
4. Plug the card into the phone and start counting. If Android asks whether to open PROJECT_NAME for the
   card, or whether the app may use it, allow it. The time you spend on those dialogs doesn't count.

   Pass: within 3 seconds the card is in the picker, with a USB icon and the name from step 1. A card
   that reports no name shows as `USB camera (VID:PID)` with its two ID numbers.
5. Choose the card. Its list of modes appears. Pick 1920×1080, 30 fps, MJPEG.

   Pass: the preview shows the HDMI picture. The status strip shows 1920×1080 at 30 fps.
6. Close the app (swipe it away in the app switcher), then open it again.

   Pass: the card is still selected, in the mode you picked (UI-5).

### Unplug the card while previewing (SRC-7, UI-4)

1. With the card's picture in the preview, pull its cable.

   Pass: a banner says the source is gone, the app keeps running, and the card drops out of the picker.
2. Plug the card back in.

   Pass: it shows up in the picker again without restarting the app.

## M1b: audio and crash-safe recording

You need the M1a kit, plus an audio and video sync test playing on the HDMI source: a video with a white
flash and a beep once a second. Searching for "audio video sync test" turns up plenty. The first test
records for 30 minutes, which is most of why this milestone can take up to two hours, over one or two
sessions.

### Record 30 minutes at the defaults (REC-1, REC-2, REC-3, SRC-8, SRC-9)

1. Leave every setting at its default. With a USB source, the audio comes from the card's USB audio.
2. Play the sync test on the HDMI source, on a loop.
3. Tap **Record**. After 30 minutes, tap **Stop**.
4. Copy the recording to the computer, as in M0a, and play it in VLC. Skip through to check the whole
   length.
5. Watch the flash and listen for the beep, first near the start and then near the 29-minute mark.

Pass: the recording plays from start to end. The beep and the flash land together at both points. The
limit is 80 ms, under three frames at 30 fps, so a gap you can clearly see and hear is a fail. If the
recording is split into more than one file, each file starts where the one before it ends, with no gap
in the clock.

If you can't tell whether the sync is right, cut 10 seconds from near the end and attach the clip to the
issue:

```sh
ffmpeg -ss 00:29:00 -i PROJECT_NAME_20261114_153000.mp4 -t 10 -c copy sync-end.mp4
```

### Join segments without gaps (REC-2)

1. Set Settings > Recording > Segment length to 5 min.
2. Record for 11 minutes, then stop. You get three files.
3. In VLC, play the last 10 seconds of each file and the first 10 seconds of the next.

Pass: the clock on the HDMI source carries on from one file to the next with no second missing, and the
sound has no gap. Set the segment length back to its default afterwards.

### Force-stop the app while recording (REC-2)

1. Tap **Record**. After about 3 minutes, note the time on the HDMI source's clock and force-stop the
   app: run `adb shell am force-stop org.example.projectname`, or go to Settings > Apps > PROJECT_NAME
   and tap Force stop.
2. Open the app again. It repairs unfinished recordings when it starts.
3. Copy the recording to the computer and play it in VLC.

Pass: it plays from the start to within a few seconds of the time you noted. Only the last unfinished
fragment may be missing.

### Pull the USB cable while recording (SRC-7, REC-2)

1. Tap **Record**. After about 2 minutes, pull the capture card's cable and note the time on the HDMI
   source's clock.

   Pass: a banner says the source is gone, the app doesn't crash, and the recording stops.
2. Copy the recording to the computer and play it in VLC.

   Pass: it plays to the last second before you pulled the cable.

## M2: outbound streaming

You need the phone, and the computer with MediaMTX and ffmpeg. The test pattern works as the source, and
so does a capture card. There are three 30-minute runs, so this needs two sessions of about two hours.
The runs can follow one another without restarting MediaMTX.

Before each test, start MediaMTX on the computer with `mediamtx test/mediamtx.yml`.

### SRT caller to MediaMTX (OUT-2, OUT-7, UI-8)

1. In the app, add a destination profile: SRT caller, host 192.168.100.10, port 8890, stream ID
   `publish:test`, no passphrase, latency 120 ms.
2. Tap **Stream** and wait until the status reads live.
3. On the computer, run:

   ```sh
   ffplay "srt://192.168.100.10:8890?streamid=read:test&latency=120000"
   ```

4. Let it play for 30 minutes, and check the clock in the picture every few minutes.

   Pass: the picture and sound start, and no freeze lasts longer than a second. The app stays live, and
   its statistics show the bitrate (OUT-7).
5. Tap **Stop** on the stream. Edit the profile: stream ID `publish:encrypted`, passphrase
   `example-passphrase`, encryption AES-128. Tap **Stream**, then run:

   ```sh
   ffplay "srt://192.168.100.10:8890?streamid=read:encrypted&passphrase=example-passphrase&latency=120000"
   ```

   Pass: it plays.
6. Repeat step 5 with the encryption set to AES-256.

   Pass: it plays.
7. Change the passphrase in the profile to `wrong-passphrase` and tap **Stream**.

   Pass: the app doesn't go live. The status reads reconnecting or failed and gives the reason in plain
   words (UI-8).

### RTMP push to MediaMTX (OUT-3, OUT-7)

1. Add a profile: RTMP push, URL `rtmp://192.168.100.10:1935/live`, stream key `test`.
2. Tap **Stream** and wait until the status reads live. On the computer, run:

   ```sh
   ffplay rtmp://192.168.100.10:1935/live/test
   ```

3. Let it play for 30 minutes.

   Pass: it plays the whole time, with no freeze longer than a second.
4. Tap **Record** too, so the next steps also check the recording.
5. Restart MediaMTX: press Ctrl+C in its window, wait 10 seconds, and start it again with the same
   command.

   Pass: the app shows reconnecting, then goes live again on its own within about 15 seconds of
   MediaMTX coming back. You don't touch the phone. Its statistics show one more reconnect (OUT-7).
6. ffplay closes when MediaMTX stops, so run the ffplay command again.

   Pass: it plays.
7. Stop recording, copy the file to the computer and play it in VLC.

   Pass: the recording runs straight through the restart, and the clock in the picture doesn't jump
   (OUT-3).

### RTSP push to MediaMTX over TCP (OUT-3, OUT-7)

1. Add a profile: RTSP push, URL `rtsp://192.168.100.10:8554/live/test`. If the profile has a transport
   setting, choose TCP.
2. Tap **Stream** and wait until the status reads live. On the computer, run:

   ```sh
   ffplay -rtsp_transport tcp rtsp://192.168.100.10:8554/live/test
   ```

3. Run steps 3 to 7 of the RTMP push test, with this ffplay command in step 6.

Pass: the same as for RTMP push.

### RTMPS and RTSPS push with a test certificate

1. Stop MediaMTX. From the top folder of this repository, make a self-signed test certificate. The
   `.local` file names are ignored by git, so the key can't be committed by mistake.

   ```sh
   openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=mediamtx-test" \
     -keyout test/server.key.local -out test/server.crt.local
   ```

2. Start MediaMTX with TLS turned on next to the plain ports:

   ```sh
   MTX_RTSPENCRYPTION=optional MTX_RTMPENCRYPTION=optional mediamtx test/mediamtx.yml
   ```

   In Windows PowerShell, set the two variables first with
   `$env:MTX_RTSPENCRYPTION="optional"; $env:MTX_RTMPENCRYPTION="optional"`, then run
   `mediamtx test/mediamtx.yml`. Let TCP 8322 and 1936 through the firewall too.
3. Add a profile: RTMP push, URL `rtmps://192.168.100.10:1936/live`, stream key `test`. Tap **Stream**,
   then run `ffplay rtmps://192.168.100.10:1936/live/test`.

   Pass: it plays.
4. Add a profile: RTSP push, URL `rtsps://192.168.100.10:8322/live/test`. Tap **Stream**, then run
   `ffplay -rtsp_transport tcp rtsps://192.168.100.10:8322/live/test`.

   Pass: it plays.

The certificate is self-signed. If the app warns about it, copy the warning's wording into the issue.
When you're done, delete `test/server.key.local` and `test/server.crt.local`.

### Start Stream, then Record, then stop Stream (UI-1, UI-2)

1. Use the SRT caller profile from the first test. Tap **Stream**, wait for live, and watch it with
   ffplay.
2. After a minute, tap **Record**.
3. After another minute, tap **Stop** on the stream, and note the time on the clock in the picture.
4. After another minute, tap **Stop** on the recording.
5. Copy the recording to the computer and play it in VLC.

   Pass: it runs for about two minutes. Around the time from step 3, the clock keeps counting, and the
   picture and sound carry on without a jump or a freeze.
6. With nothing running, tap **Both**.

   Pass: the stream and the recording both start. Tapping **Both** again stops both.

## M3: network binding

You need the phone with a SIM and mobile data, on Wi-Fi, and a USB-C Ethernet adapter (or a hub with an
Ethernet port) cabled to the same network. You also need the computer, and a server on the internet
running MediaMTX with `test/mediamtx.yml` and TCP 1935 open. One or two sessions of about two hours
should cover it.

### Bind a profile to cellular while Wi-Fi is up (NET-1, NET-3, NET-5)

1. Start MediaMTX on the internet server.
2. On the phone, connect to Wi-Fi and leave mobile data on.
3. Add a profile: RTMP push, URL `rtmp://relay.example.com:1935/live`, stream key `test`, network
   Wi-Fi. Tap **Stream**. In the server's MediaMTX log, the line for this connection shows the address
   it came from. That's your Wi-Fi network's public address. Note it, then stop the stream.
4. Change the profile's network to Cellular and tap **Stream**.

   Pass: the app goes live, and the status strip shows Cellular and the address it bound to (NET-5).
   In the server's log, this connection comes from a different address, your carrier's, not the one
   from step 3.
5. On the computer, run `ffplay rtmp://relay.example.com:1935/live/test`.

   Pass: it plays.

### Turn the bound network off mid-stream, with fallback off (NET-4, OUT-3)

1. Stream with the Cellular profile from the last test. Leave Allow fallback off, as it is by default.
2. Turn mobile data off in Quick Settings. Leave Wi-Fi on.

   Pass: the app shows reconnecting or failed, with a reason. The server's log shows the connection
   closing. For a full minute, no new connection arrives from the Wi-Fi address you noted.
3. Turn mobile data back on.

   Pass: the app reconnects on its own, over cellular. The server's log shows the carrier's address
   again.

### Bind a profile to a USB-C Ethernet adapter (NET-2, NET-5)

1. Plug the Ethernet adapter into the phone and cable it to your network. Keep Wi-Fi connected to the
   same network.
2. Open the network picker.

   Pass: Ethernet is listed with its own address (192.168.100.30 in this file's examples), next to
   Wi-Fi with 192.168.100.20 (NET-2).
3. Start MediaMTX on the computer. Add a profile: RTMP push, URL `rtmp://192.168.100.10:1935/live`,
   stream key `test`, network Ethernet. Tap **Stream**.

   Pass: in the computer's MediaMTX log, the connection comes from the adapter's address, not the Wi-Fi
   one. The status strip shows Ethernet.

## M4: inbound modes

Here the phone is the server and the players connect to it. You need the phone on Wi-Fi, and the
computer with ffmpeg, VLC and OBS Studio. For the RTSP and RTMP tests you also need a second client: a
second computer, or a phone or tablet running VLC. It's two sessions of about two hours.

If a profile has a password or passphrase, including one the app made for you, use it where the
commands below show `<password>` or `<passphrase>`.

### SRT listener (OUT-4, NET-6, UI-7)

1. Add a profile: SRT listener, port 9000, latency 120 ms.
2. Tap **Stream**.

   Pass: the status strip shows `srt://192.168.100.20:9000`, with the phone's Wi-Fi address and a copy
   button (UI-7, NET-6).
3. On the computer, start counting and run:

   ```sh
   ffplay "srt://192.168.100.20:9000?mode=caller&latency=120000"
   ```

   If the profile has a passphrase, add `&passphrase=<passphrase>` inside the quotes.

   Pass: the picture starts within 3 seconds.
4. Close ffplay and run the same command again.

   Pass: it plays again. The app accepts a new caller after the first one leaves (OUT-4).

### RTSP pull (OUT-4)

1. Add a profile: RTSP pull, port 8554, path `live`, user `viewer` and a password.
2. Tap **Stream**. The status strip shows `rtsp://192.168.100.20:8554/live`.
3. On the computer, run:

   ```sh
   ffplay -rtsp_transport tcp "rtsp://viewer:<password>@192.168.100.20:8554/live"
   ```

4. On the computer, open VLC, choose Media > Open Network Stream (File > Open Network on macOS), enter
   `rtsp://192.168.100.20:8554/live` and press Play. Give the user name and password when VLC asks.
5. Do the same in VLC on the second client.

   Pass: ffplay and both copies of VLC play at the same time.
6. On the computer, run the ffplay command from step 3 in a second window.

   Pass: all four players play at once.
7. Run ffplay with a wrong password:

   ```sh
   ffplay -rtsp_transport tcp "rtsp://viewer:wrong@192.168.100.20:8554/live"
   ```

   Pass: ffplay is refused with "401 Unauthorized", and the other players keep playing.

### RTMP pull (OUT-4, OUT-5)

RTMP pull may arrive in a later version than the other inbound modes. Skip this test if the build
doesn't offer it.

1. Add a profile: RTMP pull, port 1935, app name `live`, stream name `test`.
2. Tap **Stream**. The status strip shows `rtmp://192.168.100.20:1935/live/test`.
3. On the computer, run `ffplay rtmp://192.168.100.20:1935/live/test`.
4. On the second client, open the same URL in VLC.
5. In OBS Studio, add a source: Sources > + > Media Source. Clear Local File, enter the URL in Input
   and click OK.

   Pass: ffplay, VLC and OBS all play at the same time.
6. Wait a minute. Start counting and run the ffplay command from step 3 in a second window.

   Pass: the late player's picture starts within 3 seconds (OUT-5).

## M5: Android 1.0

### Record and stream together for 2 hours on charger power

You need the phone on a charger, or on a powered USB-C hub with the capture card. You also need the
computer with MediaMTX, ffplay and `adb`. It takes about two and a half hours, most of it waiting.

1. Plug the phone into its charger or the powered hub. Choose the capture card as the source, or the
   test pattern if the card isn't attached.
2. Start MediaMTX on the computer and use the SRT caller profile from M2.
3. Tap **Both**, then watch the stream with ffplay as in M2.
4. After 10 minutes, run this and note the TOTAL PSS figure:

   ```sh
   adb shell dumpsys meminfo org.example.projectname
   ```

5. Take the same reading after 1 hour and after 2 hours.
6. After 2 hours, tap **Both** to stop, and export the log.
7. Copy the recording to the computer and skip through it in VLC.

Pass: the app never crashes and the phone never shuts down from heat. The stream stays live and the
recording plays. The three memory readings stay roughly level instead of climbing each time. Put all
three in the issue.

## M6: iOS and iPadOS port

You need a USB-C iPad with a capture card and a powered USB-C hub, and an iPhone. The issue links a
TestFlight build to install.

Run the M1a, M1b, M2 and M3 tests again on the iPad with the capture card. On the iPhone, run the same
tests with the built-in cameras and skip the capture-card ones, because iPhones can't use USB cameras.
These parts work differently on iOS and iPadOS:

- Recordings are in the Files app, under On My iPad (or On My iPhone) > PROJECT_NAME (REC-3). Copy them
  to the computer with the Files app's share button.
- To force-stop the app, swipe it away in the app switcher. There's no `adb`.
- The app must stay on screen while it captures. iOS doesn't allow capture in the background.
- A capture card appears under the name iPadOS gives it, which may differ from its USB product name.

## M7: iOS inbound modes

Run the M4 tests on the iPad and the iPhone: the SRT listener first, then RTSP pull, then RTMP pull if
the build offers it. For each one, also open the URL the app shows in VLC.

Pass: VLC connects and plays in each mode. For the SRT listener, set the profile to no passphrase for
the VLC check, because VLC ignores options in an SRT URL.

## M8: optional extras

Each extra gets its own section here when work on it starts.
