# AirPods sensor research tools

Scripts used with the debug sensor probe in our build (`DEBUG_SENSORS`, logs under the
`SensorProbe` tag). Run them with `python3 -I <script> <log file>`.

- `sensor_descriptors.py`: decodes the sensor descriptors the AirPods send on connection
  (Apple OSSerializeBinary property lists with HID report descriptors).
- `motion_decode_check.py`: checks the head-motion (`DEVMOTION6`) decode by comparing the
  quaternion with the integrated rotation rate between still holds.

## Head-motion decode (AirPods Pro 3, firmware 9442752, checked 7 Nov 2026)

58-byte `DEVMOTION6` samples:

| Bytes | Content |
| --- | --- |
| 12-15 | uint32 LE timestamp, microseconds |
| 20, 22, 24 | int16 LE quaternion x, y, z over 32768; w implied (>= 0) |
| 26, 28, 30 | int16 LE rotation rate x, y, z; about 0.1125 deg/s per step, which matches 1/512 rad/s |

Over a 75 s head-turn test at 50 samples a second, the integrated rotation rate matched the
quaternion's rotation to a median 0.2 deg per move on all three axes.

The motion descriptor's `H2HTransformation` (8 bytes) is a unit quaternion: four int16 LE over
32768 in x, y, z, w order. Rotating the bud-frame axes by it turned measured yaw into
(-0.13, -0.11, 0.99) and pitch into (0.95, 0.17, 0.27): Apple's head frame, yaw about Z and pitch
about X. Each bud has its own, mirrored: on this pair the left bud (serial HY8H...) sent
`cc50d614c552af32` = (0.631, 0.163, 0.647, 0.396) and the right bud (H3TH...) sent
`cc502aeb3badaf32` = (0.631, -0.163, -0.647, 0.396). The descriptor comes from the bud that
streams motion (its `SerialNumber` says which), and the AirPods don't always send descriptors on
connecting, so ask with request_all_descriptors before relying on it.
