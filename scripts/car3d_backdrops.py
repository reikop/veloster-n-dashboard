#!/usr/bin/env python3
"""Paint the 내 차 screen's four 360-degree backdrops (MIT, procedural - no photos or third-party art).

    python3 scripts/car3d_backdrops.py [--out apps/dashboard/assets/car3d] [--preview DIR]

Each backdrop is a cylindrical panorama, 2048 x 512: x = azimuth (u 0..1 = atan2(dir.x, dir.z) / 2pi + 0.5, the same
mapping as CarRenderer's sky and reflection shaders), y = elevation from +64 deg (top row) to -16 deg (bottom row).
The car faces +Z (u = 0.5). The app draws it behind the car and the paint / floor reflect it, so the bright parts
(softboxes, sun, city lights) are what shows up in the reflections. Needs numpy and Pillow (development only).
"""
import argparse
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

W, H = 2048, 512
E_TOP, E_BOTTOM = 64.0, -16.0

u = (np.arange(W) + 0.5) / W
v = (np.arange(H) + 0.5) / H
U, V = np.meshgrid(u, v)
AZ = (U - 0.5) * 360.0                      # azimuth in degrees, 0 = straight ahead of the car (+Z)
EL = E_TOP - V * (E_TOP - E_BOTTOM)          # elevation in degrees


def az_diff(a, b):
    """Signed smallest angle a-b in degrees."""
    return (a - b + 180.0) % 360.0 - 180.0


def px_x(az):
    return (az / 360.0 + 0.5) * W


def px_y(el):
    return (E_TOP - el) / (E_TOP - E_BOTTOM) * H


def periodic_noise(rng, cells_x, cells_y, shape=(H, W)):
    """Smooth value noise that wraps around horizontally."""
    g = rng.random((cells_y + 2, cells_x))
    x = U * cells_x
    y = V * cells_y
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int)
    fx = x - x0; fy = y - y0
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
    x1 = (x0 + 1) % cells_x; x0 = x0 % cells_x
    y1 = np.minimum(y0 + 1, cells_y + 1)
    a = g[y0, x0] * (1 - fx) + g[y0, x1] * fx
    b = g[y1, x0] * (1 - fx) + g[y1, x1] * fx
    return a * (1 - fy) + b * fy


def fbm(rng, cx, cy, octaves=5, gain=0.5):
    out = np.zeros((H, W)); amp = 1.0; total = 0.0
    for o in range(octaves):
        out += periodic_noise(rng, cx * 2 ** o, cy * 2 ** o) * amp
        total += amp; amp *= gain
    return out / total


def ridge_line(rng, cells, octaves=5):
    """1-D periodic fbm over azimuth (0..1)."""
    out = np.zeros(W); amp = 1.0; total = 0.0
    for o in range(octaves):
        n = cells * 2 ** o
        g = rng.random(n)
        x = u * n; x0 = np.floor(x).astype(int); f = x - x0; f = f * f * (3 - 2 * f)
        out += (g[x0 % n] * (1 - f) + g[(x0 + 1) % n] * f) * amp
        total += amp; amp *= 0.5
    return out / total


def mix(a, b, t):
    t = np.asarray(t)[..., None] if np.ndim(t) == 2 else t
    return np.asarray(a) * (1 - t) + np.asarray(b) * t


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def gradient(stops):
    """Vertical gradient from (elevation, rgb) stops."""
    img = np.zeros((H, W, 3))
    es = [s[0] for s in stops]
    for c in range(3):
        col = np.interp(EL[:, 0], es[::-1], [s[1][c] for s in stops][::-1])
        img[:, :, c] = col[:, None]
    return img


def glow(img, az, el, color, radius_deg, power=1.0):
    d = np.hypot(az_diff(AZ, az) * math.cos(math.radians(el)), EL - el)
    img += np.asarray(color) * (np.exp(-(d / radius_deg) ** 2) * power)[..., None]


def blur(img, r):
    pil = Image.fromarray(np.clip(img * 255, 0, 255).astype(np.uint8))
    # wrap horizontally so the seam stays invisible
    pad = int(r * 3) + 2
    wide = Image.new('RGB', (W + 2 * pad, H))
    wide.paste(pil.crop((W - pad, 0, W, H)), (0, 0)); wide.paste(pil, (pad, 0)); wide.paste(pil.crop((0, 0, pad, H)), (W + pad, 0))
    wide = wide.filter(ImageFilter.GaussianBlur(r))
    return np.asarray(wide.crop((pad, 0, pad + W, H))).astype(np.float64) / 255


def rect_mask(az0, az1, el0, el1, soft_px=2.0):
    """Soft rectangle in azimuth/elevation (azimuths may wrap)."""
    c = (az0 + az1) / 2; half = abs(az_diff(az1, az0)) / 2
    dx = (np.abs(az_diff(AZ, c)) - half) / 360.0 * W
    dy = (np.maximum(EL - el1, el0 - EL)) / (E_TOP - E_BOTTOM) * H
    return smooth(soft_px, -soft_px, np.maximum(dx, dy))


# ---- scenes ----------------------------------------------------------------------------------------------------------
def studio(rng):
    img = gradient([(64, (0.012, 0.013, 0.017)), (30, (0.03, 0.032, 0.04)), (8, (0.075, 0.078, 0.09)),
                    (0, (0.10, 0.104, 0.118)), (-16, (0.07, 0.072, 0.082))])
    # cyclorama: the curved wall meets the floor in a soft bright band; light washes on the wall behind the stands
    img += np.asarray([0.05, 0.052, 0.06]) * np.exp(-((EL - 1.5) / 4.0) ** 2)[..., None]
    for az, w in ((-145, 38), (-20, 30), (95, 34)):
        img += np.asarray([0.13, 0.135, 0.15]) * (np.exp(-(az_diff(AZ, az) / w) ** 2) * np.exp(-((EL - 8) / 14) ** 2))[..., None]
    lights = np.zeros_like(img)
    dark = np.zeros((H, W))
    # overhead softboxes: the long stripe highlights on the paint
    for az in (-150, -30, 90):
        lights += rect_mask(az - 22, az + 22, 42, 50, 3)[..., None] * np.asarray([1.0, 1.0, 1.0])
    # stands around the car: square softboxes, tall strip boxes and round octaboxes, each on a pole with tripod legs
    stands = [(-165, 'box', 7, 6, 20), (-125, 'strip', 2.2, 3, 27), (-62, 'octa', 6, 14, 0), (8, 'box', 6, 5, 18),
              (62, 'strip', 2.2, 3, 26), (128, 'octa', 5.5, 13, 0), (172, 'strip', 2.0, 4, 24)]
    for az, kind, half, e0, e1 in stands:
        if kind == 'octa':
            d = np.hypot(az_diff(AZ, az), EL - e0)
            panel = smooth(half, half - 0.6, d)
            core = panel * (0.75 + 0.25 * np.exp(-(d / half) ** 2))
            lights += core[..., None] * np.asarray([1.0, 0.98, 0.95])
            dark += smooth(half + 0.9, half + 0.3, d) * (1 - panel)          # the black rim of the octabox
            bottom = e0 - half
        else:
            panel = rect_mask(az - half, az + half, e0, e1, 1.5)
            lights += panel[..., None] * np.asarray([0.95, 0.97, 1.0]) * (0.85 if kind == 'box' else 1.0)
            dark += np.clip(rect_mask(az - half - 0.5, az + half + 0.5, e0 - 0.5, e1 + 0.5, 1) - panel, 0, 1)
            bottom = e0
        dark += rect_mask(az - 0.25, az + 0.25, -4.5, bottom, 0.6)               # pole
        for leg in (-1, 1):                                                       # tripod legs
            t = np.clip((EL + 4.5) / 3.0, 0, 1)
            dark += smooth(0.5, 0.1, np.abs(az_diff(AZ, az + leg * (1 - t) * 2.2))) * ((EL > -4.5) & (EL < -1.5))
    img *= (1 - 0.85 * np.clip(dark, 0, 1))[..., None]
    img = img + blur(lights, 5) * 0.55 + lights * 0.8
    img += blur(lights, 26) * 0.3                      # haze around the lights
    img *= (0.93 + 0.07 * fbm(rng, 6, 2, 3))[..., None]
    return img


def sunset(rng):
    sun_az, sun_el = -150.0, 3.0   # behind the car in the default view
    img = gradient([(64, (0.05, 0.045, 0.16)), (40, (0.14, 0.09, 0.30)), (22, (0.42, 0.20, 0.42)), (10, (0.85, 0.38, 0.35)),
                    (3, (1.0, 0.58, 0.30)), (0, (1.0, 0.70, 0.40)), (-16, (0.25, 0.12, 0.14))])
    near_sun = np.exp(-(np.abs(az_diff(AZ, sun_az)) / 70.0) ** 2)
    img += np.asarray([0.35, 0.18, 0.02]) * (near_sun * smooth(30, 0, EL))[..., None]
    img *= (1 - 0.35 * (1 - near_sun) * smooth(25, 0, EL))[..., None] * 0 + 1
    # far side of the sky a little cooler
    img = mix(img, img * np.asarray([0.75, 0.75, 1.0]), (1 - near_sun) * 0.6)
    # stratified clouds: stretched fbm, lit hot near the sun and on their undersides
    n = fbm(rng, 8, 14, 6)
    band = smooth(4, 9, EL) * smooth(42, 18, EL)
    cover = smooth(0.52, 0.72, n) * band
    lit = np.clip(0.25 + 0.9 * near_sun + 0.3 * smooth(25, 5, EL), 0, 1.3)
    cloud_dark = np.asarray([0.20, 0.10, 0.22]); cloud_lit = np.asarray([1.0, 0.55, 0.32])
    cloud = mix(cloud_dark, cloud_lit, np.clip(lit * (0.4 + 0.8 * (1 - n)), 0, 1))
    img = mix(img, cloud, cover * 0.9)
    # sun disc and glow
    glow(img, sun_az, sun_el, (1.0, 0.75, 0.40), 14, 0.55)
    glow(img, sun_az, sun_el, (1.0, 0.92, 0.70), 3.5, 1.2)
    d = np.hypot(az_diff(AZ, sun_az), EL - sun_el)
    img = mix(img, np.asarray([1.0, 0.97, 0.88]), smooth(1.9, 1.5, d))
    # mountain ranges: far hazy, mid, near dark
    for cells, base, amp, col, haze in ((5, 0.5, 5.5, (0.62, 0.32, 0.40), 0.55), (7, -0.2, 4.0, (0.30, 0.14, 0.24), 0.3),
                                        (11, -0.8, 2.6, (0.07, 0.04, 0.07), 0.0)):
        ridge = base + amp * ridge_line(rng, cells) ** 1.4
        m = smooth(0.15, -0.15, EL - ridge[None, :])
        shade = np.asarray(col) * (1 + 0.15 * (fbm(rng, 30, 8, 3) - 0.5))[..., None]
        shade = mix(shade, np.asarray([1.0, 0.6, 0.4]), near_sun * haze * 0.5)
        img = mix(img, shade, m)
    return img


def garage(rng):
    concrete = 0.34 + 0.09 * fbm(rng, 40, 10, 5)
    img = np.repeat(concrete[..., None], 3, axis=2) * np.asarray([0.98, 0.98, 1.0])
    ceiling = smooth(25, 28, EL)
    img = mix(img, img * 0.30, ceiling)
    dado = smooth(3.4, 3.0, EL)
    img = mix(img, np.asarray([0.10, 0.105, 0.115]) * (0.9 + 0.2 * fbm(rng, 60, 6, 3))[..., None], dado)
    img = mix(img, np.asarray([0.70, 0.06, 0.08]), smooth(0.2, -0.2, np.abs(EL - 3.9) - 0.35))
    seam = np.abs(((AZ + 360) % 12.0) - 6.0) > 5.9
    img[seam & (EL < 25) & (EL > 3.4)] *= 0.78
    lights = np.zeros_like(img)
    # high windows with cool daylight
    for az in np.arange(-180, 180, 24) + 6:
        m = rect_mask(az - 6, az + 6, 17, 22.5, 1)
        img = mix(img, np.asarray([0.55, 0.68, 0.85]) * (0.8 + 0.2 * smooth(17, 22.5, EL))[..., None], m)
        for k in (-2, 2):   # mullions
            img = mix(img, np.asarray([0.12, 0.12, 0.13]), rect_mask(az + k * 1.5 - 0.15, az + k * 1.5 + 0.15, 17, 22.5, 0.5))
        lights += rect_mask(az - 6, az + 6, 17, 22.5, 1)[..., None] * np.asarray([0.25, 0.32, 0.45])
    # pillars with hazard bands
    for az in range(-180, 180, 60):
        m = rect_mask(az - 2.2, az + 2.2, -16, 25, 1.2)
        img = mix(img, np.asarray([0.30, 0.30, 0.32]) * (0.8 + 0.3 * smooth(az + 2.2, az - 2.2, AZ))[..., None], m)
        stripes = ((AZ - az + EL * 1.2) % 2.0) < 1.0
        haz = np.where(stripes[..., None], np.asarray([0.85, 0.65, 0.05]), np.asarray([0.05, 0.05, 0.05]))
        img = mix(img, haz, m * smooth(2.5, 2.1, EL) * smooth(-2.5, -2.0, EL))
    # red tool cabinets with drawers and chrome handles
    for az0, az1 in ((-170, -146), (-98, -80), (14, 34), (96, 120)):
        m = rect_mask(az0, az1, -3.5, 5.5, 0.8)
        drawers = ((EL + 3.5) % 1.8) < 0.12
        body = np.where(drawers[..., None], np.asarray([0.25, 0.03, 0.04]), np.asarray([0.72, 0.07, 0.08]))
        body = body * (0.85 + 0.25 * smooth(-3.5, 5.5, EL))[..., None]
        img = mix(img, body, m)
        handles = m * (np.abs(((EL + 3.5) % 1.8) - 0.9) < 0.12) * (np.abs(az_diff(AZ, (az0 + az1) / 2)) < (az1 - az0) * 0.25)
        img = mix(img, np.asarray([0.85, 0.86, 0.88]), handles)
        img = mix(img, np.asarray([0.55, 0.56, 0.58]), rect_mask(az0, az1, 5.5, 6.1, 0.5))   # steel worktop
        # pegboard with tools above
        pb = rect_mask(az0 + 1, az1 - 1, 7.5, 14.5, 0.8)
        dots = (((AZ * 2) % 1.0) < 0.25) & (((EL * 2) % 1.0) < 0.25)
        img = mix(img, np.where(dots[..., None], np.asarray([0.12, 0.09, 0.06]), np.asarray([0.42, 0.33, 0.22])), pb)
        for k in range(int((az1 - az0) / 3)):
            ta = az0 + 2.5 + k * 3; tl = 2.5 + 2.5 * rng.random()
            img = mix(img, np.asarray([0.15, 0.15, 0.17]) if k % 2 else np.asarray([0.7, 0.7, 0.72]),
                      rect_mask(ta - 0.35, ta + 0.35, 13.5 - tl, 13.5, 0.4) * pb)
    # tyre stacks
    for az in (-125, -45, 62, 150):
        for j in range(3):
            for i in range(4):
                c_az = az + (j - 1) * 4.2; c_el = -3.2 + i * 1.7
                d = np.hypot(az_diff(AZ, c_az) / 2.0, (EL - c_el) / 0.8)
                tyre = smooth(1.0, 0.92, d)
                img = mix(img, np.asarray([0.04, 0.04, 0.045]) * (0.8 + 0.6 * smooth(c_el - 0.8, c_el + 0.8, EL))[..., None], tyre)
    # lit sign: a blue N panel on the wall
    sign = rect_mask(56, 74, 13, 21, 1.5) * 0
    n_az, n_el = 168, 10
    sign = rect_mask(n_az - 8, n_az + 8, n_el - 4, n_el + 4, 1.2)
    img = mix(img, np.asarray([0.02, 0.04, 0.10]), sign)
    n_letter = (rect_mask(n_az - 5, n_az - 3.4, n_el - 2.8, n_el + 2.8, 0.8) + rect_mask(n_az + 3.4, n_az + 5, n_el - 2.8, n_el + 2.8, 0.8)
                + smooth(0.9, 0.2, np.abs((AZ - (n_az - 3.4)) / 6.8 * 5.6 - (n_el + 2.8 - EL)) * 1.3) * rect_mask(n_az - 3.4, n_az + 3.4, n_el - 2.8, n_el + 2.8, 0.8))
    lights += np.clip(n_letter, 0, 1)[..., None] * np.asarray([0.25, 0.65, 1.0]) * 1.4
    # fluorescent tubes on the ceiling
    for el, length, step in ((30, 9, 24), (37, 6.5, 24), (46, 4.5, 24), (57, 3.0, 24)):
        for az in np.arange(-180, 180, step) + (step / 2 if el in (37, 57) else 0):
            lights += rect_mask(az - length / 2, az + length / 2, el - 0.45, el + 0.45, 1)[..., None] * np.asarray([1.0, 1.0, 0.95])
    img = img * (0.72 + 0.45 * smooth(-16, 20, EL))[..., None]
    img = img + blur(lights, 9) * 0.6 + blur(lights, 2) * 0.8
    return np.clip(img, 0, 1.5)


def night_road(rng):
    img = gradient([(64, (0.004, 0.006, 0.02)), (30, (0.01, 0.015, 0.045)), (10, (0.04, 0.045, 0.10)),
                    (2, (0.16, 0.11, 0.16)), (0, (0.20, 0.13, 0.15)), (-16, (0.02, 0.02, 0.03))])
    # stars
    stars = np.zeros((H, W))
    n = 900
    xs = rng.integers(0, W, n); ys = rng.integers(0, int(px_y(14)), n); b = rng.random(n) ** 3
    stars[ys, xs] = b
    img += blur(np.repeat(stars[..., None], 3, 2), 0.6) * 3.0 * np.asarray([0.9, 0.95, 1.0])
    # moon
    glow(img, 60, 34, (0.25, 0.28, 0.35), 9, 0.5)
    d = np.hypot(az_diff(AZ, 60) * math.cos(math.radians(34)), EL - 34)
    img = mix(img, np.asarray([0.95, 0.95, 0.88]), smooth(2.3, 1.9, d))
    # skyline: far hazy layer and a near layer with windows
    lights = np.zeros_like(img)
    for layer, (count, hmin, hmax, col, wins) in enumerate(((140, 1.0, 6.0, (0.10, 0.08, 0.14), 0.25), (90, 2.0, 13.0, (0.025, 0.025, 0.04), 1.0))):
        heights = np.zeros(W); tint = np.ones(W)
        x = 0
        while x < W:
            w = int(rng.integers(10, 46 if layer else 30))
            hgt = hmin + (hmax - hmin) * rng.random() ** 1.8
            # taller towards the city centre (behind the car on the left)
            hgt *= 0.55 + 0.9 * math.exp(-((az_diff((x / W - 0.5) * 360, -120)) / 60) ** 2)
            heights[x:x + w] = hgt; tint[x:x + w] = 0.7 + 0.7 * rng.random()
            x += w + int(rng.integers(0, 4))
        m = EL < heights[None, :]
        img[m] = (np.asarray(col)[None, None, :] * tint[None, :, None] * np.ones((H, 1, 1)))[m]
        # windows on a grid
        gx = (np.arange(W) % 3) < 2
        gy = (np.arange(H) % 3) < 1 + (layer == 1)
        on = (rng.random((H // 3 + 1, W // 3 + 1)) < 0.3 * wins)
        on = np.repeat(np.repeat(on, 3, 0), 3, 1)[:H, :W]
        warm = rng.random((H // 3 + 1, W // 3 + 1)) < 0.7
        warm = np.repeat(np.repeat(warm, 3, 0), 3, 1)[:H, :W]
        win = m & on & gx[None, :] & gy[:, None] & (EL > 0.3)
        wc = np.where(warm[..., None], np.asarray([1.0, 0.75, 0.40]), np.asarray([0.6, 0.8, 1.0]))
        lights += win[..., None] * wc * (0.5 if layer == 0 else 0.9)
        # red aviation lights on the tallest
        if layer:
            for xx in range(0, W, 7):
                if heights[xx] > 9:
                    lights[int(px_y(heights[xx] + 0.4)), xx] = (1.0, 0.1, 0.05)
    # neon signs on a few buildings
    for az, el, col in ((-138, 5.5, (1.0, 0.2, 0.6)), (-96, 4.0, (0.2, 0.9, 1.0)), (40, 3.2, (1.0, 0.5, 0.1)), (150, 4.5, (0.4, 1.0, 0.4))):
        frame = rect_mask(az - 2.2, az + 2.2, el - 0.6, el + 0.6, 0.5) - rect_mask(az - 1.9, az + 1.9, el - 0.35, el + 0.35, 0.5)
        lights += np.clip(frame, 0, 1)[..., None] * np.asarray(col) * 1.2
        lights += rect_mask(az - 1.5, az + 1.5, el - 0.12, el + 0.12, 0.4)[..., None] * np.asarray(col)
    # city glow above the skyline
    img += np.asarray([0.30, 0.16, 0.10]) * (smooth(10, 0, EL) * np.exp(-((az_diff(AZ, -120)) / 80) ** 2))[..., None] * 0.6
    # street lamps along the road (runs along the car's axis: azimuth 0 and 180), converging on the vanishing points
    for vp in (0.0, 180.0):
        for k in range(4, 40):   # nearer lamps would float high in the sky of a cylinder
            dist = 6.0 * k
            for side in (-1, 1):
                az = vp + side * math.degrees(math.atan2(7.5, dist))
                el = math.degrees(math.atan2(7.0 - 1.2, dist))
                r = max(0.25, 6.0 / dist)
                d = np.hypot(az_diff(AZ, az), EL - el)
                lights += np.asarray([1.0, 0.62, 0.25]) * np.exp(-(d / r) ** 2)[..., None] * 1.2
    img = img + lights + blur(lights, 5) * 1.2 + blur(lights, 22) * 0.6
    return np.clip(img, 0, 1.5)


SCENES = [("bg_studio", studio, 11), ("bg_sunset", sunset, 23), ("bg_garage", garage, 37), ("bg_night", night_road, 51)]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--out', type=Path, default=Path(__file__).resolve().parents[1] / 'apps/dashboard/assets/car3d')
    ap.add_argument('--only', help='one scene name, e.g. bg_sunset')
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for name, fn, seed in SCENES:
        if args.only and args.only != name:
            continue
        img = fn(np.random.default_rng(seed))
        img = np.clip(img, 0, 1) ** (1 / 1.05)
        Image.fromarray((img * 255).astype(np.uint8)).save(args.out / (name + '.jpg'), quality=88, optimize=True)
        print(name, (args.out / (name + '.jpg')).stat().st_size)


if __name__ == '__main__':
    main()
