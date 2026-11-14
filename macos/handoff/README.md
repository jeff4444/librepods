# LibrePods Handoff (Mac)

The Mac's half of sharing one pair of AirPods between a Mac and an Android phone running
LibrePods, without rooting the phone (roadmap #14). A small menu-bar app.

AirPods only stay connected to two devices at once when both present Apple's Bluetooth vendor
ID, which a stock Android phone can't do without root. So this doesn't keep both connected: it
moves the AirPods to the right device automatically, in about 1.3 s to the Mac and about 2 s to
the phone, and ahead of time when it can.

## Build and install

Needs only the Xcode Command Line Tools (`xcode-select --install`), macOS 14.2 or later.

    ./build.sh install     # builds and copies it to ~/Applications
    open ~/Applications/"LibrePods Handoff.app"

Allow Bluetooth when macOS asks. The AirPods must already be paired with the Mac. Turn on
**Open at Login** in its menu to keep it running. On the phone, use a LibrePods build with
`HandoffBeacon.kt` (this fork).

## How it decides

Three sources of information:

- **The AirPods' own broadcasts.** macOS hands its own AirPods' Bluetooth LE "proximity"
  broadcasts to apps already decrypted: whether they're worn, a state byte for their current
  host (flags: bit 0 playing, bit 1 call) and the last three bytes of that host's address.
- **A direct signal between the Mac and the phone** (`Beacon.swift`, `HandoffBeacon.kt`): each
  broadcasts one service UUID saying `busy`, `idle`, `away` or `taking`. This is quicker than the
  AirPods' word: after pausing on Android they keep reporting "playing" for about five seconds.
- **Whether you're at this Mac**: the time since the last key press or mouse move.

Rules, the same on both sides, by priority (a call beats media, media beats idle, short sounds
count for nothing):

| When | Result |
|---|---|
| The other device is on a call | Never take |
| A short sound (notification, beep) | Never moves the AirPods |
| Play starts, the other device is idle | Take: at once if you just touched this device, otherwise once the sound has lasted 1.5 s |
| Play starts, the other device is playing | Newest play wins, only if you started it and it lasts 1.5 s |
| The other device takes them straight back mid-song | Don't interrupt it again for 5 minutes |
| Nothing playing, you're using this device, the other is put away | Move the AirPods here ahead of time |
| The AirPods leave while this Mac is playing | Pause, and mute the speakers until the sound stops |
| The Mac goes to sleep | Let the AirPods go |

A device says `away` only once it has been silent for a minute and isn't being used, so a press
on the AirPods' stem can still resume what was paused there.

## Menu

- **Connect to This Mac / Release to the Phone**: by hand.
- **Switch When Something Plays**, **Take Them Even While the Phone Is Playing**,
  **Follow the Device I'm Using**: the three behaviours above.
- **Show Log**: `~/Library/Logs/LibrePodsHandoff.log` says what it did and why.

## Testing from a terminal

The app's binary also runs single commands (start it with `open` so macOS treats it as the app
for Bluetooth permission):

    open -W -n --stdout /tmp/out.txt ~/Applications/"LibrePods Handoff.app" --args watch 30

`status`, `audio`, `scan SECONDS`, `watch SECONDS`, `connect`, `disconnect`.

## Limits

- One pair of AirPods (the first paired device named "AirPods"; set another with
  `defaults write me.kavishdevar.librepods.handoff address AA-BB-CC-DD-EE-FF`).
- Pausing the Mac's player uses the system's private MediaRemote framework; if a player ignores
  it, the speakers still stay muted until it stops.
- Built and tested with one Mac (macOS 27), one Pixel 10 Pro (Android 17) and AirPods Pro 3
  (firmware 9B47).
