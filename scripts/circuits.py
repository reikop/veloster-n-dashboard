#!/usr/bin/env python3
"""Generate shared/src/com/veloster/vehicle/CircuitData.java: Korean circuits for the Circuit screen's map and laps.

Track lines come from OpenStreetMap (© OpenStreetMap contributors, ODbL 1.0; the generated file is under the ODbL).
Fetch the ways below with an Overpass query and pass the JSON:

    [out:json];way(id:<all ids in CIRCUITS>);out body geom;

    python scripts/circuits.py circuits.json

The start / finish line is, in this order: a point read off the circuit's official map, snapped onto the track (Inje:
the 2025 SuperRace round 6 circuit map, control line just after T19; Taebaek: a circuit map with the line about mid
straight between T10 and T1; AMG: a circuit map, the line 185 m into the T17-T1 straight); OSM's "Finish Line" node (KIC); or an estimate,
halfway (by distance) along the longest straight within 40 m of the pit lane, the main straight beside the pits, or
the middle of the longest straight where no pit lane is mapped. Lap times do not depend on where the line sits.

The driving direction is the order of the points when every track way is oneway=yes in OSM, or when a circuit map
confirms it (DIRECTION_CHECKED); otherwise unknown, and the app takes it from the first crossing.
"""
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIRECTION_CHECKED = {"kic"}   # no oneway tag, but the circuit map has the cars leave the line towards T1 as the way runs
TARGET = ROOT / "shared/src/com/veloster/vehicle/CircuitData.java"
# id, Korean name, English name, track ways in driving order (joined end to end), pit lane ways, finish: an OSM node or
# (lat, lon) read off the official circuit map
CIRCUITS = [
    ("inje", "인제 스피디움", "Inje Speedium", [651693293], [677355827], (38.000451, 128.291362)),   # 168 m up the 646 m main straight
    ("kic", "코리아 인터내셔널 서킷", "Korea International Circuit", [708763326], [234767156, 1291666918], 2429025515),
    ("kic_permanent", "KIC 상설 서킷", "KIC Permanent Circuit", [234764328], [234768719], None),
    ("taebaek", "태백 스피드웨이", "Taebaek Speedway", [240211101], [826496123], (37.082725, 129.017869)),   # circuit map: 348 m into the 651 m straight from T10
    ("amg", "AMG 스피드웨이", "AMG Speedway", [240199084, 671420801, 671420803, 671420804], [450059011], (37.296167, 127.206624)),   # circuit map: 185 m into the 446 m T17-T1 straight
    ("pocheon", "포천 레이스웨이", "Pocheon Raceway", [1559045138], [], None),   # OSM has no pit lane here
]


def metres(lat0, lon0):
    kx = 111320 * math.cos(math.radians(lat0))
    return lambda lat, lon: ((lon - lon0) * kx, (lat - lat0) * 110540)


def seg_dist(p, a, b):
    ax, ay = b[0] - a[0], b[1] - a[1]
    t = 0 if ax == ay == 0 else max(0, min(1, ((p[0] - a[0]) * ax + (p[1] - a[1]) * ay) / (ax * ax + ay * ay)))
    return math.hypot(p[0] - a[0] - t * ax, p[1] - a[1] - t * ay)


def polyline(points):
    out, plat, plon = [], 0, 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for d in (ilat - plat, ilon - plon):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = ilat, ilon
    return "".join(out)


def main():
    elements = json.load(open(sys.argv[1], encoding="utf-8"))["elements"]
    ways = {e["id"]: e for e in elements if e["type"] == "way"}
    rows = []
    for cid, ko, en, track, pits, finish in CIRCUITS:
        pts, nodes = [], []
        for wid in track:
            g, n = ways[wid]["geometry"], ways[wid]["nodes"]
            g, n = [(p["lat"], p["lon"]) for p in g], list(n)
            if nodes and n[0] != nodes[-1]:
                raise SystemExit(f"{cid}: way {wid} does not continue the track")
            pts += g[1:] if pts else g
            nodes += n[1:] if nodes else n
        if nodes[0] == nodes[-1]:
            pts, nodes = pts[:-1], nodes[:-1]   # closed: drop the repeated point
        else:   # open (KIC): drop an end that runs on past the start
            m0 = metres(*pts[0])
            while math.hypot(*m0(*pts[-1])) < 10:
                pts, nodes = pts[:-1], nodes[:-1]
        m = metres(*pts[0])
        xy = [m(*p) for p in pts]
        if isinstance(finish, tuple):
            q, n = m(*finish), len(xy)
            k = min(range(n), key=lambda i: seg_dist(q, xy[i], xy[(i + 1) % n]))
            a, b = xy[k], xy[(k + 1) % n]
            ax, ay = b[0] - a[0], b[1] - a[1]
            f = max(0, min(1, ((q[0] - a[0]) * ax + (q[1] - a[1]) * ay) / (ax * ax + ay * ay)))
            pa, pb = pts[k], pts[(k + 1) % n]
            pts.insert(k + 1, (pa[0] + f * (pb[0] - pa[0]), pa[1] + f * (pb[1] - pa[1])))   # the line, snapped onto the track
            xy.insert(k + 1, m(*pts[k + 1]))
            sf, src = k + 1, "map"
        elif finish in nodes:
            sf, src = nodes.index(finish), "osm"
        else:
            src = "est"
            n = len(xy)
            hd = [math.atan2(xy[(i + 1) % n][1] - xy[i][1], xy[(i + 1) % n][0] - xy[i][0]) for i in range(n)]
            straight = lambda i: abs((hd[i % n] - hd[(i - 1) % n] + math.pi) % (2 * math.pi) - math.pi) < math.radians(8)
            if pits:   # straight and beside the pit lane (a pit lane may run on round a corner)
                pit = [m(p["lat"], p["lon"]) for w in pits for p in ways[w]["geometry"]]
                keep = [min(seg_dist(q, a, b) for a, b in zip(pit, pit[1:])) < 40 for q in xy]
                ok = lambda i: keep[i % n] and keep[(i + 1) % n] and straight(i)
            else:
                ok = straight
            seg = lambda i: math.dist(xy[i % n], xy[(i + 1) % n])
            best, i = (0, 0), 0   # (length, first segment)
            while i < n:
                j, total = i, 0
                while ok(j) and j - i < n:
                    total += seg(j)
                    j += 1
                best = max(best, (total, i))
                i = max(j, i + 1)
            half, k = best[0] / 2, best[1]
            while half > seg(k):
                half -= seg(k)
                k += 1
            k %= n
            f = half / seg(k) if seg(k) else 0
            a, b = pts[k], pts[(k + 1) % n]
            pts.insert(k + 1, (a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])))   # the line, between two track points
            xy.insert(k + 1, m(*pts[k + 1]))
            sf = k + 1
        pts, xy = pts[sf:] + pts[:sf], xy[sf:] + xy[:sf]   # the line at the first point
        length = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(xy, xy[1:] + xy[:1]))
        known = cid in DIRECTION_CHECKED or all(ways[w].get("tags", {}).get("oneway") == "yes" for w in track)
        rows.append((cid, ko, en, round(length), polyline(pts), src, "1" if known else "0"))
        print(f"{cid}: {len(pts)} points, {length:.0f} m, line at point {sf} ({src}), direction {'known' if known else 'unknown'}")
    body = ",\n".join(f'        {{"{c}","{k}","{e}","{l}","' + p.replace("\\", "\\\\").replace('"', '\\"') + f'","{r}","{dk}"}}' for c, k, e, l, p, r, dk in rows)
    TARGET.write_text(f'''// Generated by scripts/circuits.py from {Path(sys.argv[1]).name}. Do not edit.
// Track data © OpenStreetMap contributors, available under the Open Database License (ODbL) 1.0:
// https://www.openstreetmap.org/copyright. This file (not the rest of the repository) is under the ODbL.
package com.veloster.vehicle;

/** Circuits: id, Korean name, English name, track length (m), the track as a Google polyline starting at the start /
 *  finish line (in one driving direction or the other: CircuitLap learns which at the first crossing), and where the
 *  line came from: "map" the official circuit map, "osm" OSM's finish node, "est" estimated beside the pits; and "1"
 *  when the points run in the driving direction (OSM oneway or a circuit map), "0" when that is not known. */
final class CircuitData {{
    private CircuitData(){{}}
    static final String[][] TRACKS={{
{body}
    }};
}}
''', encoding="utf-8", newline="\n")
    print(f"{len(rows)} circuits -> {TARGET.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
