# Pixel Buds Pro 2 research (roadmap #7, #15)

Notes for bringing Pixel Buds Pro 2 into LibrePods. Sources: Google's Pixel Buds app
(`com.google.android.apps.wearables.maestro.companion` 1.0.996096625, pulled from the 6a and
decompiled locally; the decompiled code is not kept in this repo), pbpctrl
(<https://github.com/qzed/pbpctrl>, Apache-2.0/MIT, commit 2620367), and HCI snoop captures.

## The pair under test

- Pixel Buds Pro 2, "Jeff's Pixel Buds Pro 2", Bluetooth class `0x244404` (pbpctrl's
  `PIXEL_BUDS2_CLASS`; the first Pro is `0x240404`).
- Firmware `release_6.144` on the left bud, right bud and case (2026-11-08).
- Paired to the Pixel 6a (lab) and the Pixel 10 Pro.

## How the app reaches the buds

- **Maestro**, Google's settings protocol: an RFCOMM channel found through SDP, opened with the
  ordinary `createRfcommSocketToServiceRecord` (no hidden API).
  - Pro line UUID `25e97ff7-24ce-4c4c-8951-f764a708f7b5`; A-Series
    `3a046f6d-24d2-7655-6534-0d7ecb759709`. The app also accepts the byte-reversed forms
    (`b5f708a7-64f7-5189-4c4c-ce24f77fe925`, `099775cb-7e0d-3465-5576-d2246d6f043a`) and maps them
    back.
  - Framing: Pigweed RPC (`pw_rpc`) packets inside HDLC U-frames; see pbpctrl's `libmaestro`
    (`hdlc/`, `pwrpc/`, `protocol/addr.rs` for the channel ids). Messages are protobuf.
  - pw_rpc identifies methods by a hash of the name, so spelling matters: the app calls
    `SetWallclock`, where pbpctrl's proto says `SetWallClock`.
- **Google Fast Pair message stream**, RFCOMM `df21fe2c-2515-4fdb-8886-f12c4d67927c`: battery per
  bud and case, ANC change events, multipoint switching (pbpctrl `docs/Notes.md`).
- The app also references the HID UUID `00001124-…` and `5e8945b0-9525-11e3-a5e2-0800200c9a66`
  (purpose not yet known).

## RPC services the app uses

Everything the app calls over Maestro, by service. Services marked * are not in pbpctrl.

| Service | Methods |
|---|---|
| `maestro_pw.Maestro` | GetSoftwareInfo, GetHardwareInfo, SubscribeRuntimeInfo (stream: battery, in-case), SetWallclock, ReadSetting, WriteSetting, SubscribeToSettingsChanges (stream), SubscribeToOobeActions (stream: every tap, hold, swipe and on/off-head change, per bud) |
| `maestro_pw.Multipoint` | SubscribeToQuietModeStatus (stream), ForceMultipointSwitch |
| `maestro_pw.EartipFitTest` | StartTest, SubscribeToResults (stream), EndTest |
| `maestro_pw.Dosimeter` | FetchDailySummaries, SubscribeToLiveDb (stream: live sound level) |
| `maestro_pw.JitterBuffer` | SetJitterBufferSizePreference |
| `maestro_pw.HeadGesture` * | StartDetection, SubscribeToResults (stream), EndDetection: nod and shake |
| `maestro_pw.DynamicServerConfigService` * | SetConfig, GetConfig, SetOverrides, GetOverrides: firmware feature config |
| `pw.log.Logs` * | Listen (stream): the buds' own firmware log |
| `pw.file.FileSystem` * | List (stream), Delete |
| `a10a20.kpi.Kpi` * | KpiStream (stream): telemetry |
| `pw.rpc.EchoService` * | Echo: a harmless first call to prove a connection works |
| `pw.transfer.Transfer`, `pw.software_update.BundledUpdate`, `a10a20.software_update.UpdateHelperService`, `hr.core.software_update.UpdateHelperService` (EraseBundle, RequestOtaRoleSwitch) * | firmware updates; leave alone |

pbpctrl's `libmaestro/proto/maestro_pw.proto` has the message layouts it worked out for the
Maestro, Multipoint, EartipFitTest, JitterBuffer and Dosimeter services, including setting ids 1
to 22 (ANC mode and gesture loop with Adaptive, EQ, ear detection, multipoint, balance, mono,
speech detection, ...). The app's protobuf classes are obfuscated with field names stripped, so
new messages have to come from captures.

## App features (from its feature keys and screens)

Adaptive ANC, audio sharing, call mute (tap to mute and unmute), head gestures (nod to answer,
shake to dismiss, with a tutorial and a "listen for three chimes" calibration), Live Translate,
sleep sync, eartip seal test, hearing wellness (dosimeter, exposure limit notifications), EQ with
bass presets, spoken notifications, find my device, case sounds, head tracking for spatial audio
("Audio changes as you move your head").

## App components worth a look

- `grpc.ondevicegrpcserver.MaestroEndpointService`: an on-device gRPC server over Binder, exported
  with no manifest permission. If its in-code security policy lets other apps in, LibrePods could
  talk to the buds through Google's app instead of competing for the RFCOMM channel. Untested.
- `settingprovider.service.MaestroDeviceSettingsProviderService` (needs BLUETOOTH_PRIVILEGED: the
  system's Bluetooth settings page), `companiondevice.MaestroCompanionDeviceService` (a
  CompanionDeviceManager association), `slices.MaestroSliceProvider` (exported).

## Open questions

1. Can LibrePods open the Maestro RFCOMM channel while Google's app holds it, or does one have to
   let go?
2. Does `MaestroEndpointService` accept calls from another app?
3. ~~Is the head tracker a sensor ordinary apps can read (roadmap #7)?~~ Not directly. While the
   buds are connected, `dumpsys sensorservice` lists "Jeff's Pixel Buds Pro 2", vendor
   "Bluetooth 00e0:00e0", type `android.sensor.head_tracker` (37), dynamic, continuous 10 to
   100 Hz, no permission. But `getDynamicSensorList(TYPE_HEAD_TRACKER)` and
   `getSensorList(TYPE_HEAD_TRACKER)` return nothing to LibrePods (2026-11-08, 6a, Android 17):
   the system keeps head trackers for itself. Probe:
   `adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_HEAD_TRACKER --ei seconds 20 -p me.kavishdevar.librepods.dev2`.
   Still to try: whether the shell user (adb, Shizuku) can read it, the head tracker's HID reports
   in a snoop capture taken with head tracking on, and Maestro's HeadGesture stream.
4. What do `DynamicServerConfigService.GetConfig/GetOverrides`, `pw.log.Logs` and
   `pw.file.FileSystem` return?
