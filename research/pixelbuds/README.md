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

## What the captures show (2026-11-08, 6a, firmware 6.144)

Decoder: [`maestro_snoop.py`](maestro_snoop.py) (btsnoop → L2CAP → RFCOMM →
HDLC → pw_rpc → protobuf). Google's app keeps three RFCOMM channels open to the buds:

- **DLCI 3: Maestro.** It addresses each bud's BtCore separately (channel 19 left, 21 right). On
  connect: GetSoftwareInfo (all three `release_6.144`), GetHardwareInfo (serials for case, right
  and left; `MPR1` model strings), SubscribeRuntimeInfo (battery per part with charging state, and
  which buds are in the case), SubscribeToSettingsChanges, SetWallclock (Unix ms),
  `DynamicServerConfigService.SetConfig {1:1 2:1 3:0}`, JitterBuffer `{1:1}`, Dosimeter summaries,
  then ReadSetting for ids 13, 1, 3, 2, 4, 5, 11, 7, 16, 15, 17, 18, 12, 21-24, 26-32, 34-39, 19, 25.
  KPI telemetry streams constantly (218 messages in a few minutes).
- **DLCI 5: Fast Pair message stream** (battery `group 3 code 3`, `group 8` audio switching, and
  `group 7 code 64-66` with encrypted-looking payloads).
- **DLCI 9: another group/code channel with protobuf payloads**, which carries
  `google-pixel-buds-pro-v1` strings, firmware info (`group 2 code 4`, `group 3 code 2`) and state
  notifications (`group 4 code 5` went 03 → 05 → 06 during the script; `group 4 codes 18, 20, 22`).
  Not decoded yet; the noise-mode changes and head gestures in the script didn't show up on
  Maestro, so they are probably here.

Settings (`WriteSetting`/`ReadSetting` field 4 holds a SettingValue whose field number is the
setting id), from the script:

| Id | What | Values seen |
|---|---|---|
| 7 | Press-and-hold action per bud `{1: left {4:{1:x}}, 2: right {4:{1:x}}}` | 5 (ANC control), 6 (assistant), 7 (new: probably Gemini) |
| 11 | Multipoint | 0 / 1 |
| 13 | Noise mode (read) | 0 at first, 4 (Adaptive) later |
| 14 | OTTS mode, on only while the eartip fit test runs | 1 / 0 |
| 16 | EQ, five floats | presets wrote (-5, -1.5, 0, 0, 0) and (5, 3, 0, 0, 0) |
| 22 | Conversation detection (pbpctrl: speech detection) | 1 / 0 |
| 29 | New; toggled during step 7 or 8 | 1 / 2 |
| 23, 31 | New; messages `{4:{1:0}}` and `{1:f32 2:f32 3:5}` | read only |
| 1, 25 | Reading them fails with status 2 | |

Streams: EartipFitTest results are two floats per bud (e.g. 34 and 3; 38 and 5), with the OOBE
action stream open alongside it reporting 10 and 12 (left and right on head).
`Dosimeter.SubscribeToLiveDb` sends one float per bud per second.

## LibrePods talking to the buds (2026-11-08)

`adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_MAESTRO --ei seconds 5 -p me.kavishdevar.librepods.dev2`
(`services/MaestroProbe.kt`) opens the Maestro channel with `createRfcommSocketToServiceRecord`
and sends read-only calls.

- With Google's app connected: refused ("read failed, socket might closed"). One app at a time.
- After `am force-stop com.google.android.apps.wearables.maestro.companion`: connected, and both
  buds answered GetSoftwareInfo, GetHardwareInfo and ReadSetting 13 (= 4, Adaptive). The case
  (channel 18) didn't answer with the buds out.

## Open questions

1. ~~Can LibrePods open the Maestro RFCOMM channel while Google's app holds it?~~ No; see above.
   Google's app holds the channel whenever the buds are connected, and an ordinary app can't make
   it let go (force-stopping needs adb). So on a phone where LibrePods runs the buds over Maestro,
   Google's app has to be off the channel: disabled (`adb shell pm disable-user --user 0
   com.google.android.apps.wearables.maestro.companion`, undone with `pm enable`), at the cost of
   its firmware updates and settings until it's re-enabled. What still works alongside Google's
   app: the buds' BLE broadcasts (Fast Pair adverts carry battery), Android's own battery level,
   and anything on a phone where Google's app isn't connected to the buds.
2. ~~Does `MaestroEndpointService` accept calls from another app?~~ No. It is a gRPC-over-Binder
   server whose security policy is set per service: "internal-only" (the caller must run as
   Google's app) or a "1st-party only Allowlist" that also requires the caller to be signed by
   Google (it rejects others with "Not google-signed"; `com.google.android.apps.pixel.dcservice`
   is one allowlisted package). LibrePods can't pass either without root.
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

## Read-only support in LibrePods (2026-11-08)

Works with Google's Pixel Buds app uninstalled (Android's Bluetooth settings still show the buds'
settings: that page uses another Google channel, not Maestro).

- **Connection:** Pixel Buds Pro 2 connect when a bud leaves the case and disconnect as soon as it's
  back, even with the lid open. So connecting is never "lid open"; it goes straight to the island.
- **Android's own Disconnect** (measured 2026-11-08): A2DP and HFP go connected -> disconnecting
  while the link and the Maestro channel are still up, and Android drops the link ~1.4 s later.
  Putting the buds in the case is the reverse: the buds close Maestro (RFCOMM DISC from their side)
  and the link drops before the profiles report disconnecting. ACL_DISCONNECTED carries only the
  transport, no reason. LibrePods holds the buds when the link is up at "disconnecting" and still
  up 0.3 s later (AirPods keep their 1.5 s check).
- **Lid:** the buds' Fast Pair beacon (service data 0xFE2C, *extended* advertising on 1M/2M: Android
  only reports it to scans with `setLegacy(false)`) comes every few seconds with the lid closed and
  9-10 a second while it's open, alongside a Find My Device frame (0xFEAA, frame 0x40). Addresses
  rotate and the data is keyed to Google's account keys, so LibrePods can't prove the beacon is
  this pair. `PixelBudsLidWatcher` takes a close (-55 dBm or stronger) burst of 3 within 1.5 s with
  the 0xFEAA frame as the lid opening (measured: 0.2 s after the burst starts), and 3 s without one
  as it closing. It scans only with the screen on and the buds bonded but not connected.
- **Read over Maestro** once connected: firmware, serials, model and colour code, battery and
  charging per part, placement, noise mode and other settings. Runtime info is subscribed from both
  buds first.
- **Art:** Google's Pro 2 renders per colour, Google's bud and case symbols, and the case card's
  turntable rendered from Google's own 3D model (`turntable/`): Hazel buds, the case in the model's
  own white finish.
- The card used to close on a fast flick alone, so a system back swipe starting over it dismissed
  it; now a flick has to have moved it 40 px.

## Controls in LibrePods (2026-11-08)

`WriteSetting` is `{4: SettingValue}`, where the SettingValue's field number is the setting id. On
the 6a, firmware 6.144, writes to setting 13 (noise mode: off, transparency, adaptive, ANC) and 16
(EQ presets) all returned OK and took effect; the EQ was still set after reconnecting.

| id | what | encoding |
|----|------|----------|
| 2 | in-ear detection | bool |
| 4 | touch controls | bool |
| 7 | press and hold | `{1 left, 2 right}`, each `{4: {1: action}}`; 5 noise control, 6 assistant, 7 seen on these buds (likely Gemini), 1-4 notifications/previous/next/play-pause (not offered by Google) |
| 11 | multipoint | bool |
| 12 | modes the hold cycles through | `{1 ANC, 2 off, 3 transparency, 4 adaptive}` bools; read as `{1: 1, 2: 0, 3: 1}` |
| 13 | noise mode | 1 off, 2 ANC, 3 transparency, 4 adaptive (same numbering as AirPods) |
| 14 | test ("OTTS") mode | 1 while the fit test runs |
| 15 | volume EQ | bool |
| 16 | EQ | `{1 low bass, 2 bass, 3 mid, 4 treble, 5 upper treble}` floats, -6..6 |
| 17 | balance | 0..200: even = left by n/2, odd = right by n/2+1 (pbpctrl) |
| 19 | mono | bool |
| 21 | volume exposure notifications | bool |
| 22 | conversation detection | bool |

Google's EQ presets (low bass, bass, mid, treble, upper treble), from a tool description in its app:
Default 0/0/0/0/0, Balanced -3.5/0.5/1/-1/2.5, Clarity -2/0/2/3/5, Heavy bass 5/3/0/0/0,
Light bass -5/-1.5/0/0/0, Vocal boost -1/0/4/2/0.

**Names from Google's app** (2026-11-08): its SettingValue schema (class `sbr`: 39 oneof fields;
bool, enum, message, and field 17 a zigzag `sint32`) and its handlers (`gmt` "handleAllegroSetting",
`gsw`, and the writers in `goj`/`cxn`/`hdi`/`hei`/`hci`/`gzd`) give: 3 setup finished, 6 setup mode,
8 ANC accessibility mode, 9/10 noise mode with one/two buds (enums), 14 test mode (bool),
18 last saved EQ, 27 case sounds ("case earcon", the chimes other than a bud returning), 28 bud
return sound, 29 head gestures (enum, 1 off, 2 on; was 2), 34 "phone booth" (a call feature the
app can switch on after a delay), 36 sleep detection (sleep mode feature), 37 Live Translate
settings (message with an auto-ANC flag), 38 mic mute gesture (needs a Pixel ROM in Google's
app). `WriteSetting` itself is a oneof of five families (fields 1-5); 4 is these settings.
Still unnamed: 20, 23 (message), 24 and 26 (enums), 25, 30, 31 (message), 32 (written by the
app's settings writer), 33, 35 (message), 39.

Types of the unnamed ones (from the schema): 20, 25, 30, 32, 33, 39 are bools (20 and 25 don't
answer reads; on this pair 30=0, 32=1, 33=1, 39=0). 24 and 26 use the same three-value enum check
as head gestures (`a.aZ`: 0, 1, 2 valid), so likely the same "not set / off / on" switch; both 0.
23, 31 and 35 are messages whose classes are empty in Google's build (fields stripped as unread),
and nothing reads them back: 23 is shaped like one bud's press-and-hold entry (`{4: {1: 0}}`, an
unset gesture slot?), 31 is `{1: 0f, 2: 0f, 3: 5}`, 35 comes back empty.

Without Google's app, Android's Bluetooth settings page for the buds only offers a few options,
so naming the rest by toggling needs Google's app back (and then LibrePods loses Maestro to it).

Unnamed ids that answer (values on this pair): 3=1, 5=1, 6=0, 18=empty, 23=`{4: {1: 0}}`, 24=0,
26=0, 27=1, 28=1, 29=2, 30=0, 31=`{1: 0f, 2: 0f, 3: 5}`, 32=1, 33=1, 34=0, 35=empty, 36=1, 37=empty,
38 and up still to list. LibrePods logs every change (`setting N changed`), so toggling options
in Android's Pixel Buds settings page should name them.

**Ear tip fit test** (`maestro_pw.EartipFitTest`, per bud on its own channel): write setting 14 = 1,
wait 800 ms, set media volume to 53% of max (Google's `MaestroOtts__volume_ratio_lr`), play
`raw/etft_03` (a 13.7 s sound), wait 800 ms, then on each bud `StartTest` and only then
`SubscribeToResults` (empty messages; subscribing first ends the stream at once with status 9,
FAILED_PRECONDITION). Results are `{1: fit float, 2: noise float}`; afterwards `EndTest` and setting 14 = 0.
Google grades with flags `fit_test_good_criteria` 80, `fit_test_max_criteria` 200 and
`noise_test_max_criteria` 40 (255 = no reading) into good / needs adjustment / change ear tip /
noisy; the decompiled grading is garbled, so which way "fit" goes is still to confirm.

**Dosimeter:** `SubscribeToLiveDb` streams `{2: intensity}` (dB = 10·log10). `FetchDailySummaries`
returned `{1: 36493, 2: {1: day, 6: float}...}`; the day numbers (18240...) and floats are not
decoded yet.

**Other streams:** `SubscribeToOobeActions` reports on/off-head events (10 left on, 11 left off,
12 right on, 13 right off) outside setup too; taps were not seen yet. `Multipoint.SubscribeToQuietModeStatus`
sent `{1: 0}`. `HeadGesture.StartDetection` + `SubscribeToResults` (empty messages) should stream
`{1: gesture}`; not tried with nods yet.
