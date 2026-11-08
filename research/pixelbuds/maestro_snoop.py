"""Decode Maestro (Pixel Buds) traffic from an Android btsnoop_hci.log.

HCI ACL -> L2CAP (PSM 3) -> RFCOMM -> HDLC U-frames -> pw_rpc RpcPacket -> protobuf payload.
Layouts from pbpctrl's libmaestro (hdlc/, pwrpc/, protocol/addr.rs).

Usage: python3 -I maestro_snoop.py btsnoop_hci.log [--all-dlci] [--raw]
"""
import datetime
import struct
import sys
import zlib
from collections import defaultdict

path = sys.argv[1]
SHOW_RAW = "--raw" in sys.argv

# ---- pw_rpc names -------------------------------------------------------------------------------
SERVICES = {
    "maestro_pw.Maestro": ["GetSoftwareInfo", "GetHardwareInfo", "SubscribeRuntimeInfo", "SetWallclock",
                           "SetWallClock", "WriteSetting", "ReadSetting", "SubscribeToSettingsChanges",
                           "SubscribeToOobeActions"],
    "maestro_pw.Multipoint": ["SubscribeToQuietModeStatus", "ForceMultipointSwitch"],
    "maestro_pw.EartipFitTest": ["StartTest", "EndTest", "SubscribeToResults"],
    "maestro_pw.JitterBuffer": ["SetJitterBufferSizePreference"],
    "maestro_pw.Dosimeter": ["FetchDailySummaries", "SubscribeToLiveDb"],
    "maestro_pw.HeadGesture": ["StartDetection", "SubscribeToResults", "EndDetection"],
    "maestro_pw.DynamicServerConfigService": ["SetConfig", "GetConfig", "SetOverrides", "GetOverrides"],
    "pw.log.Logs": ["Listen"],
    "pw.file.FileSystem": ["List", "Delete"],
    "a10a20.kpi.Kpi": ["KpiStream"],
    "pw.rpc.EchoService": ["Echo"],
    "pw.transfer.Transfer": ["Read", "Write", "GetResourceStatus"],
    "pw.software_update.BundledUpdate": ["GetStatus", "Start", "Verify", "Apply", "Abort", "Reset", "SetTransferred"],
    "a10a20.software_update.UpdateHelperService": ["EraseBundle", "RequestOtaRoleSwitch"],
    "hr.core.software_update.UpdateHelperService": ["EraseBundle", "RequestOtaRoleSwitch"],
}


def pw_hash(name):
    h = len(name) & 0xFFFFFFFF
    coef = 65599
    for ch in name:
        h = (h + coef * ord(ch)) & 0xFFFFFFFF
        coef = (coef * 65599) & 0xFFFFFFFF
    return h


SVC = {pw_hash(s): s for s in SERVICES}
MTH = {}
for s, ms in SERVICES.items():
    for m in ms:
        MTH[(pw_hash(s), pw_hash(m))] = m

PTYPE = {0: "REQUEST", 1: "RESPONSE", 2: "CLIENT_STREAM", 3: "SERVER_STREAM_END(old)", 4: "CLIENT_ERROR",
         5: "SERVER_ERROR", 6: "CANCEL(old)", 7: "SERVER_STREAM", 8: "CLIENT_STREAM_END"}
PEER = {0: "?", 1: "Host", 2: "Case", 3: "LeftBtCore", 4: "RightBtCore", 5: "LeftSensorHub",
        6: "RightSensorHub", 7: "LeftSpiBridge", 8: "RightSpiBridge", 9: "DebugApp", 10: "MaestroA",
        11: "LeftTahiti", 12: "RightTahiti", 13: "MaestroB"}

# ---- protobuf ------------------------------------------------------------------------------------


def varint(b, i):
    v = s = 0
    while True:
        if i >= len(b):
            raise ValueError("varint")
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        s += 7
        if not c & 0x80:
            return v, i


def pb_fields(b):
    i, out = 0, []
    while i < len(b):
        key, i = varint(b, i)
        f, wt = key >> 3, key & 7
        if f == 0:
            raise ValueError("field 0")
        if wt == 0:
            v, i = varint(b, i)
        elif wt == 1:
            if i + 8 > len(b):
                raise ValueError("f64")
            v = b[i:i + 8]
            i += 8
        elif wt == 2:
            n, i = varint(b, i)
            if i + n > len(b):
                raise ValueError("len")
            v = b[i:i + n]
            i += n
        elif wt == 5:
            if i + 4 > len(b):
                raise ValueError("f32")
            v = b[i:i + 4]
            i += 4
        else:
            raise ValueError("wiretype")
        out.append((f, wt, v))
    return out


def pb_show(b, depth=0):
    try:
        fields = pb_fields(b)
    except ValueError:
        return None
    parts = []
    for f, wt, v in fields:
        if wt == 0:
            parts.append(f"{f}:{v}")
        elif wt == 5:
            fl = struct.unpack("<f", v)[0]
            parts.append(f"{f}:f32({struct.unpack('<I', v)[0]:#x}|{fl:.4g})")
        elif wt == 1:
            parts.append(f"{f}:f64({struct.unpack('<Q', v)[0]})")
        else:
            inner = pb_show(v, depth + 1) if v and depth < 6 else None
            if inner is not None and inner != "":
                parts.append(f"{f}:{{{inner}}}")
            else:
                try:
                    t = v.decode("utf-8")
                    if t.isprintable() and t:
                        parts.append(f'{f}:"{t}"')
                        continue
                except UnicodeDecodeError:
                    pass
                parts.append(f"{f}:0x{v.hex()}")
    return " ".join(parts)


# ---- btsnoop -> ACL -> L2CAP ---------------------------------------------------------------------
data = open(path, "rb").read()
assert data[:8] == b"btsnoop\0", "not a btsnoop file"
off = 16
EPOCH = 0x00E03AB44A676000
acl_buf = {}            # (handle, dir) -> bytearray
l2_frames = []          # (t, dir, handle, cid, payload)
while off + 24 <= len(data):
    olen, ilen, flags, drops, ts = struct.unpack(">IIIIq", data[off:off + 24])
    off += 24
    pkt = data[off:off + ilen]
    off += ilen
    if not pkt or pkt[0] != 0x02 or len(pkt) < 5:
        continue
    t = (ts - EPOCH) / 1e6
    direction = "rx" if flags & 1 else "tx"     # rx = from the buds, tx = from the phone
    hw = struct.unpack("<H", pkt[1:3])[0]
    handle, pb = hw & 0x0FFF, (hw >> 12) & 3
    body = pkt[5:]
    key = (handle, direction)
    if pb in (0, 2):
        acl_buf[key] = bytearray(body)
    elif pb == 1 and key in acl_buf:
        acl_buf[key] += body
    else:
        continue
    buf = acl_buf[key]
    if len(buf) >= 4:
        ln, cid = struct.unpack("<HH", buf[:4])
        if len(buf) >= 4 + ln:
            l2_frames.append((t, direction, handle, cid, bytes(buf[4:4 + ln])))
            del acl_buf[key]

# L2CAP signalling: map CIDs to PSMs
psm_of = {}   # (handle, cid as it appears in packets towards that side) -> psm
pending = {}
for t, d, h, cid, p in l2_frames:
    if cid != 1:
        continue
    i = 0
    while i + 4 <= len(p):
        code, ident, ln = p[i], p[i + 1], struct.unpack("<H", p[i + 2:i + 4])[0]
        c = p[i + 4:i + 4 + ln]
        if code == 0x02 and len(c) >= 4:          # connection request: psm, scid
            psm, scid = struct.unpack("<HH", c[:4])
            pending[(h, ident)] = (psm, scid, d)
        elif code == 0x03 and len(c) >= 8:        # connection response: dcid, scid, result
            dcid, scid, result = struct.unpack("<HHH", c[:6])
            for (hh, idt), (psm, s, dreq) in list(pending.items()):
                if hh == h and s == scid:
                    # requester's cid = scid (packets to requester carry it), responder's cid = dcid
                    req_side = dreq
                    rsp_side = "rx" if dreq == "tx" else "tx"
                    psm_of[(h, scid, "rx" if req_side == "tx" else "tx")] = psm
                    psm_of[(h, dcid, req_side)] = psm
        i += 4 + ln

# ---- RFCOMM --------------------------------------------------------------------------------------
streams = defaultdict(bytearray)   # (handle, dlci, dir) -> bytes
events = []                        # (t, dir, handle, dlci, info)
for t, d, h, cid, p in l2_frames:
    if psm_of.get((h, cid, d)) != 3 or len(p) < 4:
        continue
    addr, ctrl = p[0], p[1]
    dlci = addr >> 2
    ln = p[2] >> 1
    i = 3
    if not p[2] & 1:
        ln |= p[3] << 7
        i = 4
    ftype = ctrl & 0xEF
    if ftype != 0xEF or dlci == 0:      # UIH data frames only
        continue
    if ctrl & 0x10:                      # credit byte present
        i += 1
    info = p[i:i + ln]
    if info:
        events.append((t, d, h, dlci, info))

# ---- HDLC per stream -----------------------------------------------------------------------------
out = []
hdlc_dlcis = defaultdict(int)
other = defaultdict(list)
state = {}
for t, d, h, dlci, info in events:
    k = (h, dlci, d)
    st = state.setdefault(k, {"buf": bytearray(), "esc": False, "in": False})
    for b in info:
        if b == 0x7E:
            fr = bytes(st["buf"])
            st["buf"] = bytearray()
            st["esc"] = False
            if len(fr) >= 6:
                body, crc = fr[:-4], struct.unpack("<I", fr[-4:])[0]
                if zlib.crc32(body) & 0xFFFFFFFF == crc:
                    hdlc_dlcis[k] += 1
                    # address: one-terminated varint
                    a = s = j = 0
                    while j < len(body):
                        a |= (body[j] >> 1) << s
                        s += 7
                        j += 1
                        if body[j - 1] & 1:
                            break
                    ctrlb = body[j]
                    out.append((t, d, dlci, a, ctrlb, body[j + 1:]))
                elif fr:
                    other[k].append((t, fr))
            continue
        if b == 0x7D:
            st["esc"] = True
            continue
        if st["esc"]:
            b ^= 0x20
            st["esc"] = False
        st["buf"].append(b)

print(f"{len(l2_frames)} L2CAP frames, RFCOMM data frames: {len(events)}")
for (h, dlci, d), n in sorted(hdlc_dlcis.items()):
    print(f"  handle {h:#x} DLCI {dlci} ({'buds->phone' if d == 'rx' else 'phone->buds'}): {n} Maestro frames")
plain = defaultdict(int)
for t, d, h, dlci, info in events:
    if (h, dlci, d) not in hdlc_dlcis:
        plain[(h, dlci, d)] += 1
for (h, dlci, d), n in sorted(plain.items()):
    print(f"  handle {h:#x} DLCI {dlci} ({d}): {n} non-HDLC frames")

calls = {}
print()
for t, d, dlci, a, ctrlb, payload in out:
    src, dst = PEER.get((a >> 6) & 0xF, "?"), PEER.get((a >> 10) & 0xF, "?")
    try:
        fields = {f: v for f, wt, v in pb_fields(payload)}
    except ValueError:
        print(datetime.datetime.fromtimestamp(t).strftime("%H:%M:%S.%f")[:-3], d, "bad rpc", payload.hex())
        continue
    ptype = fields.get(1, 0)
    ch = fields.get(2)
    sid = struct.unpack("<I", fields[3])[0] if 3 in fields else None
    mid = struct.unpack("<I", fields[4])[0] if 4 in fields else None
    body = fields.get(5, b"")
    status = fields.get(6)
    call = fields.get(7)
    sname = SVC.get(sid, f"svc{sid:#010x}" if sid is not None else "?")
    mname = MTH.get((sid, mid), f"m{mid:#010x}" if mid is not None else "?")
    ts = datetime.datetime.fromtimestamp(t).strftime("%H:%M:%S.%f")[:-3]
    arrow = "->" if d == "tx" else "<-"
    shown = pb_show(body) if body else ""
    if shown is None:
        shown = "0x" + body.hex()
    st = f" status={status}" if status else ""
    raw = f"  raw={body.hex()}" if SHOW_RAW and body else ""
    print(f"{ts} {arrow} ch{ch} {src}>{dst} {PTYPE.get(ptype, ptype)} {sname.split('.')[-1]}.{mname} call={call}{st}  {shown}{raw}")

# ---- non-Maestro RFCOMM channels (Fast Pair message stream etc.) ---------------------------------
if "--other" in sys.argv:
    print("\n# other RFCOMM channels")
    for t, d, h, dlci, info in events:
        if (h, dlci, d) in hdlc_dlcis:
            continue
        ts = datetime.datetime.fromtimestamp(t).strftime("%H:%M:%S.%f")[:-3]
        arrow = "->" if d == "tx" else "<-"
        extra = ""
        if len(info) >= 4:
            g, c, n = info[0], info[1], struct.unpack(">H", info[2:4])[0]
            if n == len(info) - 4:
                extra = f"  gfps group={g} code={c} data={info[4:].hex()}"
        txt = ""
        try:
            s = info.decode("ascii")
            if all(32 <= ord(ch) < 127 or ch in "\r\n" for ch in s):
                txt = "  text=" + repr(s)
        except UnicodeDecodeError:
            pass
        print(f"{ts} {arrow} DLCI{dlci} {info.hex()[:80]}{extra}{txt}")
