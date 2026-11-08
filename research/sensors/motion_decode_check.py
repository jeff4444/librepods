"""Check the head-motion decode (roadmap #9) from a SensorProbe log of DEVMOTION6 samples.

Decode under test, 58-byte samples:
  bytes 12-15  uint32 LE  timestamp in microseconds
  bytes 20/22/24  int16 LE  quaternion x, y, z over 32768 (w implied, >= 0)
  bytes 26/28/30  int16 LE  rotation rate x, y, z (scale to be measured; community says 0.122 deg/s per step)

Finds still holds (low rotation rate), reports each hold's rotation from the first one, and between
holds compares the gyro, integrated over time, with the rotation the quaternion moved through. That
gives the gyro scale per axis and checks that the two decodes agree.

Usage: python3 -I motion9.py probe.log
"""
import math
import re
import struct
import sys

rows = []
for line in open(sys.argv[1], errors="replace"):
    m = re.search(r"sample service=16\S* len=58 data=([0-9a-f]{116})\b", line)
    if m:
        rows.append(bytes.fromhex(m.group(1)))

def i16(b, o):
    return struct.unpack_from("<h", b, o)[0]

samples = []
for b in rows:
    if not any(b[20:26]):
        continue  # first sample after a (re)start is all zeros
    t = struct.unpack_from("<I", b, 12)[0] / 1e6
    x, y, z = (i16(b, o) / 32768 for o in (20, 22, 24))
    w = math.sqrt(max(0.0, 1 - x * x - y * y - z * z))
    g = [i16(b, o) for o in (26, 28, 30)]
    samples.append((t, (w, x, y, z), g, x * x + y * y + z * z))

# unwrap the 32-bit microsecond clock
out, offset, prev = [], 0.0, None
for t, q, g, n in samples:
    if prev is not None and t + offset < prev - 1000:
        offset += 2 ** 32 / 1e6
    out.append((t + offset, q, g, n))
    prev = t + offset
samples = out
if not samples:
    sys.exit("no samples")
t0 = samples[0][0]
dts = [b[0] - a[0] for a, b in zip(samples, samples[1:])]
print(f"{len(samples)} samples over {samples[-1][0] - t0:.1f} s; median step {sorted(dts)[len(dts)//2]*1000:.1f} ms")
print(f"|xyz|^2 range {min(s[3] for s in samples):.3f}..{max(s[3] for s in samples):.3f} (must stay <= 1 for an implied w)")

def qmul(a, b):
    aw, ax, ay, az = a; bw, bx, by, bz = b
    return (aw*bw - ax*bx - ay*by - az*bz, aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx, aw*bz + ax*by - ay*bx + az*bw)

def qconj(q):
    return (q[0], -q[1], -q[2], -q[3])

def rotvec(q):
    """Rotation vector (degrees) of a unit quaternion."""
    w, x, y, z = q
    if w < 0:
        w, x, y, z = -w, -x, -y, -z
    s = math.sqrt(x * x + y * y + z * z)
    ang = 2 * math.degrees(math.atan2(s, w))
    return [0.0, 0.0, 0.0] if s < 1e-9 else [ang * c / s for c in (x, y, z)]

# still holds: at least 0.8 s with every rate axis under the threshold
STILL = 25
holds, cur = [], []
for i, (t, q, g, n) in enumerate(samples):
    if max(abs(v) for v in g) < STILL:
        cur.append(i)
    else:
        if cur and samples[cur[-1]][0] - samples[cur[0]][0] >= 0.8:
            holds.append(cur)
        cur = []
if cur and samples[cur[-1]][0] - samples[cur[0]][0] >= 0.8:
    holds.append(cur)
print(f"\n{len(holds)} still holds")

ref = samples[holds[0][len(holds[0]) // 2]][1] if holds else samples[0][1]
print("hold   time        angle from first hold   rotation vector from first hold (deg, x y z)")
mids = []
for h in holds:
    i = h[len(h) // 2]
    q = samples[i][1]
    rv = rotvec(qmul(qconj(ref), q))
    ang = math.sqrt(sum(c * c for c in rv))
    mids.append(i)
    print(f"  {samples[h[0]][0]-t0:6.1f}-{samples[h[-1]][0]-t0:5.1f}s   {ang:6.1f}°   [{rv[0]:7.1f} {rv[1]:7.1f} {rv[2]:7.1f}]")

# between consecutive holds: integrate the gyro (LSB*s) and compare with the quaternion's rotation
print("\nmove   quaternion rotation (deg x y z)    gyro integral (LSB*s x y z)    implied deg/s per LSB")
pairs = []
for a, b in zip(mids, mids[1:]):
    qa, qb = samples[a][1], samples[b][1]
    rv = rotvec(qmul(qconj(qa), qb))
    integ = [0.0, 0.0, 0.0]
    for k in range(a + 1, b + 1):
        dt = samples[k][0] - samples[k - 1][0]
        if 0 < dt < 0.5:
            g = samples[k][2]
            integ = [integ[j] + g[j] * dt for j in range(3)]
    ratio = [rv[j] / integ[j] if abs(integ[j]) > 50 else None for j in range(3)]
    pairs.append((rv, integ))
    rs = " ".join(f"{r:6.3f}" if r is not None else "   -  " for r in ratio)
    print(f"  {samples[a][0]-t0:5.1f}->{samples[b][0]-t0:5.1f}s  [{rv[0]:7.1f} {rv[1]:7.1f} {rv[2]:7.1f}]   [{integ[0]:8.0f} {integ[1]:8.0f} {integ[2]:8.0f}]   {rs}")

# robust scale: per move, the dominant axis only, for moves of 15-120 degrees
ratios = []
for rv, it in pairs:
    ang = sum(c * c for c in rv) ** 0.5
    j = max(range(3), key=lambda k: abs(it[k]))
    if 15 <= ang <= 120 and abs(it[j]) > 50:
        ratios.append((j, rv[j] / it[j]))
if ratios:
    vals = sorted(r for _, r in ratios)
    print(f"\ndominant-axis scale over {len(vals)} moves: median {vals[len(vals)//2]:.4f} deg/s per LSB "
          f"(range {vals[0]:.4f}..{vals[-1]:.4f}); axes used: {sorted(set('xyz'[j] for j, _ in ratios))}")

# one scale for all axes by least squares
num = sum(rv[j] * it[j] for rv, it in pairs for j in range(3))
den = sum(it[j] * it[j] for _, it in pairs for j in range(3))
if den > 0:
    s = num / den
    res = [math.sqrt(sum((rv[j] - s * it[j]) ** 2 for j in range(3))) for rv, it in pairs]
    print(f"\nbest single scale: {s:.4f} deg/s per LSB (full scale ±{s*32768:.0f} deg/s); "
          f"median mismatch per move {sorted(res)[len(res)//2]:.1f}°")
