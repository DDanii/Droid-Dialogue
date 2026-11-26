# Wyoming Legacy Satellite

A Home Assistant voice satellite for **old Android phones (Android 4.0 and newer, API 14)**.
It speaks the [Wyoming protocol](https://github.com/rhasspy/wyoming), so Home Assistant treats the phone
like any other satellite: it streams your voice after the wake word and plays the spoken reply.

The wake word is detected **on the phone**, in plain Java, from a few recordings of your own voice.

> **Status: written and tested on a desktop JVM and against stubs, not yet run on a real phone.**
> The protocol code is tested end to end against a fake Home Assistant client, and the wake word detector
> against synthetic speech. Real devices, real voices and a real Home Assistant will find things this
> could not. Please report what happens.

## What it is not

- The wake word detector is a **personal template matcher** (MFCC features and dynamic time warping), not a
  neural model like openWakeWord. It only knows the phrase *you* recorded, in *your* voice, and it is
  noticeably less reliable than openWakeWord, especially in noise or from across a big room.
- There is no echo cancellation. The detector pauses while a reply is playing, but speech recognition may
  still pick up the phone's own speaker if you turn the volume up high.
- It is not a launcher-style always-on app: it runs as a foreground service with a notification and holds
  a CPU and Wi-Fi wake lock, so keep the phone on charge.

## Install

Download the APK from the latest release (or from the artifacts of a build run), then:

```
adb install -r wyoming-legacy-satellite-*.apk
```

or copy it to the phone and open it (allow "Unknown sources" in the settings first).

## Set up

1. Open the app and tap **Start**. Allow the microphone permission if asked (Android 6 and newer).
2. The status shows the phone's address and port (default 10700).
3. In Home Assistant: **Settings, Devices & services, Add integration, Wyoming Protocol**, enter that
   address and port. On Android 4.1 and newer the phone also announces itself, so Home Assistant may offer
   it automatically.
4. In the new device's settings choose the Assist pipeline to use.
5. Train the wake word: tap **Record sample**, and within three seconds say your phrase. Do this at least
   **three times**, the same way each time, in the room where the phone will stand. Vary distance a little.
6. Say the phrase. The status line shows a **live score**; it must drop below the **threshold** for the phrase
   to be detected. If genuine attempts miss, raise the sensitivity (or record more samples). If it triggers on
   its own, lower the sensitivity or record cleaner samples. Saying the wrong words should keep the score
   well above the threshold.

**Talk now** starts a request without the wake word, which is handy to check the Home Assistant side first.
Reply volume follows the media volume keys. **Microphone gain** helps phones with a quiet mic.

## Build it yourself

Pushing to GitHub builds it automatically (`.github/workflows/build.yml`): the tests run, then an APK is
built and attached to the run. Push a tag like `v1.0.0` to publish a release.

Locally you need a JDK 17+, the Android SDK (`ANDROID_HOME`) with a platform and build-tools, and `zip`:

```
./test.sh     # desktop tests, no Android needed
./build.sh    # build/wyoming-legacy-satellite-<version>.apk
```

The build uses the SDK command line tools directly (javac, d8, aapt2, zipalign, apksigner) with `--min-api 14`
and has no dependencies beyond the Android framework. By default it signs with the debug key. For a key you
control, set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (and `KEY_PASSWORD`); in GitHub Actions set the
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` secrets.
Android only updates an app in place when it is signed with the same key.

## How it works

- `core/`: the Wyoming protocol and the satellite state machine. Home Assistant connects to the phone. It sends
  `describe`, the phone answers with `info` (a satellite called "Wyoming Legacy <model>"), and Home Assistant
  keeps the link alive with `ping` and `pong`. On the wake word the phone sends `detection`, `run-pipeline`
  (from speech recognition to speech output), `audio-start` and `audio-chunk`s of 16 kHz mono 16-bit audio.
  When Home Assistant sends `transcript` the phone stops streaming; the reply arrives as `audio-start`,
  `audio-chunk`s and `audio-stop`, and the phone answers `played` after it finished speaking.
- `wake/`: 25 ms frames every 10 ms become 12 MFCCs; a streaming DTW matcher compares them with your recorded
  templates and fires when the match is close enough and enough of the matched stretch was speech. The
  threshold comes from how much your own samples differ from each other, scaled by the sensitivity slider.
- `app/`: the foreground service, microphone capture (`AudioRecord`), reply playback (`AudioTrack`) and a
  small UI built in code. Everything newer than API 14 sits behind an SDK version check. Some old phones
  cannot record at 16 kHz; the app then falls back to 48, 44.1, 32 or 22.05 kHz and converts to 16 kHz itself
  (the status line shows which rate is in use).

Audio only leaves the phone after the wake word (or **Talk now**), goes only to the Home Assistant that is
connected, and stops when Home Assistant has the transcript.

## Troubleshooting

- *Home Assistant cannot connect:* check the address, port and that both are on the same network; some
  routers isolate Wi-Fi clients from each other.
- *Microphone unavailable:* another app is using the mic, or the permission was denied.
- *Nothing is heard after the wake word:* try **Talk now**; if that works the wake word needs more samples
  or a different sensitivity, if not look at the pipeline and last event lines in the status.
- *Android 14 and newer:* the microphone permission must be granted before **Start**.
