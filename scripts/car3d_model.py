#!/usr/bin/env python3
"""Convert the Sketchfab "2022 Hyundai Veloster N" glTF into the 내 차 (3D car) screen's asset.

    python3 scripts/car3d_model.py <unzipped glTF folder> [--out apps/dashboard/assets/car3d] [--preview out.png]

Model: "2022 Hyundai Veloster N" by Ddiaz Design,
https://sketchfab.com/3d-models/2022-hyundai-veloster-n-d85ea2322d814899ad5905210f2c0e5a, CC BY-NC-SA 4.0. The
converted mesh and lamp textures written here are an adaptation of it and stay under CC BY-NC-SA 4.0
(apps/dashboard/assets/car3d/LICENSE.txt); this script itself is MIT like the rest of the repository.

What the conversion does, for a Telechips tcc893x (GLES 2.0) at 800x480:
  * metres, origin at the ground under the middle of the car, +Z forward, +Y up, +X the car's left (the glTF's own axes)
  * drops the engine and the parts under the bonnet, decimates the rest except the paint (about 406k -> ~116k triangles)
  * bakes base-colour textures into vertex colours; only the lamp atlas stays a texture (LED detail)
  * splits the four wheels (tyre, rim, disc: spin + steer) from the callipers (steer only)
  * adds the two number plates the model lacks (a Korean long plate, 520 x 110 mm, on a thin black holder): the front
    one on the grille under the badge, the rear one on the bumper's black lower part between the reflectors (both
    fixed; checked against owner photos of a Korean Veloster N), each laid on the surface it sits on; the app draws the
    plate picture (CarPlate)
  * splits the parts that open on a hinge (the three doors, bonnet, hatch, both mirrors) with their glass and lamps, and
    keeps a decimated engine bay that the app draws only while the bonnet is open
  * sorts every lamp triangle into a lamp group from the colour it glows in the emissive map and where it sits:
    white front = headlamp, amber = turn signal (side from X), red rear = tail / stop, spoiler = high-mount stop,
    white rear = reversing lamp

Needs numpy, scipy, Pillow and fast-simplification (development only; the app just reads the output).

File format (little endian), assets/car3d/veloster_n.bin:
  "VN3D" u32 version=1, u32 parts
  per part: u8 program (0 body, 1 lamp, 2 glass, 3 number plate), u8 motion (0 fixed, 1 wheel, 2 calliper, 3 hinge), u8 corner (wheel /
            calliper: 0 FL, 1 FR, 2 RL, 3 RR; hinge: the HINGES index), u8 0, f32 pivot x y z (wheel centre / hinge point;
            the part's vertices are relative to it), u32 vertices, u32 indices, vertices*28 bytes, indices*u16 (padded to 4)
  vertex (28 bytes): f32 x y z, s16 nx ny nz (/32767), u16 lamp group (8 = body paint), u8 r g b a (body: a = gloss, glass: a = opacity),
          u16 u v (/65535; number plates: 0..1 across the plate as seen from outside, u to the right, v down)
  then u32 spots, per spot: u8 lamp group, u8 hinge + 1 (0: on the body), 2 x u8 0, f32 x y z, f32 normal x y z,
            f32 radius (glow halos)
"""
import argparse
import json
import os
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image

VERSION = 3
BODY, LAMP, GLASS, PLATE = 0, 1, 2, 3
FIXED, WHEEL, CALLIPER, HINGE = 0, 1, 2, 3
FL, FR, RL, RR = 0, 1, 2, 3
# Hinged parts (CarModel / CarState use the same numbers): the glTF node name that marks each. The Veloster has one door
# on the left (driver) and two on the right; the roof spoiler (with the high-mount stop lamp) sits on the hatch. ENGINE is
# not hinged: the bay, drawn only while the bonnet is open.
DOOR_L, DOOR_RF, DOOR_RR, BONNET, HATCH, MIRROR_L, MIRROR_R, ENGINE = range(8)
HINGE_NODES = (("SK_Door_FL_", DOOR_L), ("SK_Door_FR_", DOOR_RF), ("SK_Door_BR_", DOOR_RR), ("SK_Hood_", BONNET),
               ("SK_Trunk_", HATCH), ("SK_Spoiler_", HATCH), ("SM_SideMirror_L_", MIRROR_L), ("SM_SideMirror_R_", MIRROR_R), ("SM_Engine_", ENGINE))
# The engine bay and the bonnet's underside are seen only with the bonnet open: kept this coarse (fraction of triangles).
ENGINE_KEEP, BONNET_UNDER_KEEP = 0.08, 0.15
# Lamp groups (CarScene.java uses the same numbers).
NONE, HEAD, POSITION, TAIL, STOP, TURN_L, TURN_R, REVERSE = 0, 1, 2, 3, 4, 5, 6, 7
PAINT = 8   # body vertices that take the chosen factory colour (CarPaint)

SCALE = 100.0   # the glTF is in 1/100 m
DROP_MATERIALS = {"Velosterdummy_material_4_014", "Velosterdummy_material_5_010", "Velosterdummy_material_6_010"}
DROP_NODES = ()
UNDER_BONNET = {"Engine_Plas", "Engine", "Engine_a"}
# Fraction of triangles kept, by material (1 = untouched).
# The paint keeps every triangle and the modeller's normals: decimated paint shows ripples in the reflection.
KEEP = {"CarPaint": 1.0, "material": 0.12, "RimHub": 0.22, "Calipers": 0.35, "tire": 0.7, "Interior": 0.05,
        "Interior_leather": 0.09, "InteriorPlatic": 0.10, "Interior_SH": 0.15, "Leather_normal": 0.2,
        "Interior_A": 0.3, "Light": 1.0, "Glass_light": 0.7, "Windowd": 1.0}
# Gloss (vertex alpha of body parts): how much of the fake sky reflection a material shows.
GLOSS = {"CarPaint": 1.0, "mirror": 1.0, "RimHub": 0.6, "Calipers": 0.5, "Carbon": 0.45, "material": 0.25,
         "tire": 0.05, "Display": 0.3}
# Flat colours where the texture average is misleading or there is none.
COLOR = {"mirror": (0.75, 0.78, 0.82), "Glass_light": (0.9, 0.92, 0.95), "Windowd": (0.02, 0.025, 0.03)}
# Opacity of the glass parts (the glTF lens is 55 % black, which would hide the lamps).
OPACITY = {"Glass_light": 0.12, "Windowd": 0.62}

CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}


def quat_mat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def local(n):
    if 'matrix' in n:
        return np.array(n['matrix'], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4)
    if 'scale' in n:
        m = np.diag(list(n['scale']) + [1]) @ m
    if 'rotation' in n:
        r = np.eye(4); r[:3, :3] = quat_mat(n['rotation']); m = r @ m
    if 'translation' in n:
        t = np.eye(4); t[:3, 3] = n['translation']; m = t @ m
    return m


def load(folder):
    g = json.load(open(folder / 'scene.gltf'))
    buf = (folder / g['buffers'][0]['uri']).read_bytes()

    def acc(i):
        a = g['accessors'][i]; v = g['bufferViews'][a['bufferView']]; n = NC[a['type']]; dt = np.dtype(CT[a['componentType']])
        off = v.get('byteOffset', 0) + a.get('byteOffset', 0); stride = v.get('byteStride', 0) or n * dt.itemsize
        raw = np.frombuffer(buf, dtype=np.uint8, count=stride * (a['count'] - 1) + n * dt.itemsize, offset=off)
        return np.lib.stride_tricks.as_strided(raw, shape=(a['count'], n * dt.itemsize), strides=(stride, 1)).copy().view(dt).reshape(a['count'], n)

    def tex(mat, key):
        info = mat.get(key) or mat.get('pbrMetallicRoughness', {}).get(key)
        if not info:
            return None
        uri = g['images'][g['textures'][info['index']]['source']]['uri']
        return np.asarray(Image.open(folder / uri).convert('RGBA')).astype(np.float64) / 255

    prims = []

    def walk(i, M):
        n = g['nodes'][i]; W = M @ local(n); name = n.get('name', '')
        for p in (g['meshes'][n['mesh']]['primitives'] if 'mesh' in n else []):
            at = p['attributes']; mat = g['materials'][p['material']]
            P = acc(at['POSITION']).astype(np.float64); N = acc(at['NORMAL']).astype(np.float64)
            UV = acc(at['TEXCOORD_0']).astype(np.float64) if 'TEXCOORD_0' in at else np.zeros((len(P), 2))
            I = acc(p['indices']).reshape(-1, 3).astype(np.int64)
            P = (np.c_[P, np.ones(len(P))] @ W.T)[:, :3] * SCALE
            N = N @ np.linalg.inv(W[:3, :3]); N /= np.linalg.norm(N, axis=1, keepdims=True) + 1e-12
            if np.linalg.det(W[:3, :3]) < 0:
                I = I[:, ::-1]
            pbr = mat.get('pbrMetallicRoughness', {})
            prims.append(dict(node=name, mat=mat['name'], P=P, N=N, UV=UV, I=I,
                              factor=np.array(pbr.get('baseColorFactor', [1, 1, 1, 1])),
                              base=tex(mat, 'baseColorTexture'), emissive=tex(mat, 'emissiveTexture'),
                              blend=mat.get('alphaMode') == 'BLEND'))
        for c in n.get('children', []):
            walk(c, W)

    for r in g['scenes'][0]['nodes']:
        walk(r, np.eye(4))
    return prims


def sample(img, uv):
    h, w = img.shape[:2]
    x = np.clip(((uv[:, 0] % 1.0) * w).astype(int), 0, w - 1)
    y = np.clip(((uv[:, 1] % 1.0) * h).astype(int), 0, h - 1)
    return img[y, x]


def hinge_of(node):
    """The hinged part (HINGES index) a glTF node belongs to, or None for the body."""
    for key, h in HINGE_NODES:
        if key in node:
            return h
    return None


def hinge_pivots(prims):
    """Hinge points (already offset to the car's origin), estimated from each part's painted skin: doors at the outer
    front edge, the bonnet at its rear edge, the hatch at its top (roof) edge, the mirrors at the inner base."""
    out = {}
    for h in (DOOR_L, DOOR_RF, DOOR_RR, BONNET, HATCH, MIRROR_L, MIRROR_R):
        own = [p for p in prims if hinge_of(p['node']) == h and 'SK_Spoiler_' not in p['node']]   # the hatch's hinge from the hatch alone
        pts = [p['P'] for p in own if p['mat'] not in DROP_MATERIALS]
        skin = [p['P'] for p in own if p['mat'] in ('CarPaint', 'material')]
        P = np.vstack(skin or pts); A = np.vstack(pts)
        if h in (DOOR_L, DOOR_RF, DOOR_RR):
            front = P[P[:, 2] > P[:, 2].max() - 0.06]
            outer = np.percentile(np.abs(front[:, 0]), 90) * np.sign(front[:, 0].mean())
            out[h] = np.array([outer, (A[:, 1].min() + A[:, 1].max()) / 2, np.percentile(front[:, 2], 70)])
        elif h == BONNET:
            rear = P[P[:, 2] < P[:, 2].min() + 0.05]
            out[h] = np.array([0.0, np.percentile(rear[:, 1], 90), np.percentile(rear[:, 2], 30)])
        elif h == HATCH:
            top = P[P[:, 1] > P[:, 1].max() - 0.04]
            out[h] = np.array([0.0, np.percentile(top[:, 1], 70), np.percentile(top[:, 2], 85)])
        else:
            inner = A[np.abs(A[:, 0]) < np.abs(A[:, 0]).min() + 0.05]
            out[h] = np.array([np.sign(A[:, 0].mean()) * np.percentile(np.abs(inner[:, 0]), 30),
                               np.median(inner[:, 1]), np.percentile(inner[:, 2], 75)])
    out[ENGINE] = np.zeros(3)
    return out


# Number plates (Korean long plate): size, the holder around it, and where each sits (centre height, side).
PLATE_W, PLATE_H, HOLDER_W, HOLDER_H = 0.520, 0.110, 0.540, 0.130
PLATES = ((+1, 0.465, 0.012), (-1, 0.510, 0.004))   # (+1 front / -1 rear, centre y, gap off the surface)
# The rear plate's two lamps (light with the tail lamps): on the bumper just above the plate, shining down onto it. The
# rear plate's vertices carry the POSITION lamp group (lit with the tail lamps, not brighter when braking).
PLATE_LAMP_X, PLATE_LAMP_UP = 0.12, 0.018
HOLDER_RGBA = (0.035, 0.035, 0.04, 0.3)


def surface_z(prims, side, xs, ys):
    """The outermost body surface (front: largest z, rear: smallest) along +-Z at each (x, y): a ray cast against every
    body triangle (doors, bonnet, hatch, mirrors and the engine are left out); NaN where nothing is hit."""
    T = np.vstack([p['P'][p['I']] for p in prims if p['mat'] not in DROP_MATERIALS and hinge_of(p['node']) is None])
    T = T[(np.sign(T[:, :, 2].mean(1)) == side)]
    out = np.full((len(ys), len(xs)), np.nan)
    a, b, c = T[:, 0, :2], T[:, 1, :2], T[:, 2, :2]
    den = (b[:, 1] - c[:, 1]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 1] - c[:, 1])
    ok = np.abs(den) > 1e-12
    T, a, b, c, den = T[ok], a[ok], b[ok], c[ok], den[ok]
    for j, y in enumerate(ys):
        for i, x in enumerate(xs):
            l1 = ((b[:, 1] - c[:, 1]) * (x - c[:, 0]) + (c[:, 0] - b[:, 0]) * (y - c[:, 1])) / den
            l2 = ((c[:, 1] - a[:, 1]) * (x - c[:, 0]) + (a[:, 0] - c[:, 0]) * (y - c[:, 1])) / den
            l3 = 1 - l1 - l2
            m = (l1 >= 0) & (l2 >= 0) & (l3 >= 0)
            if m.any():
                z = l1[m] * T[m, 0, 2] + l2[m] * T[m, 1, 2] + l3[m] * T[m, 2, 2]
                out[j, i] = z.max() if side > 0 else z.min()
    return out


def plate_parts(prims):
    """The two plates and their holders, as (program, triangles, normals, colours, uvs, group) tuples, the rear plate
    lamps' halo spots, plus where they went.
    Each lies on a plane z = a + b*y fitted to the surface under it (the rear bumper leans back), moved out until no
    part of the surface pokes through, plus the gap."""
    out, where, lamps = [], [], []
    for side, cy, gap in PLATES:
        xs = np.linspace(-HOLDER_W / 2, HOLDER_W / 2, 9); ys = np.linspace(cy - HOLDER_H / 2, cy + HOLDER_H / 2, 6)
        Z = surface_z(prims, side, xs, ys)
        rows = np.nanmax(Z, 1) if side > 0 else np.nanmin(Z, 1)
        b, a = np.polyfit(ys, rows, 1)
        lift = np.nanmax((Z - (a + b * ys)[:, None]) * side)
        a += side * (max(lift, 0) + gap)
        n = np.array([0.0, -b, 1.0]) * side; n /= np.linalg.norm(n)
        right = np.array([side, 0.0, 0.0])   # the viewer's right: +X (the car's left) seen from the front, -X from behind
        for prog, w, h, back in ((BODY, HOLDER_W, HOLDER_H, 0.003), (PLATE, PLATE_W, PLATE_H, 0.0)):
            corners = []
            for u, v in ((0, 0), (1, 0), (1, 1), (0, 1)):   # u right, v down
                x = (u - 0.5) * w * side; y = cy + (0.5 - v) * h
                corners.append((np.array([x, y, a + b * y - side * back]), (u, v)))
            P = np.array([[corners[0][0], corners[2][0], corners[1][0]], [corners[0][0], corners[3][0], corners[2][0]]]) if side > 0 else \
                np.array([[corners[0][0], corners[1][0], corners[2][0]], [corners[0][0], corners[2][0], corners[3][0]]])
            UV = np.array([[corners[0][1], corners[2][1], corners[1][1]], [corners[0][1], corners[3][1], corners[2][1]]]) if side > 0 else \
                np.array([[corners[0][1], corners[1][1], corners[2][1]], [corners[0][1], corners[2][1], corners[3][1]]])
            N = np.tile(n, (2, 3, 1))
            col = np.tile(HOLDER_RGBA if prog == BODY else (1, 1, 1, 0.5), (2, 3, 1))
            out.append((prog, P, N, col, UV if prog == PLATE else np.zeros((2, 3, 2)), POSITION if prog == PLATE and side < 0 else NONE))
        if side < 0:
            top = cy + PLATE_H / 2 + PLATE_LAMP_UP
            for lx in (-PLATE_LAMP_X, PLATE_LAMP_X):
                lamps.append((POSITION, np.array([lx, top, a + b * top - 0.01]), np.array([0.0, -0.6, -0.8]), 0.025, -1))
        where.append({'side': 'front' if side > 0 else 'rear', 'centre': [0.0, cy, round(float(a + b * cy), 4)],
                      'lean_dz_dy': round(float(b), 3), 'right': right.tolist()})
    return out, lamps, where


def corner_of(c):
    """FL/FR/RL/RR from a point: +X is the car's left, +Z the front."""
    return (FL if c[0] > 0 else FR) if c[2] > 0 else (RL if c[0] > 0 else RR)


def lamp_groups(p):
    """Lamp group per triangle of a 'Light' primitive."""
    I = p['I']; c = p['P'][I].mean(1)
    e = sample(p['emissive'], p['UV'][I].mean(1))[:, :3] if p['emissive'] is not None else np.zeros((len(I), 3))
    r, g, b = e.T; lit = e.max(1) > 0.25
    red = lit & (r > 0.5) & (g < 0.35 * r)
    amber = lit & (r > 0.5) & (g >= 0.35 * r) & (b < 0.6 * g)
    white = lit & ~red & ~amber
    out = np.full(len(I), NONE)
    turn = np.where(c[:, 0] > 0, TURN_L, TURN_R)
    front = c[:, 2] > 0
    if 'Spoiler' in p['node']:
        out[red | white | amber] = STOP
        return out
    out[amber] = turn[amber]
    out[front & white] = HEAD
    out[front & red] = POSITION        # the orange-red glow around the projectors
    out[~front & red] = TAIL
    out[~front & white] = REVERSE
    return out


def weld(P, I, tol=1e-5):
    q = np.round(P / tol).astype(np.int64)
    _, first, inv = np.unique(q, axis=0, return_index=True, return_inverse=True)
    return P[first], inv.reshape(-1)[I], first


def smooth_normals(P, I, crease_deg=50.0):
    """Corner normals: average the faces around a vertex whose normal is within the crease angle."""
    fn = np.cross(P[I[:, 1]] - P[I[:, 0]], P[I[:, 2]] - P[I[:, 0]])
    area = np.linalg.norm(fn, axis=1, keepdims=True); fu = fn / (area + 1e-20)
    nf = len(I); corner_v = I.reshape(-1); corner_f = np.repeat(np.arange(nf), 3)
    order = np.argsort(corner_v, kind='stable'); sv = corner_v[order]; sf = corner_f[order]
    starts = np.r_[0, np.flatnonzero(np.diff(sv)) + 1, len(sv)]
    out = np.zeros((nf * 3, 3)); cos = np.cos(np.radians(crease_deg))
    for a, b in zip(starts[:-1], starts[1:]):
        fs = sf[a:b]; u = fu[fs]; w = fn[fs]
        if b - a == 1:
            out[order[a]] = u[0]; continue
        sim = (u @ u.T) > cos
        acc = sim.astype(np.float64) @ w
        acc /= np.linalg.norm(acc, axis=1, keepdims=True) + 1e-20
        out[order[a:b]] = acc
    return out.reshape(nf, 3, 3)


def decimate(P, I, keep):
    if keep >= 0.999 or len(I) < 200:
        return P, I
    import fast_simplification
    Pw, Iw, _ = weld(P, I)
    Pd, Id = fast_simplification.simplify(Pw.astype(np.float32), Iw.astype(np.int32), target_reduction=1 - keep, agg=6)
    return Pd.astype(np.float64), Id.astype(np.int64)


class Part:
    def __init__(self, program, motion, corner):
        self.program, self.motion, self.corner = program, motion, corner
        self.pos, self.nrm, self.group, self.col, self.uv = [], [], [], [], []

    def add(self, P, N, group, col, uv):   # triangle soups: (n,3,3) (n,3,3) (n,) (n,3,4) (n,3,2)
        self.pos.append(P); self.nrm.append(N); self.group.append(np.repeat(group[:, None], 3, 1)); self.col.append(col); self.uv.append(uv)

    def soup(self):
        return (np.concatenate(self.pos).reshape(-1, 3), np.concatenate(self.nrm).reshape(-1, 3),
                np.concatenate(self.group).reshape(-1), np.concatenate(self.col).reshape(-1, 4), np.concatenate(self.uv).reshape(-1, 2))


def build(prims):
    from scipy.spatial import cKDTree
    allP = np.vstack([p['P'] for p in prims if p['mat'] not in DROP_MATERIALS])
    lo, hi = allP.min(0), allP.max(0)
    offset = np.array([-(lo[0] + hi[0]) / 2, -lo[1], -(lo[2] + hi[2]) / 2])
    for p in prims:
        p['P'] = p['P'] + offset
    pivots = hinge_pivots(prims)
    # Wheel centres from the tyres.
    centres = {}
    for k in range(4):
        pts = np.vstack([p['P'] for p in prims if p['mat'] == 'tire' and corner_of(p['P'].mean(0)) == k])
        centres[k] = (pts.min(0) + pts.max(0)) / 2
    radius = float(np.mean([np.vstack([p['P'] for p in prims if p['mat'] == 'tire' and corner_of(p['P'].mean(0)) == k])[:, 1].max() / 2 for k in range(4)]))
    parts = {}

    def part(program, motion=FIXED, corner=0):
        return parts.setdefault((program, motion, corner), Part(program, motion, corner))

    stats = {}
    spots = {}
    for p in prims:
        if p['mat'] in DROP_MATERIALS or any(d in p['node'] for d in DROP_NODES):
            continue
        mat = p['mat']; I = p['I']; P = p['P']
        # Per-vertex base colour (texture sample x factor), sampled on the full mesh before decimation.
        col = np.tile(p['factor'], (len(P), 1))
        if p['base'] is not None:
            col = col * sample(p['base'], p['UV'])
        if mat in COLOR:
            col[:, :3] = COLOR[mat]
        if p['blend']:
            alpha = np.full(len(P), OPACITY.get(mat, 0.5))
        else:
            alpha = np.full(len(P), GLOSS.get(mat, 0.15))
        col[:, 3] = alpha
        node = p['node']
        hinge = hinge_of(node)
        is_wheel = mat in ('tire', 'RimHub') or 'Disc_Scale' in node or 'SK_Rim' in node
        is_calliper = 'Ani_Wheel_Caliper' in node
        if mat == 'Light':
            groups = lamp_groups(p)
            collect_spots(p, groups, spots, hinge)
            prog = part(LAMP, HINGE, hinge) if hinge is not None else part(LAMP)
            prog.add(P[I], p['N'][I], groups, col[I], p['UV'][I])
            stats[mat] = stats.get(mat, 0) + len(I)
            continue
        if p['blend'] and mat in ('Glass_light', 'Windowd'):
            target = part(GLASS, HINGE, hinge) if hinge is not None else part(GLASS)
        elif is_wheel or is_calliper:
            target = part(BODY, CALLIPER if is_calliper else WHEEL, corner_of(P[I].mean(1).mean(0)))
        elif hinge is not None:
            target = part(BODY, HINGE, hinge)
        else:
            target = part(BODY)
        keep = KEEP.get(mat, 0.5)
        if hinge == ENGINE:
            keep = min(keep, ENGINE_KEEP)
        elif mat in UNDER_BONNET:
            keep = min(keep, BONNET_UNDER_KEEP)
        if keep >= 0.999:   # untouched: the original (smooth) normals
            Pd, Id, Nd = P, I, p['N'][I]
        else:
            Pd, Id = decimate(P, I, keep)
            Nd = smooth_normals(Pd, Id)
        # Carry colours over from the nearest original vertex.
        _, near = cKDTree(P).query(Pd)
        cd = col[near]
        group = np.full(len(Id), PAINT if mat == 'CarPaint' else NONE)
        target.add(Pd[Id], Nd, group, cd[Id], np.zeros((len(Id), 3, 2)))
        stats[mat] = stats.get(mat, 0) + len(Id)
    plates, plate_lamps, plate_where = plate_parts(prims)
    for prog, P, N, col, UV, group in plates:
        part(prog).add(P, N, np.full(len(P), group), col, UV)
    stats['plates'] = plate_where
    return parts, centres, pivots, radius, offset, stats, finish_spots(spots) + plate_lamps


def collect_spots(p, groups, spots, hinge=None):
    """Accumulate the brightly glowing area of each lamp group per side (area-weighted)."""
    I = p['I']; tri = p['P'][I]
    e = sample(p['emissive'], p['UV'][I].mean(1))[:, :3].max(1)
    area = np.linalg.norm(np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]), axis=1) / 2
    c = tri.mean(1); n = p['N'][I].mean(1)
    zone = np.where(c[:, 2] > 1.0, 1, np.where(c[:, 2] < -1.0, -1, 0))   # front lamp, mirror, rear lamp
    for g in set(groups.tolist()) - {NONE}:
        for side, z in [(x, y) for x in (1, -1) for y in (1, 0, -1)]:
            m = (groups == g) & (e > 0.4) & (zone == z)
            if g == STOP:
                if side < 0:
                    continue
            else:
                m &= np.sign(c[:, 0]) == side
            if not m.any():
                continue
            s = spots.setdefault((g, side, z, -1 if hinge is None else hinge), [np.zeros(3), np.zeros(3), 0.0, []])
            w = area[m]
            s[0] += (c[m] * w[:, None]).sum(0); s[1] += (n[m] * w[:, None]).sum(0); s[2] += w.sum(); s[3].append(c[m])


def finish_spots(spots):
    out = []
    for (g, side, z, hinge), (pc, pn, w, pts) in sorted(spots.items()):
        if w <= 0:
            continue
        centre = pc / w; nrm = pn / (np.linalg.norm(pn) + 1e-12)
        pts = np.vstack(pts); size = float(np.percentile(np.linalg.norm(pts - centre, axis=1), 80))
        out.append((g, centre, nrm, max(size, 0.03), hinge))
    return out


def pack(parts, centres, pivots, spots, out):
    blobs = []
    order = sorted(parts.values(), key=lambda q: (q.program, q.motion, q.corner))
    count = 0; total_tris = 0
    for q in order:
        P, N, G, C, UV = q.soup()
        # Index the soup: identical corners share a vertex.
        key = np.c_[np.round(P * 1e5), np.round(N * 32767), G, np.round(C * 255), np.round(UV * 65535)].astype(np.int64)
        _, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
        inv = inv.reshape(-1); tris = inv.reshape(-1, 3)
        pivot = pivots[q.corner] if q.motion == HINGE else centres[q.corner] if q.motion != FIXED else np.zeros(3)
        # Split into chunks that fit 16-bit indices.
        start = 0
        while start < len(tris):
            used = {}; end = start; idx = []
            while end < len(tris):
                t = tris[end]; new = sum(1 for v in t if v not in used)
                if len(used) + new > 65535:
                    break
                for v in t:
                    if v not in used:
                        used[v] = len(used)
                    idx.append(used[v])
                end += 1
            verts = np.array(sorted(used, key=used.get))
            src = first[verts]
            vp = (P[src] - pivot).astype('<f4')
            vn = np.clip(np.round(N[src] * 32767), -32767, 32767).astype('<i2')
            vg = G[src].astype('<u2')
            vc = np.clip(np.round(C[src] * 255), 0, 255).astype('u1')
            vu = np.clip(np.round(UV[src] * 65535), 0, 65535).astype('<u2')
            rec = np.zeros(len(verts), dtype=[('p', '<f4', 3), ('n', '<i2', 3), ('g', '<u2'), ('c', 'u1', 4), ('u', '<u2', 2)])
            rec['p'] = vp; rec['n'] = vn; rec['g'] = vg; rec['c'] = vc; rec['u'] = vu
            ib = np.array(idx, dtype='<u2').tobytes()
            ib += b'\0' * ((-len(ib)) % 4)
            blobs.append(struct.pack('<BBBB3fII', q.program, q.motion, q.corner, 0, *pivot.astype(float), len(verts), len(idx)) + rec.tobytes() + ib)
            count += 1; total_tris += len(idx) // 3
            start = end
    trailer = struct.pack('<I', len(spots)) + b''.join(
        struct.pack('<BBBB7f', g, hinge + 1, 0, 0, *c.astype(float), *n.astype(float), size) for g, c, n, size, hinge in spots)
    data = b'VN3D' + struct.pack('<II', VERSION, count) + b''.join(blobs) + trailer
    out.write_bytes(data)
    return count, total_tris, len(data)


def write_lamp_textures(prims, out):
    for p in prims:
        if p['mat'] == 'Light':
            Image.fromarray((p['base'][:, :, :3] * 255).astype(np.uint8)).save(out / 'lamp_base.png', optimize=True)
            Image.fromarray((p['emissive'][:, :, :3] * 255).astype(np.uint8)).save(out / 'lamp_glow.png', optimize=True)
            return


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('model', type=Path, help='folder with scene.gltf, scene.bin and textures/')
    ap.add_argument('--out', type=Path, default=Path(__file__).resolve().parents[1] / 'apps/dashboard/assets/car3d')
    args = ap.parse_args()
    prims = load(args.model)
    parts, centres, pivots, radius, offset, stats, spots = build(prims)
    args.out.mkdir(parents=True, exist_ok=True)
    count, tris, size = pack(parts, centres, pivots, spots, args.out / 'veloster_n.bin')
    write_lamp_textures(prims, args.out)
    print(json.dumps({'chunks': count, 'triangles': tris, 'bytes': size, 'tyre_radius_m': round(radius, 4),
                      'wheel_centres_m': {k: [round(float(x), 4) for x in v] for k, v in centres.items()},
                      'hinge_pivots_m': {k: [round(float(x), 4) for x in v] for k, v in pivots.items()},
                      'lamp_spots': [[g, [round(float(x), 3) for x in c], round(sz, 3), h] for g, c, n, sz, h in spots],
                      'triangles_by_material': stats}, indent=1))


if __name__ == '__main__':
    main()
