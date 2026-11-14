# AirPods hand-off between a Mac and an unrooted Android phone (roadmap #14)

Notes from building `macos/handoff` and LibrePods' `HandoffBeacon`, 14 Nov 2026. Devices: MacBook
(macOS 27.0.1), Pixel 10 Pro (Android 17), AirPods Pro 3 (firmware 9B47).

## Why not real two-device connection

AirPods hold two hosts only for hosts whose Bluetooth Device ID record carries Apple's vendor ID
(0x004C). On stock Android 14-17 that value comes from the system property
`bluetooth.device_id.vendor_id` (SELinux label `bluetooth_prop`), which the shell (adb, Shizuku)
can't set; no app API, DeviceConfig flag or overlay reaches it. On the 10 Pro only
`bluetooth.device_id.product_id=16662` is set. With one Apple host and one non-Apple host, the
AirPods keep whichever connected last and drop the other's link within about half a second.

## What the Mac can read

CoreBluetooth gives a Mac its own AirPods' proximity broadcasts with the private 16 bytes already
decrypted, and a name for the sender (others' AirPods have no name and stay encrypted). Layout
after `4c 00 07 19`: paired flag, model (little-endian, `27 20` = 0x2027), status (in-ear bits),
two battery bytes, lid, colour, host state, then 16 plain bytes: `[1]`,`[2]` bud batteries,
`[3]` case battery (ff unknown), `[4..6]` the last three bytes of the address of the host that
has them (`d6 8f 93` the Pixel, `0c c5 19` the Mac), `[7..10]` zero, the rest changing.

## Host state byte

Flags, not an enumeration. Seen: `00` no host; `04` host silent; `08` silent for a couple of
minutes on the Mac; `0c` for a moment after connecting; `05`, `09`, `0d` the same with media
playing (bit 0); `06`, `07` a call (bit 1). LibrePods' names (0x09 "HangingUp") didn't fit: the
AirPods report `09` while the Mac plays to them after a pre-positioned connection.

## Timings (median-ish, several runs each)

| Step | Time |
|---|---|
| Mac connects to AirPods idle on the phone (IOBluetooth `openConnection`) | 0.7-1.2 s |
| ...then macOS has them as the sound output | +0.1-0.2 s |
| Mac connects while the phone is still streaming | 1.7-2.6 s |
| Phone: page until ACL up | 0.2-1.5 s |
| Phone: ACL up until A2DP open | 0.65-1.3 s (1.8 s when the Mac was streaming) |
| Phone: A2DP open until Android lists the output | 0.35-0.45 s |
| Phone: play key until the stream starts | 0.3-0.5 s |
| AirPods report "idle" after pausing on Android | about 5 s |
| AirPods report "idle" after the Mac stops | 0.5-1.1 s |
| Beacon heard by the other side | 0.1-0.5 s |

End to end: phone idle, play on Mac about 1.3 s; Mac idle, play on phone about 2.1 s; pause
phone then play on Mac 6.7 s before the beacons, about 2.1 s after; phone taking from a Mac
that is playing about 5 s (1.5 s check plus a slow connect). The Mac releasing first, on the
phone's `taking` beacon, made little difference.

## Upstream bugs found in LibrePods v2

- `MediaController.initialize` assigned `musicPlayed` to itself, so the service's take-over
  callback was never stored.
- The playback callback returned early when the phone's own Bluetooth address was unknown
  (always, in v2), before the take-over check.
- The first playback callback after pressing play can list no players; the ones that do fell
  inside a 300 ms debounce. Media starting is now detected on every callback, by usage (media
  or game), so podcasts count.

## Not verified

Calls in either direction; the Mac taking from a phone that is playing (needs real input at the
Mac; logic only); whether MediaRemote's pause reaches every Mac player.
