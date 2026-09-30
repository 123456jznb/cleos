"""
ku100_nearfield_circ360.npz (from github.com/Saekisui/binaural-voice, itself a format conversion of
Arend, Neidhardt & Poerschmann, Zenodo 2020, doi:10.5281/zenodo.4297951, CC BY 4.0) into the app's
own file: the three nearest distances only, each multiplied by the dataset's gain for it, as 16-bit
integers. Standard library only (no numpy here).

Layout (little-endian): "HRIR", int32 version=1, int32 rate, int32 distances, int32 azimuths,
int32 ears, int32 taps, float32 scale, float32 distance[distances], then int16
[distance][azimuth][ear][tap]; a value is int16 * scale. Azimuth in degrees counter-clockwise from
the front (90 = left); ear 0 is the left one.

    python tools/convert_hrir.py ku100_nearfield_circ360.npz app/src/main/assets/hrir/ku100_near.bin
"""
import array
import ast
import io
import math
import struct
import sys
import zipfile

SRC = sys.argv[1]
DST = sys.argv[2]
KEEP = 3  # 0.25, 0.5, 0.75 m: the app goes no further than 0.5 m and a little drift past it
# The gains the dataset's own notes give per distance (NF_Datasets_Gains_infos.pdf, as the source repo applies them).
GAINS = [1.00, 0.33, 0.25, 0.16, 0.095]


def npy(raw):
    assert raw[:6] == b"\x93NUMPY", "not an npy"
    major = raw[6]
    if major == 1:
        (hlen,) = struct.unpack("<H", raw[8:10])
        start = 10
    else:
        (hlen,) = struct.unpack("<I", raw[8:12])
        start = 12
    header = ast.literal_eval(raw[start:start + hlen].decode("latin1"))
    body = raw[start + hlen:]
    assert not header["fortran_order"]
    return header["descr"], header["shape"], body


with zipfile.ZipFile(SRC) as z:
    parts = {n: z.read(n) for n in z.namelist()}

descr, shape, body = npy(parts["ir.npy"])
assert descr == "<f4", descr
ir = array.array("f")
ir.frombytes(body)
assert len(ir) == math.prod(shape)
_, _, fs_body = npy(parts["fs.npy"])
fs_descr, _, _ = npy(parts["fs.npy"])
fs = struct.unpack("<q" if fs_descr in ("<i8",) else "<i", fs_body[:8 if fs_descr == "<i8" else 4])[0]
d_descr, d_shape, d_body = npy(parts["dists.npy"])
dists = array.array("d" if d_descr == "<f8" else "f")
dists.frombytes(d_body)
print("ir", shape, "fs", fs, "dists", list(dists))
nd, naz, near, taps = shape
assert (nd, naz, near) == (5, 360, 2) and fs == 48000
assert [round(x, 2) for x in dists] == [0.25, 0.5, 0.75, 1.0, 1.5]


def at(d, a, e, t):
    return ir[((d * naz + a) * near + e) * taps + t]


def energy(d, a, e):
    return sum(at(d, a, e, t) ** 2 for t in range(taps))


# Which ear is which: at 90 degrees (left, counter-clockwise from the front) the left ear hears far more.
for a in (0, 90, 180, 270):
    print("az", a, "left/right energy dB: %.1f" % (10 * math.log10(energy(0, a, 0) / energy(0, a, 1))))
assert energy(0, 90, 0) > 10 * energy(0, 90, 1), "ear 0 should be the left one"
assert energy(0, 270, 1) > 10 * energy(0, 270, 0)

kept = [at(d, a, e, t) * GAINS[d] for d in range(KEEP) for a in range(naz) for e in range(near) for t in range(taps)]
peak = max(abs(x) for x in kept)
scale = peak / 32767.0
q = array.array("h", (max(-32768, min(32767, round(x / scale))) for x in kept))
if sys.byteorder != "little":
    q.byteswap()
with open(DST, "wb") as f:
    f.write(b"HRIR")
    f.write(struct.pack("<6i", 1, fs, KEEP, naz, near, taps))
    f.write(struct.pack("<f", scale))
    f.write(struct.pack("<%df" % KEEP, *[dists[d] for d in range(KEEP)]))
    f.write(q.tobytes())
err = max(abs(kept[i] - q[i] * scale) for i in range(len(kept)))
print("wrote", DST, "values", len(q), "scale", scale, "max quantisation error %.2e (peak %.3f)" % (err, peak))
