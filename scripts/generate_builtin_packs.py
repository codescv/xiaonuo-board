#!/usr/bin/env python3
# /// script
# requires-python = ">=3.9"
# dependencies = ["pillow", "numpy", "scipy"]
# ///
"""Built-in coloring page generator for 小诺画板 (XiaoNuo Board).

Generates 66 coloring pages (line art + pixel-aligned colored reference) plus
``manifest.json`` into ``app/src/main/assets/builtin_packs/``.

Usage:
    uv run scripts/generate_builtin_packs.py --mode offline
    uv run scripts/generate_builtin_packs.py --mode gemini   # needs GEMINI_API_KEY
    uv run scripts/generate_builtin_packs.py --only elsa_01,alphabet --preview /tmp/prev

Offline art is ORIGINAL procedural artwork (themed scenes, no copyrighted
characters).  Each scene is painted as filled shapes into an integer region-id
buffer (painter's order, 2x supersampled).  Line art = pixels near an id
boundary (+ decorative ink strokes); the colored reference = per-id flat fill
colours with the very same black lines on top, so the two images are pixel
aligned by construction.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage as ndi

W, H = 1200, 1600
REPO = Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "app" / "src" / "main" / "assets" / "builtin_packs"
DEFAULT_FONT = "/usr/share/fonts/truetype/quicksand/Quicksand-Bold.ttf"
FONT_PATH = DEFAULT_FONT

CATEGORIES = [
    {"id": "elsa", "title": "艾莎"},
    {"id": "bluey", "title": "布鲁伊"},
    {"id": "peppa", "title": "小猪佩奇"},
    {"id": "anpanman", "title": "面包超人"},
    {"id": "alphabet", "title": "字母"},
]
REGION_RANGE = {"easy": (10, 18), "standard": (20, 60)}
TINY_PX = 400
MAX_TINY = 3
UNIFORM_MIN = 0.97

# ---------------------------------------------------------------------------
# Kaleido-friendly palette (core 16 colours + a few light tints)
# ---------------------------------------------------------------------------
RED = "#E61919"
ORANGE = "#FF6F00"
YELLOW = "#FFD600"
GREEN = "#38B000"
FOREST = "#007F5F"
SKY = "#00B4D8"
ICE = "#0077B6"
BLUE = "#2400FF"
PURPLE = "#9D4EDD"
ROSE = "#FF006E"
PINK = "#FF70A6"
PEACH = "#FFB58A"
BROWN = "#A45A2A"
GRAY = "#8D99AE"
WHITE = "#FFFFFF"
BLACK = "#000000"
# light tints
LSKY = "#BDEBFA"
LICE = "#DCEFFF"
LYELLOW = "#FFF1A1"
LGREEN = "#B6F09C"
LPINK = "#FFC9DE"
LPURPLE = "#E3CCFF"
SAND = "#F7D99B"
TAN = "#E0A96D"
LORANGE = "#FFC285"
NAVY = "#1B3A8C"
LGRAY = "#D5DBE5"
CORE = [RED, ORANGE, YELLOW, GREEN, FOREST, SKY, ICE, BLUE, PURPLE, ROSE,
        PINK, PEACH, BROWN, GRAY]


def hex2rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


# ---------------------------------------------------------------------------
# Geometry helpers (all in logical 1200x1600 coordinates)
# ---------------------------------------------------------------------------
def catmull(pts, closed=True, n=14):
    P = [tuple(p) for p in pts]
    m = len(P)
    out = []
    rng = range(m) if closed else range(m - 1)
    for i in rng:
        p0 = P[(i - 1) % m] if closed else P[max(i - 1, 0)]
        p1 = P[i]
        p2 = P[(i + 1) % m]
        p3 = P[(i + 2) % m] if closed else P[min(i + 2, m - 1)]
        for k in range(n):
            t = k / n
            t2, t3 = t * t, t * t * t
            xy = []
            for a in range(2):
                xy.append(0.5 * ((2 * p1[a]) + (-p0[a] + p2[a]) * t
                                 + (2 * p0[a] - 5 * p1[a] + 4 * p2[a] - p3[a]) * t2
                                 + (-p0[a] + 3 * p1[a] - 3 * p2[a] + p3[a]) * t3))
            out.append(tuple(xy))
    if not closed:
        out.append(P[-1])
    return out


def ellipse_pts(cx, cy, rx, ry, rot=0.0, a0=0.0, a1=360.0, n=None):
    if n is None:
        n = max(24, int(abs(a1 - a0) / 3))
    r = math.radians(rot)
    cr, sr = math.cos(r), math.sin(r)
    out = []
    for k in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * k / n)
        x, y = rx * math.cos(a), ry * math.sin(a)
        out.append((cx + x * cr - y * sr, cy + x * sr + y * cr))
    return out


def rotate(pts, ang, cx=0.0, cy=0.0):
    r = math.radians(ang)
    c, s = math.cos(r), math.sin(r)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in pts]


def tf(pts, x=0.0, y=0.0, s=1.0, rot=0.0, sx=None, sy=None):
    """Scale (around origin), rotate, then translate a point list."""
    sx = s if sx is None else sx
    sy = s if sy is None else sy
    pts = [(px * sx, py * sy) for px, py in pts]
    if rot:
        pts = rotate(pts, rot)
    return [(px + x, py + y) for px, py in pts]


def star_pts(cx, cy, ro, ri, n=5, rot=-90.0):
    out = []
    for k in range(2 * n):
        a = math.radians(rot + 180.0 * k / n)
        r = ro if k % 2 == 0 else ri
        out.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return out


def heart_pts(cx, cy, s):
    out = []
    for k in range(72):
        t = 2 * math.pi * k / 72
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        out.append((cx + x * s / 16, cy + y * s / 16))
    return out


def rrect_pts(x0, y0, x1, y1, r, n=8):
    r = min(r, (x1 - x0) / 2, (y1 - y0) / 2)
    out = []
    for (cx, cy, a0) in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for k in range(n + 1):
            a = math.radians(a0 + 90 * k / n)
            out.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return out


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


# ---------------------------------------------------------------------------
# Page: region-id painter
# ---------------------------------------------------------------------------
class Page:
    def __init__(self, lw=8.0, ss=2, bg=WHITE):
        self.ss = ss
        self.lw = lw
        self.colors = [bg]
        self.idimg = Image.new("I", (W * ss, H * ss), 0)
        self.d = ImageDraw.Draw(self.idimg)
        self.ink = Image.new("L", (W * ss, H * ss), 0)
        self.di = ImageDraw.Draw(self.ink)

    # -- id management ----------------------------------------------------
    def bg(self, color):
        self.colors[0] = color
        return 0

    def new(self, color):
        self.colors.append(color)
        return len(self.colors) - 1

    def _id(self, c):
        if isinstance(c, (int, np.integer)):
            return int(c)
        return self.new(c)

    def _P(self, pts):
        s = self.ss
        return [(x * s, y * s) for x, y in pts]

    # -- filled shapes (each returns its region id) -------------------------
    def _ids(self):
        return np.array(self.idimg, dtype=np.int32)

    def _set_ids(self, arr):
        self.idimg = Image.fromarray(arr.astype(np.int32))
        self.d = ImageDraw.Draw(self.idimg)

    def _clipmask(self, clip, draw):
        tmp = Image.new("L", self.idimg.size, 0)
        draw(ImageDraw.Draw(tmp))
        arr = self._ids()
        return arr, (np.array(tmp) > 0) & np.isin(arr, np.atleast_1d(clip))

    def poly(self, pts, c, clip=None):
        i = self._id(c)
        P = self._P(pts)
        if clip is None:
            self.d.polygon(P, fill=i)
        else:
            arr, m = self._clipmask(clip, lambda d: d.polygon(P, fill=255))
            arr[m] = i
            self._set_ids(arr)
        return i

    def smooth(self, pts, c, n=14, clip=None):
        return self.poly(catmull(pts, True, n), c, clip)

    def ellipse(self, cx, cy, rx, ry, c, rot=0.0, clip=None):
        if rot or clip is not None:
            return self.poly(ellipse_pts(cx, cy, rx, ry, rot, n=180), c, clip)
        i = self._id(c)
        s = self.ss
        self.d.ellipse([(cx - rx) * s, (cy - ry) * s, (cx + rx) * s, (cy + ry) * s], fill=i)
        return i

    def circle(self, cx, cy, r, c, clip=None):
        return self.ellipse(cx, cy, r, r, c, clip=clip)

    def rect(self, x0, y0, x1, y1, c, r=0, clip=None):
        if clip is not None:
            pts = rrect_pts(x0, y0, x1, y1, r) if r > 0 else [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
            return self.poly(pts, c, clip)
        i = self._id(c)
        s = self.ss
        if r > 0:
            self.d.rounded_rectangle([x0 * s, y0 * s, x1 * s, y1 * s], radius=r * s, fill=i)
        else:
            self.d.rectangle([x0 * s, y0 * s, x1 * s, y1 * s], fill=i)
        return i

    def star(self, cx, cy, ro, c, ri=None, n=5, rot=-90.0, rounded=False):
        ri = ro * 0.45 if ri is None else ri
        pts = star_pts(cx, cy, ro, ri, n, rot)
        if rounded:
            return self.smooth(pts, c, 6)
        return self.poly(pts, c)

    def heart(self, cx, cy, s, c):
        return self.poly(heart_pts(cx, cy, s), c)

    def pie(self, cx, cy, rx, ry, a0, a1, c, rot=0.0, clip=None):
        return self.poly([(cx, cy)] + ellipse_pts(cx, cy, rx, ry, rot, a0, a1), c, clip)

    def chord(self, cx, cy, rx, ry, a0, a1, c, rot=0.0, clip=None):
        return self.poly(ellipse_pts(cx, cy, rx, ry, rot, a0, a1), c, clip)

    def band(self, top_pts, bottom_y, c):
        """Filled area from a (left->right) top curve down to bottom_y."""
        pts = catmull(top_pts, False, 14)
        pts = pts + [(pts[-1][0], bottom_y), (pts[0][0], bottom_y)]
        return self.poly(pts, c)

    def mask(self, m, c):
        """m: bool array at supersampled resolution."""
        i = self._id(c)
        arr = self._ids()
        arr[m] = i
        self._set_ids(arr)
        return i

    # -- ink (black decorative strokes, do not create ids) -------------------
    def _ink(self, draw, clip=None):
        if clip is None:
            draw(self.di)
            return
        tmp = Image.new("L", self.ink.size, 0)
        draw(ImageDraw.Draw(tmp))
        m = (np.array(tmp) > 0) & np.isin(self._ids(), np.atleast_1d(clip))
        ink = np.array(self.ink)
        ink[m] = 255
        self.ink = Image.fromarray(ink)
        self.di = ImageDraw.Draw(self.ink)

    def line(self, pts, w=None, cap=True, clip=None):
        w = (self.lw * 0.85 if w is None else w) * self.ss
        P = self._P(pts)

        def draw(d):
            d.line(P, fill=255, width=max(1, int(round(w))), joint="curve")
            if cap:
                r = w / 2
                for (x, y) in (P[0], P[-1]):
                    d.ellipse([x - r, y - r, x + r, y + r], fill=255)
        self._ink(draw, clip)

    def curve(self, pts, w=None, clip=None):
        self.line(catmull(pts, False, 14), w, clip=clip)

    def arc(self, cx, cy, rx, ry, a0, a1, w=None, rot=0.0, clip=None):
        self.line(ellipse_pts(cx, cy, rx, ry, rot, a0, a1), w, clip=clip)

    def dot(self, cx, cy, r):
        s = self.ss
        self.di.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=255)

    def inkpoly(self, pts, smooth=False, clip=None):
        if smooth:
            pts = catmull(pts, True, 10)
        P = self._P(pts)
        self._ink(lambda d: d.polygon(P, fill=255), clip)

    def inkellipse(self, cx, cy, rx, ry, rot=0.0):
        self.inkpoly(ellipse_pts(cx, cy, rx, ry, rot))

    def eye(self, cx, cy, r, shine=True):
        """Cute solid eye with white highlight (highlight is erased ink)."""
        self.dot(cx, cy, r)
        if shine:
            s = self.ss
            hr = r * 0.36
            hx, hy = cx - r * 0.3, cy - r * 0.32
            self.di.ellipse([(hx - hr) * s, (hy - hr) * s, (hx + hr) * s, (hy + hr) * s], fill=0)

    def smile(self, cx, cy, w, h=None, lw=None):
        h = w * 0.55 if h is None else h
        self.arc(cx, cy - h * 0.2, w / 2, h, 20, 160, lw)

    # -- render ------------------------------------------------------------
    def render(self):
        ss = self.ss
        ids = np.array(self.idimg, dtype=np.int32)
        e = np.zeros(ids.shape, dtype=bool)
        dh = ids[:, 1:] != ids[:, :-1]
        e[:, 1:] |= dh
        e[:, :-1] |= dh
        dv = ids[1:, :] != ids[:-1, :]
        e[1:, :] |= dv
        e[:-1, :] |= dv
        t = max(0.5, (self.lw * ss - 2) / 2.0)
        dist = ndi.distance_transform_edt(~e)
        line_ss = dist <= t
        line_ss |= np.array(self.ink) > 127
        line = line_ss.reshape(H, ss, W, ss).mean(axis=(1, 3)) >= 0.5
        small_ids = ids[ss // 2::ss, ss // 2::ss]
        # remove uncolourable specks (fill them black)
        lab, n = ndi.label(~line, structure=_CROSS)
        if n:
            sizes = np.bincount(lab.ravel())
            speck = sizes < 160
            speck[0] = False
            line |= speck[lab]
        lut = np.array([hex2rgb(c) for c in self.colors], dtype=np.uint8)
        ref = lut[np.clip(small_ids, 0, len(self.colors) - 1)]
        ref[line] = 0
        return line, ref


_CROSS = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=bool)


# ---------------------------------------------------------------------------
# Analysis / QC helpers
# ---------------------------------------------------------------------------
def label_regions(line: np.ndarray):
    """White 4-connected components after a 1px closing of the black lines."""
    pad = np.pad(line, 2, mode="edge")
    closed = ndi.binary_closing(pad, structure=np.ones((3, 3), bool))[2:-2, 2:-2] | line
    lab, n = ndi.label(~closed, structure=_CROSS)
    return lab, n


def region_stats(line, ref):
    lab, n = label_regions(line)
    sizes = np.bincount(lab.ravel(), minlength=n + 1)[1:]
    tiny = int((sizes < TINY_PX).sum())
    # uniformity: dominant colour share inside every region
    rgb = ref.astype(np.int64)
    key_c = (rgb[..., 0] << 16) | (rgb[..., 1] << 8) | rgb[..., 2]
    m = lab > 0
    keys = lab[m].astype(np.int64) * (1 << 24) + key_c[m]
    uk, cnt = np.unique(keys, return_counts=True)
    labs = uk >> 24
    best = np.zeros(n + 1, dtype=np.int64)
    np.maximum.at(best, labs, cnt)
    tot = np.bincount(lab[m], minlength=n + 1)
    share = best[1:] / np.maximum(tot[1:], 1)
    worst = float(share.min()) if n else 1.0
    return n, tiny, worst, sizes


def dominant_palette(ref, k=6):
    rgb = ref.reshape(-1, 3).astype(np.int64)
    key = (rgb[:, 0] << 16) | (rgb[:, 1] << 8) | rgb[:, 2]
    uk, cnt = np.unique(key, return_counts=True)
    order = np.argsort(-cnt)
    out = []
    for i in order:
        v = int(uk[i])
        r, g, b = v >> 16, (v >> 8) & 255, v & 255
        if max(r, g, b) < 40:          # black lines
            continue
        if min(r, g, b) > 245:          # white / paper
            continue
        if cnt[i] < 50:
            continue
        out.append("#%02X%02X%02X" % (r, g, b))
        if len(out) == k:
            break
    for c in CORE:                      # pad (rare)
        if len(out) >= k:
            break
        if c not in out:
            out.append(c)
    return out


# ---------------------------------------------------------------------------
# Scene registry
# ---------------------------------------------------------------------------
@dataclass
class SceneDef:
    category: str
    index: int
    title: str
    desc: str
    fn: Callable
    difficulty: str = "easy"

    @property
    def id(self):
        return f"{self.category}_{self.index:02d}"


SCENES: dict[str, SceneDef] = {}


def scene(category, index, title, desc):
    def deco(fn):
        diff = "easy" if (category == "alphabet" or index <= 5) else "standard"
        sd = SceneDef(category, index, title, desc, fn, diff)
        SCENES[sd.id] = sd
        return fn
    return deco


def new_page(diff):
    return Page(lw=10.0 if diff == "easy" else 8.0)



# ===========================================================================
# Shared object library (original, generic drawings)
# ===========================================================================
def cloud(p, x, y, s=1.0, c=WHITE):
    i = p.new(c) if not isinstance(c, int) else c
    p.ellipse(x, y + 14 * s, 112 * s, 44 * s, i)
    p.circle(x - 56 * s, y + 4 * s, 42 * s, i)
    p.circle(x + 4 * s, y - 22 * s, 58 * s, i)
    p.circle(x + 62 * s, y + 6 * s, 40 * s, i)
    return i


def sun(p, x, y, r, c=YELLOW, rays=8, ray_c=ORANGE, ray_len=None, ink_rays=False, face=False):
    ray_len = r * 0.55 if ray_len is None else ray_len
    for k in range(rays):
        a = 2 * math.pi * k / rays - math.pi / 2
        if ink_rays:
            p.line([(x + (r + 18) * math.cos(a), y + (r + 18) * math.sin(a)),
                    (x + (r + 18 + ray_len) * math.cos(a), y + (r + 18 + ray_len) * math.sin(a))])
        else:
            hw = math.pi / rays * 0.55
            p.poly([(x + (r + 14) * math.cos(a - hw), y + (r + 14) * math.sin(a - hw)),
                    (x + (r + 14 + ray_len) * math.cos(a), y + (r + 14 + ray_len) * math.sin(a)),
                    (x + (r + 14) * math.cos(a + hw), y + (r + 14) * math.sin(a + hw))], ray_c)
    i = p.circle(x, y, r, c)
    if face:
        p.eye(x - r * 0.33, y - r * 0.12, r * 0.11)
        p.eye(x + r * 0.33, y - r * 0.12, r * 0.11)
        p.arc(x, y + r * 0.05, r * 0.38, r * 0.33, 25, 155)
    return i


def pine(p, x, yb, s=1.0, c=FOREST, trunk=BROWN, tiers=3, snow=None):
    p.rect(x - 27 * s, yb - 80 * s, x + 27 * s, yb, trunk)
    h = 300 * s
    for k in range(tiers):
        t = k / tiers
        w = (150 - 40 * k * 3 / tiers) * s
        yb_t = yb - 64 * s - t * h * 0.62
        top = yb_t - h * 0.5
        pts = [(x, top), (x + w, yb_t), (x + w * 0.5, yb_t + 14 * s), (x, yb_t + 4 * s),
               (x - w * 0.5, yb_t + 14 * s), (x - w, yb_t)]
        tid = p.poly(catmull(pts, True, 5), c)
        if snow and k == tiers - 1:
            p.poly([(x, top - 20 * s), (x + w * 0.6, top + h * 0.2), (x + w * 0.15, top + h * 0.17),
                    (x, top + h * 0.23), (x - w * 0.18, top + h * 0.17), (x - w * 0.6, top + h * 0.2)], snow, clip=tid)
    return


def round_tree(p, x, yb, s=1.0, leaf=GREEN, trunk=BROWN, fruit=None, nfruit=0):
    p.poly([(x - 30 * s, yb), (x - 22 * s, yb - 170 * s), (x + 22 * s, yb - 170 * s), (x + 30 * s, yb)], trunk)
    cy = yb - 260 * s
    i = p.new(leaf)
    p.circle(x, cy - 40 * s, 110 * s, i)
    p.circle(x - 95 * s, cy + 20 * s, 85 * s, i)
    p.circle(x + 95 * s, cy + 20 * s, 85 * s, i)
    p.circle(x, cy + 50 * s, 90 * s, i)
    if fruit:
        spots = [(-70, -30), (60, -60), (20, 50), (-100, 50), (100, 40), (-20, -100)]
        for dx, dy in spots[:nfruit]:
            p.circle(x + dx * s, cy + dy * s, 22 * s, fruit)
    return i


def flower(p, x, y, s=1.0, petal=PINK, center=YELLOW, stem=True, leaf=GREEN, stem_len=110):
    if stem:
        p.line([(x, y), (x, y + stem_len * s)], 9 * s if s < 1 else None)
        if leaf:
            p.ellipse(x + 26 * s, y + stem_len * 0.65 * s, 26 * s, 12 * s, leaf, rot=-30)
    i = p.new(petal)
    for k in range(5):
        a = 2 * math.pi * k / 5 - math.pi / 2
        p.circle(x + 30 * s * math.cos(a), y + 30 * s * math.sin(a), 24 * s, i)
    p.circle(x, y, 19 * s, center)
    return i


def tulip(p, x, y, s=1.0, c=RED, stem=GREEN):
    p.line([(x, y), (x, y + 120 * s)])
    p.ellipse(x - 26 * s, y + 80 * s, 28 * s, 11 * s, stem, rot=35)
    p.poly([(x - 36 * s, y - 40 * s), (x - 18 * s, y - 18 * s), (x, y - 44 * s), (x + 18 * s, y - 18 * s),
            (x + 36 * s, y - 40 * s), (x + 34 * s, y + 4 * s), (x, y + 28 * s), (x - 34 * s, y + 4 * s)], c)


def grass(p, x, y, s=1.0):
    p.line([(x - 22 * s, y - 26 * s), (x - 10 * s, y)], 6)
    p.line([(x, y - 36 * s), (x, y)], 6)
    p.line([(x + 22 * s, y - 26 * s), (x + 10 * s, y)], 6)


def bird(p, x, y, s=1.0):
    p.arc(x - 22 * s, y, 22 * s, 16 * s, 200, 340, 6)
    p.arc(x + 22 * s, y, 22 * s, 16 * s, 200, 340, 6)


def snowflake_ink(p, x, y, r, w=6):
    for k in range(6):
        a = math.pi / 3 * k + math.pi / 2
        ex, ey = x + r * math.cos(a), y + r * math.sin(a)
        p.line([(x, y), (ex, ey)], w)
        mx, my = x + r * 0.6 * math.cos(a), y + r * 0.6 * math.sin(a)
        for d in (-0.6, 0.6):
            p.line([(mx, my), (mx + r * 0.32 * math.cos(a + d), my + r * 0.32 * math.sin(a + d))], w)


def drop(p, x, y, s, c):
    pts = [(x, y - 40 * s)] + ellipse_pts(x, y + 8 * s, 26 * s, 26 * s, 0, -30, 210)
    return p.poly(catmull(pts, True, 6), c)


def sparkle(p, x, y, r, c):
    return p.poly(catmull(star_pts(x, y, r, r * 0.3, 4, -90), True, 3), c)


def house(p, x, yb, w, h, wall=PEACH, roof=RED, door=BROWN, win=LSKY, chimney=True):
    if chimney:
        p.rect(x + w * 0.18, yb - h - h * 0.55, x + w * 0.34, yb - h - h * 0.1, BROWN)
    p.rect(x - w / 2, yb - h, x + w / 2, yb, wall)
    p.poly([(x - w / 2 - 30, yb - h + 6), (x, yb - h - h * 0.62), (x + w / 2 + 30, yb - h + 6)], roof)
    p.rect(x - w * 0.12, yb - h * 0.55, x + w * 0.12, yb, door, r=12)
    p.rect(x - w * 0.42, yb - h * 0.75, x - w * 0.2, yb - h * 0.45, win, r=6)
    p.rect(x + w * 0.2, yb - h * 0.75, x + w * 0.42, yb - h * 0.45, win, r=6)
    p.dot(x + w * 0.07, yb - h * 0.27, 6)


def gift(p, x, y, w, h, box=RED, ribbon=YELLOW):
    p.rect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, box)
    p.rect(x - w * 0.09, y - h / 2, x + w * 0.09, y + h / 2, ribbon)
    p.ellipse(x - w * 0.22, y - h / 2 - 20, w * 0.2, 18, ribbon, rot=-20)
    p.ellipse(x + w * 0.22, y - h / 2 - 20, w * 0.2, 18, ribbon, rot=20)


def wave_band(p, y, amp, c, period=150, bottom=H, phase=0.0, x0=-20, x1=W + 20):
    pts = []
    x = x0
    while x <= x1:
        pts.append((x, y + amp * math.sin((x / period) * 2 * math.pi + phase)))
        x += 10
    return p.poly(pts + [(x1, bottom), (x0, bottom)], c)


def hill(p, pts, c, bottom=H + 20):
    return p.band(pts, bottom, c)


def quad_bezier(p0, p1, p2, n=24):
    out = []
    for k in range(n + 1):
        t = k / n
        out.append(((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
                    (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]))
    return out


def balloon(p, x, y, s, c, string_to=None):
    p.poly([(x - 14 * s, y + 98 * s), (x, y + 84 * s), (x + 14 * s, y + 98 * s)], c)
    i = p.smooth([(x, y - 90 * s), (x + 62 * s, y - 52 * s), (x + 66 * s, y + 10 * s), (x + 30 * s, y + 70 * s),
                  (x, y + 88 * s), (x - 30 * s, y + 70 * s), (x - 66 * s, y + 10 * s), (x - 62 * s, y - 52 * s)], c)
    p.arc(x - 30 * s, y - 30 * s, 20 * s, 34 * s, 190, 250, 7)
    end = string_to or (x + 10 * s, y + 260 * s)
    p.curve([(x, y + 100 * s), (x + 18 * s, y + 150 * s), (x - 12 * s, y + 200 * s), end], 6)
    return i


def umbrella_canopy(p, cx, tip_y, base_y, half_w, colors, scallop=34):
    """Classic umbrella: len(colors) curved panels with scalloped hem."""
    n = len(colors)
    xs = [cx - half_w + 2 * half_w * k / n for k in range(n + 1)]
    tip = (cx, tip_y)

    def seam(xk):
        ctrl = (xk + (xk - cx) * 0.32, tip_y + (base_y - tip_y) * 0.1)
        return quad_bezier(tip, ctrl, (xk, base_y))
    ids = []
    for k in range(n):
        a = seam(xs[k])
        b = seam(xs[k + 1])
        mid = (xs[k] + xs[k + 1]) / 2
        hem = quad_bezier((xs[k], base_y), (mid, base_y - 2 * scallop), (xs[k + 1], base_y), 12)
        ids.append(p.poly(a + hem + b[::-1], colors[k]))
    return ids


def bunting(p, ctrl, cols, half=50, drop_len=105, w=7):
    """Rope (ink) along a smooth curve with triangular flags hanging from it."""
    curve = catmull(ctrl, False, 30)
    p.line(curve, w)
    xs = np.array([c[0] for c in curve])
    ys = np.array([c[1] for c in curve])
    x0, x1 = ctrl[0][0], ctrl[-1][0]
    n = len(cols)
    for k in range(n):
        cx = x0 + (x1 - x0) * (k + 0.5) / n
        top = [(x, float(np.interp(x, xs, ys))) for x in np.linspace(cx - half, cx + half, 9)]
        cy = float(np.interp(cx, xs, ys))
        p.poly(top + [(cx, cy + drop_len)], cols[k])


# ---------------------------------------------------------------------------
# Bubble letters
# ---------------------------------------------------------------------------
_FONTS: dict = {}


def _font(size):
    path = os.environ.get("XN_FONT", FONT_PATH)
    k = (path, size)
    if k not in _FONTS:
        _FONTS[k] = ImageFont.truetype(path, size)
    return _FONTS[k]


def bubble_pair(p, up, lo, c_up, c_lo, top=90, bottom=800, cx=(320, 880), maxw=470, dil=13):
    ss = p.ss
    base = 700
    f = _font(base * ss)
    bbs = [f.getbbox(ch, anchor="ls") for ch in (up, lo)]
    maxw_px = max(b[2] - b[0] for b in bbs)
    tall = max(-b[1] for b in bbs) + max(max(b[3] for b in bbs), 0)
    k = min(1.0, (maxw - 2 * dil) * ss / maxw_px, (bottom - top - 2 * dil) * ss / tall)
    size = int(base * ss * k)
    f = _font(size)
    bbs = [f.getbbox(ch, anchor="ls") for ch in (up, lo)]
    ytop = min(b[1] for b in bbs)
    ybot = max(b[3] for b in bbs)
    baseline = ((top + bottom) / 2) * ss - (ytop + ybot) / 2
    ids = []
    for ch, col, x, bb in zip((up, lo), (c_up, c_lo), cx, bbs):
        img = Image.new("L", p.idimg.size, 0)
        ImageDraw.Draw(img).text((x * ss - (bb[0] + bb[2]) / 2, baseline), ch, font=f, anchor="ls", fill=255)
        m = np.array(img) > 127
        m = ndi.distance_transform_edt(~m) <= dil * ss
        ids.append(p.mask(m, col))
    return ids


# ===========================================================================
# Alphabet (26 pages): hollow bubble letter pair + a cute object
# ===========================================================================
OX, OY = 600, 1200   # object centre


@scene("alphabet", 1, "Aa 苹果 Apple", "a big red apple with a leaf, on a plate")
def alpha_a(p):
    bubble_pair(p, "A", "a", RED, GREEN)
    p.ellipse(OX, 1455, 280, 55, LSKY)
    p.smooth([(OX, 1000), (OX + 90, 960), (OX + 210, 1010), (OX + 245, 1160), (OX + 190, 1340),
                     (OX + 90, 1420), (OX, 1395), (OX - 90, 1420), (OX - 190, 1340), (OX - 245, 1160),
                     (OX - 210, 1010), (OX - 90, 960)], RED)
    p.poly(rotate([(OX - 16, 1010), (OX - 12, 900), (OX + 14, 900), (OX + 16, 1010)], 12, OX, 1000), BROWN)
    p.ellipse(OX + 95, 930, 80, 36, GREEN, rot=-25)
    p.line(rotate([(OX + 30, 930), (OX + 160, 930)], -25, OX + 95, 930), 6)
    p.ellipse(OX - 130, 1110, 30, 62, WHITE, rot=20)
    sparkle(p, OX - 330, 1000, 55, YELLOW)
    sparkle(p, OX + 330, 1250, 50, YELLOW)


@scene("alphabet", 2, "Bb 小熊 Bear", "a cute teddy bear face with a bow tie")
def alpha_b(p):
    bubble_pair(p, "B", "b", BROWN, ORANGE)
    for sx in (-1, 1):
        p.circle(OX + sx * 175, 975, 78, BROWN)
        p.circle(OX + sx * 175, 975, 42, PEACH)
    p.ellipse(OX, 1170, 250, 225, BROWN)
    p.ellipse(OX, 1250, 115, 88, TAN)
    p.ellipse(OX, 1205, 34, 24, BLACK)
    p.line([(OX, 1225), (OX, 1262)], 7)
    p.arc(OX - 30, 1260, 30, 24, 10, 170, 7)
    p.arc(OX + 30, 1260, 30, 24, 10, 170, 7)
    p.eye(OX - 90, 1120, 20)
    p.eye(OX + 90, 1120, 20)
    p.poly([(OX - 150, 1380), (OX - 30, 1430), (OX - 150, 1490)], RED)
    p.poly([(OX + 150, 1380), (OX + 30, 1430), (OX + 150, 1490)], RED)
    p.circle(OX, 1432, 36, YELLOW)


@scene("alphabet", 3, "Cc 小猫 Cat", "a cute kitten face with a collar and bell")
def alpha_c(p):
    bubble_pair(p, "C", "c", ORANGE, PURPLE)
    for sx in (-1, 1):
        p.poly([(OX + sx * 70, 1000), (OX + sx * 215, 900), (OX + sx * 225, 1080)], GRAY)
        p.poly([(OX + sx * 110, 1010), (OX + sx * 195, 950), (OX + sx * 198, 1050)], PINK)
    p.rect(OX - 150, 1350, OX + 150, 1410, RED, r=26)
    p.ellipse(OX, 1180, 250, 200, GRAY)
    p.circle(OX, 1420, 44, YELLOW)
    p.line([(OX - 30, 1430), (OX + 30, 1430)], 6)
    p.dot(OX, 1445, 7)
    p.eye(OX - 95, 1150, 24)
    p.eye(OX + 95, 1150, 24)
    p.smooth([(OX - 40, 1205), (OX + 40, 1205), (OX, 1250)], PINK, 6)
    p.arc(OX - 24, 1255, 24, 20, 10, 170, 7)
    p.arc(OX + 24, 1255, 24, 20, 10, 170, 7)
    for sx in (-1, 1):
        for dy in (-12, 18):
            p.line([(OX + sx * 120, 1235 + dy * 0.3), (OX + sx * 290, 1215 + dy)], 6)


@scene("alphabet", 4, "Dd 小狗 Dog", "a friendly puppy face with floppy ears and a collar")
def alpha_d(p):
    bubble_pair(p, "D", "d", SKY, BROWN)
    p.rect(OX - 170, 1370, OX + 170, 1425, BLUE, r=26)
    p.circle(OX, 1450, 40, YELLOW)
    p.ellipse(OX, 1170, 230, 220, TAN)
    p.ellipse(OX + 85, 1118, 62, 56, BROWN)
    for sx in (-1, 1):
        p.smooth([(OX + sx * 170, 990), (OX + sx * 280, 1010), (OX + sx * 300, 1200), (OX + sx * 250, 1290),
                  (OX + sx * 200, 1180)], BROWN)
    p.ellipse(OX, 1265, 120, 85, WHITE)
    p.ellipse(OX, 1220, 40, 28, BLACK)
    p.line([(OX, 1240), (OX, 1275)], 7)
    p.arc(OX, 1255, 70, 40, 20, 160, 7)
    p.smooth([(OX - 30, 1290), (OX + 30, 1290), (OX + 26, 1335), (OX, 1350), (OX - 26, 1335)], PINK)
    p.eye(OX - 90, 1115, 22)
    p.eye(OX + 95, 1115, 22)


@scene("alphabet", 5, "Ee 彩蛋 Egg", "a decorated egg in an egg cup")
def alpha_e(p):
    bubble_pair(p, "E", "e", GREEN, PINK)
    egg = p.smooth([(OX, 900), (OX + 130, 960), (OX + 200, 1120), (OX + 190, 1280), (OX, 1350),
                    (OX - 190, 1280), (OX - 200, 1120), (OX - 130, 960)], LYELLOW)
    zig = [(OX - 240 + 40 * k, 1110 + (0 if k % 2 == 0 else -45)) for k in range(13)]
    p.poly(zig + [(OX + 260, 1180), (OX - 260, 1180)], SKY, clip=egg)
    for dx, dy in ((-80, 1000), (40, 970), (90, 1050)):
        p.circle(OX + dx, dy, 26, PINK, clip=egg)
    p.chord(OX, 1260, 170, 150, 0, 180, ORANGE)
    p.rect(OX - 45, 1395, OX + 45, 1440, ORANGE)
    p.ellipse(OX, 1460, 140, 34, RED)


@scene("alphabet", 6, "Ff 小鱼 Fish", "a striped fish with bubbles")
def alpha_f(p):
    bubble_pair(p, "F", "f", BLUE, ORANGE)
    p.poly([(OX + 150, 1200), (OX + 300, 1080), (OX + 280, 1200), (OX + 300, 1320)], YELLOW)
    p.poly([(OX - 60, 1070), (OX + 60, 990), (OX + 90, 1080)], ROSE)
    body = p.ellipse(OX - 20, 1200, 220, 145, ORANGE)
    p.poly([(OX - 40, 1000), (OX + 20, 1000), (OX - 10, 1400), (OX - 70, 1400)], WHITE, clip=body)
    p.poly([(OX + 70, 1000), (OX + 120, 1000), (OX + 100, 1400), (OX + 50, 1400)], WHITE, clip=body)
    p.eye(OX - 140, 1170, 22)
    p.arc(OX - 175, 1225, 28, 20, 0, 120, 7)
    for (bx, by, r) in ((OX - 300, 1050, 36), (OX - 340, 940, 26), (OX - 270, 880, 20)):
        p.circle(bx, by, r, LSKY)
    p.curve([(OX - 280, 1450), (OX - 120, 1420), (OX + 40, 1460), (OX + 200, 1420), (OX + 320, 1450)], 7)


@scene("alphabet", 7, "Gg 葡萄 Grapes", "a bunch of grapes with a leaf")
def alpha_g(p):
    bubble_pair(p, "G", "g", PURPLE, GREEN)
    p.rect(OX - 12, 900, OX + 12, 990, BROWN)
    p.ellipse(OX + 135, 950, 100, 45, GREEN, rot=-15)
    p.line(rotate([(OX + 50, 950), (OX + 220, 950)], -15, OX + 135, 950), 6)
    rows = [(-150, 1050, 3), (-100, 1150, 3), (-50, 1250, 2), (0, 1350, 1)]
    cols = [PURPLE, ROSE, PURPLE, ROSE]
    for ri, (dx0, y, n) in enumerate(rows):
        for k in range(n):
            p.circle(OX - (n - 1) * 54 + k * 108, y, 76, cols[(ri + k) % 4])


@scene("alphabet", 8, "Hh 房子 House", "a cozy little house with a chimney")
def alpha_h(p):
    bubble_pair(p, "H", "h", RED, BLUE)
    p.band([(-10, 1440), (300, 1420), (600, 1435), (900, 1420), (1210, 1440)], 1620, LGREEN)
    p.rect(OX + 90, 900, OX + 160, 1020, BROWN)
    p.rect(OX - 200, 1140, OX + 200, 1440, YELLOW)
    p.poly([(OX - 250, 1150), (OX, 930), (OX + 250, 1150)], RED)
    p.rect(OX + 40, 1280, OX + 130, 1440, BROWN, r=14)
    p.dot(OX + 110, 1365, 7)
    p.rect(OX - 150, 1210, OX - 30, 1330, LSKY)
    p.line([(OX - 90, 1210), (OX - 90, 1330)], 7)
    p.line([(OX - 150, 1270), (OX - 30, 1270)], 7)
    p.circle(OX, 1060, 40, LSKY)


@scene("alphabet", 9, "Ii 冰淇淋 Ice cream", "a two-scoop ice cream cone with a cherry")
def alpha_i(p):
    bubble_pair(p, "I", "i", PINK, SKY)
    cone = p.poly([(OX - 150, 1180), (OX + 150, 1180), (OX, 1500)], TAN)
    p.line([(OX - 80, 1180), (OX + 60, 1440)], 7, clip=cone)
    p.line([(OX + 80, 1180), (OX - 60, 1440)], 7, clip=cone)
    p.smooth([(OX - 170, 1180), (OX - 160, 1090), (OX, 1050), (OX + 160, 1090), (OX + 170, 1180),
              (OX + 100, 1215), (OX + 40, 1185), (OX - 30, 1225), (OX - 100, 1190)], PINK)
    p.smooth([(OX - 140, 1080), (OX - 120, 980), (OX, 940), (OX + 120, 980), (OX + 140, 1080), (OX, 1105)], LYELLOW)
    p.circle(OX + 20, 905, 38, RED)
    p.curve([(OX + 30, 870), (OX + 55, 830), (OX + 90, 815)], 7)
    for (dx, dy, a) in ((-60, 1010, 20), (40, 1000, -30), (80, 1060, 60), (-30, 1060, -60)):
        p.line(rotate([(OX + dx - 16, dy), (OX + dx + 16, dy)], a, OX + dx, dy), 8)


@scene("alphabet", 10, "Jj 水母 Jellyfish", "a happy jellyfish with wavy tentacles and bubbles")
def alpha_j(p):
    bubble_pair(p, "J", "j", PURPLE, PINK)
    for k, dx in enumerate((-120, -40, 40, 120)):
        pts = []
        for t in range(13):
            y = 1170 + t * 25
            pts.append((OX + dx + 22 * math.sin(t * 0.9 + k), y))
        left = [(x - 20, y) for x, y in pts]
        right = [(x + 20, y) for x, y in pts][::-1]
        p.poly(catmull(left, False) + catmull(right, False), [PINK, LPURPLE, PINK, LPURPLE][k])
    p.smooth([(OX - 200, 1180), (OX - 190, 1040), (OX - 100, 950), (OX, 930), (OX + 100, 950), (OX + 190, 1040),
              (OX + 200, 1180)], PURPLE)
    scal = []
    for k in range(5, -1, -1):
        x0 = OX - 210 + 70 * k
        scal += ellipse_pts(x0 + 35, 1180, 35, 30, 0, 0, 180, 12)
    p.poly([(OX - 210, 1150), (OX + 210, 1150)] + scal, LPINK)
    p.eye(OX - 70, 1060, 20)
    p.eye(OX + 70, 1060, 20)
    p.smile(OX, 1110, 60)
    for (bx, by, r) in ((OX + 260, 1000, 34), (OX + 300, 900, 22), (OX - 280, 1150, 28)):
        p.circle(bx, by, r, LSKY)


@scene("alphabet", 11, "Kk 风筝 Kite", "a diamond kite with a bow tail and a cloud")
def alpha_k(p):
    bubble_pair(p, "K", "k", ORANGE, BLUE)
    cloud(p, OX - 280, 960, 0.9)
    top, rt, bot, lf, mid = (OX + 60, 880), (OX + 250, 1060), (OX + 60, 1330), (OX - 130, 1060), (OX + 60, 1060)
    for (a, b, col) in ((top, rt, RED), (rt, bot, YELLOW), (bot, lf, RED), (lf, top, YELLOW)):
        p.poly([a, b, mid], col)
    pts = [bot, (OX + 20, 1400), (OX - 40, 1440), (OX - 120, 1460), (OX - 220, 1440)]
    p.curve(pts, 6)
    for (bx, by), col in zip(((OX + 20, 1400), (OX - 120, 1460), (OX - 230, 1440)), (BLUE, GREEN, PURPLE)):
        p.poly([(bx - 62, by - 42), (bx, by - 8), (bx + 62, by - 42), (bx + 62, by + 42), (bx, by + 8), (bx - 62, by + 42)], col)


@scene("alphabet", 12, "Ll 狮子 Lion", "a smiling lion face with a fluffy mane")
def alpha_l(p):
    bubble_pair(p, "L", "l", YELLOW, ORANGE)
    mane = [(OX + (255 if k % 2 == 0 else 215) * math.cos(2 * math.pi * k / 24),
             1190 + (255 if k % 2 == 0 else 215) * math.sin(2 * math.pi * k / 24)) for k in range(24)]
    p.smooth(mane, ORANGE, 6)
    for sx in (-1, 1):
        p.circle(OX + sx * 130, 1040, 52, YELLOW)
        p.circle(OX + sx * 130, 1040, 26, PEACH)
    p.circle(OX, 1200, 175, YELLOW)
    p.ellipse(OX - 45, 1270, 55, 45, WHITE)
    p.ellipse(OX + 45, 1270, 55, 45, WHITE)
    p.smooth([(OX - 38, 1215), (OX + 38, 1215), (OX, 1255)], BROWN, 6)
    p.eye(OX - 70, 1160, 20)
    p.eye(OX + 70, 1160, 20)
    for sx in (-1, 1):
        for dx, dy in ((40, 1265), (65, 1285), (35, 1295)):
            p.dot(OX + sx * dx, dy, 5)


@scene("alphabet", 13, "Mm 月亮 Moon", "a sleepy crescent moon with stars and a cloud")
def alpha_m(p):
    bubble_pair(p, "M", "m", BLUE, PURPLE)
    p.circle(OX - 40, 1180, 240, YELLOW)
    p.circle(OX + 70, 1110, 210, 0)
    p.arc(OX - 165, 1135, 28, 18, 20, 160, 7)
    p.arc(OX - 120, 1240, 30, 22, 30, 150, 7)
    p.circle(OX - 185, 1205, 18, PINK)
    for (sx, sy, r, c) in ((OX + 150, 960, 50, YELLOW), (OX + 270, 1110, 38, ORANGE),
                           (OX + 190, 1270, 44, YELLOW), (OX - 290, 930, 42, ORANGE)):
        p.star(sx, sy, r, c, rounded=True)
    cloud(p, OX + 120, 1440, 1.05, LSKY)


@scene("alphabet", 14, "Nn 鸟窝 Nest", "a bird nest with three eggs on a branch")
def alpha_n(p):
    bubble_pair(p, "N", "n", GREEN, BROWN)
    p.poly([(60, 1380), (1140, 1300), (1140, 1340), (60, 1430)], BROWN)
    p.ellipse(220, 1340, 60, 26, GREEN, rot=-30)
    p.ellipse(1000, 1270, 60, 26, GREEN, rot=20)
    for dx, c in ((-110, LSKY), (0, SKY), (110, LSKY)):
        p.ellipse(OX + dx, 1140, 70, 92, c)
    p.smooth([(OX - 270, 1180), (OX + 270, 1180), (OX + 230, 1310), (OX, 1370), (OX - 230, 1310)], TAN)
    p.arc(OX, 1210, 220, 60, 20, 160, 6)
    p.arc(OX, 1250, 180, 60, 30, 150, 6)
    p.curve([(OX - 200, 1215), (OX - 60, 1270), (OX + 100, 1230)], 6)
    p.heart(OX - 300, 950, 60, PINK)
    p.heart(OX + 300, 980, 50, RED)


@scene("alphabet", 15, "Oo 章鱼 Octopus", "a cheerful octopus with curly arms and bubbles")
def alpha_o(p):
    bubble_pair(p, "O", "o", PINK, PURPLE)
    body = p.new(PURPLE)
    for k, dx in enumerate((-200, -120, -40, 40, 120, 200)):
        p.ellipse(OX + dx, 1330 + (20 if k in (0, 5) else 50), 44, 110, body, rot=(dx / 8))
        p.circle(OX + dx * 1.15, 1440 + (0 if k in (0, 5) else 20), 40, body)
    p.ellipse(OX, 1130, 220, 200, body)
    p.eye(OX - 75, 1130, 24)
    p.eye(OX + 75, 1130, 24)
    p.smile(OX, 1190, 70)
    p.circle(OX - 140, 1200, 26, PINK)
    p.circle(OX + 140, 1200, 26, PINK)
    p.circle(OX - 60, 1010, 26, LPURPLE)
    p.circle(OX + 70, 1000, 20, LPURPLE)
    for (bx, by, r) in ((OX + 300, 980, 34), (OX + 330, 880, 22), (OX - 310, 1000, 28)):
        p.circle(bx, by, r, LSKY)


@scene("alphabet", 16, "Pp 企鹅 Penguin", "a cute penguin wearing a scarf")
def alpha_p(p):
    bubble_pair(p, "P", "p", SKY, BLUE)
    for sx in (-1, 1):
        p.ellipse(OX + sx * 195, 1240, 50, 135, NAVY, rot=-sx * 22)
    p.ellipse(OX, 1190, 200, 275, NAVY)
    p.smooth([(OX, 1010), (OX + 70, 960), (OX + 130, 1000), (OX + 140, 1200), (OX + 110, 1380), (OX, 1430),
              (OX - 110, 1380), (OX - 140, 1200), (OX - 130, 1000), (OX - 70, 960)], WHITE)
    p.eye(OX - 58, 1030, 24)
    p.eye(OX + 58, 1030, 24)
    p.smooth([(OX - 36, 1075), (OX + 36, 1075), (OX, 1115)], ORANGE, 6)
    p.smooth([(OX - 150, 1140), (OX, 1170), (OX + 150, 1140), (OX + 148, 1200), (OX, 1230), (OX - 148, 1200)], RED)
    for sx in (-1, 1):
        p.ellipse(OX + sx * 75, 1470, 75, 32, ORANGE)
    for (x, y) in ((OX - 320, 1000), (OX + 320, 1050)):
        snowflake_ink(p, x, y, 45, 7)


@scene("alphabet", 17, "Qq 皇冠 Queen crown", "a royal crown with jewels")
def alpha_q(p):
    bubble_pair(p, "Q", "q", PURPLE, ROSE)
    pts = [(OX - 260, 1360), (OX - 280, 1020), (OX - 150, 1160), (OX, 960), (OX + 150, 1160),
           (OX + 280, 1020), (OX + 260, 1360)]
    p.poly(pts, YELLOW)
    p.rect(OX - 275, 1300, OX + 275, 1420, ORANGE, r=20)
    for (x, y) in ((OX - 280, 1000), (OX, 935), (OX + 280, 1000)):
        p.circle(x, y, 34, ROSE)
    p.heart(OX, 1205, 60, RED)
    for dx, c in ((-170, SKY), (0, GREEN), (170, SKY)):
        p.ellipse(OX + dx, 1360, 44, 32, c)


@scene("alphabet", 18, "Rr 彩虹 Rainbow", "a rainbow between two clouds")
def alpha_r(p):
    bubble_pair(p, "R", "r", RED, GREEN)
    cols = [RED, ORANGE, YELLOW, GREEN, SKY]
    r0 = 360
    for k, c in enumerate(cols):
        p.pie(OX, 1380, r0 - k * 48, r0 - k * 48, 180, 360, c)
    p.pie(OX, 1380, r0 - 5 * 48, r0 - 5 * 48, 180, 360, 0)
    cloud(p, OX - 300, 1380, 1.2)
    cloud(p, OX + 300, 1380, 1.2)
    p.star(OX - 330, 960, 46, YELLOW, rounded=True)
    p.star(OX + 330, 990, 42, PINK, rounded=True)


@scene("alphabet", 19, "Ss 太阳 Sun", "a smiling sun with pointed rays")
def alpha_s(p):
    bubble_pair(p, "S", "s", ORANGE, YELLOW)
    sun(p, OX, 1190, 165, YELLOW, rays=8, ray_c=ORANGE, ray_len=105)
    p.eye(OX - 58, 1160, 20)
    p.eye(OX + 58, 1160, 20)
    p.smile(OX, 1225, 100)
    p.circle(OX - 105, 1225, 26, PINK)
    p.circle(OX + 105, 1225, 26, PINK)


@scene("alphabet", 20, "Tt 大树 Tree", "an apple tree on a grassy hill")
def alpha_t(p):
    bubble_pair(p, "T", "t", GREEN, BROWN)
    p.band([(-10, 1440), (300, 1415), (600, 1430), (900, 1410), (1210, 1440)], 1620, LGREEN)
    round_tree(p, OX, 1440, 1.25, GREEN, BROWN, RED, 6)
    p.ellipse(OX, 1340, 26, 36, BLACK)
    bird(p, OX - 330, 960)
    bird(p, OX + 340, 1010, 0.8)


@scene("alphabet", 21, "Uu 雨伞 Umbrella", "a colorful umbrella in the rain")
def alpha_u(p):
    bubble_pair(p, "U", "u", BLUE, SKY)
    p.line([(OX, 1150), (OX, 1400)], 14)
    p.arc(OX - 45, 1400, 45, 50, 0, 180, 14)
    umbrella_canopy(p, OX, 930, 1170, 270, [RED, YELLOW, RED, YELLOW])
    p.circle(OX, 915, 22, RED)
    for (dx, dy) in ((-340, 1000), (330, 1020), (-300, 1330), (310, 1340)):
        drop(p, OX + dx, dy, 1.0, SKY)


@scene("alphabet", 22, "Vv 火山 Volcano", "a cartoon volcano puffing clouds of smoke")
def alpha_v(p):
    bubble_pair(p, "V", "v", RED, ORANGE)
    p.band([(-10, 1450), (600, 1440), (1210, 1450)], 1620, LGREEN)
    p.poly([(OX - 330, 1450), (OX - 90, 1090), (OX + 90, 1090), (OX + 330, 1450)], BROWN)
    p.poly([(OX - 90, 1090), (OX + 90, 1090), (OX + 120, 1135), (OX + 70, 1170), (OX + 40, 1230),
            (OX + 10, 1160), (OX - 40, 1200), (OX - 70, 1150), (OX - 120, 1135)], ORANGE)
    cloud(p, OX - 60, 990, 0.8, LGRAY)
    cloud(p, OX + 120, 890, 0.7, LGRAY)
    drop(p, OX - 220, 1020, 0.9, RED)
    drop(p, OX + 250, 1050, 0.9, RED)


@scene("alphabet", 23, "Ww 鲸鱼 Whale", "a happy whale spouting water on the waves")
def alpha_w(p):
    bubble_pair(p, "W", "w", SKY, BLUE)
    body = p.new(BLUE)
    p.smooth([(OX - 260, 1250), (OX - 220, 1100), (OX - 60, 1030), (OX + 120, 1060), (OX + 220, 1170),
              (OX + 300, 1210), (OX + 330, 1300), (OX + 200, 1330), (OX - 40, 1350), (OX - 210, 1330)], body)
    p.poly([(OX + 280, 1220), (OX + 330, 1110), (OX + 360, 1180), (OX + 420, 1140), (OX + 360, 1250)], body)
    p.ellipse(OX - 30, 1330, 220, 70, LSKY, clip=body)
    p.eye(OX - 140, 1180, 20)
    p.smile(OX - 180, 1255, 70)
    p.circle(OX - 90, 1240, 20, PINK)
    p.smooth([(OX - 90, 1030), (OX - 120, 940), (OX - 170, 900), (OX - 90, 920), (OX - 60, 870), (OX - 40, 930),
              (OX + 20, 900), (OX - 30, 960), (OX - 60, 1030)], LSKY)
    wave_band(p, 1400, 18, SKY, period=200)
    sun(p, OX + 300, 950, 60, YELLOW, rays=8, ink_rays=True, ray_len=30)


@scene("alphabet", 24, "Xx 木琴 Xylophone", "a toy xylophone with two mallets")
def alpha_x(p):
    bubble_pair(p, "X", "x", PURPLE, ORANGE)
    p.poly([(OX - 300, 1360), (OX + 300, 1360), (OX + 270, 1440), (OX - 270, 1440)], BROWN)
    cols = [RED, ORANGE, YELLOW, GREEN, SKY, PURPLE]
    for k, c in enumerate(cols):
        x = OX - 250 + k * 100
        hh = 200 - k * 20
        p.rect(x - 38, 1300 - hh, x + 38, 1300 + hh * 0.2, c, r=14)
        p.dot(x, 1300 - hh + 26, 8)
    for (x0, y0, x1, y1) in ((OX - 300, 960, OX - 100, 1100), (OX + 300, 950, OX + 120, 1060)):
        p.line([(x0, y0), (x1, y1)], 12)
        p.circle(x0, y0, 38, ROSE)


@scene("alphabet", 25, "Yy 悠悠球 Yo-yo", "a spinning yo-yo on a string with sparkles")
def alpha_y(p):
    bubble_pair(p, "Y", "y", RED, YELLOW)
    p.arc(OX - 280, 905, 34, 34, 0, 360, 9)
    p.curve([(OX - 260, 930), (OX - 160, 1000), (OX - 60, 1120), (OX, 1200)], 7)
    p.circle(OX, 1210, 230, RED)
    inner = p.circle(OX, 1210, 160, YELLOW)
    p.pie(OX, 1210, 160, 160, 0, 90, SKY, clip=inner)
    p.pie(OX, 1210, 160, 160, 180, 270, SKY, clip=inner)
    p.circle(OX, 1210, 50, WHITE)
    p.dot(OX, 1210, 12)
    for (dx, dy) in ((-250, 1350), (-290, 1270)):
        p.line([(OX + dx, dy), (OX + dx - 70, dy)], 8)
    sparkle(p, OX + 300, 980, 55, YELLOW)
    sparkle(p, OX + 320, 1400, 50, PINK)


@scene("alphabet", 26, "Zz 斑马 Zebra", "a smiling zebra head with a striped mane")
def alpha_z(p):
    bubble_pair(p, "Z", "z", GRAY, GREEN)
    for sx in (-1, 1):
        p.ellipse(OX + sx * 110, 960, 45, 90, WHITE, rot=sx * 25)
        p.ellipse(OX + sx * 110, 965, 20, 55, PINK, rot=sx * 25)
    p.smooth([(OX - 50, 900), (OX + 60, 930), (OX + 40, 1010), (OX - 40, 1010)], GRAY)
    head = p.smooth([(OX, 960), (OX + 140, 1010), (OX + 170, 1160), (OX + 130, 1300), (OX + 150, 1400),
                     (OX, 1460), (OX - 150, 1400), (OX - 130, 1300), (OX - 170, 1160), (OX - 140, 1010)], WHITE)
    p.smooth([(OX - 150, 1320), (OX, 1290), (OX + 150, 1320), (OX + 140, 1430), (OX, 1470), (OX - 140, 1430)], LGRAY)
    for sx in (-1, 1):
        for k, y in enumerate((1080, 1160, 1240)):
            x0 = OX + sx * 175
            p.inkpoly([(x0, y - 22), (OX + sx * (80 - 10 * k), y - 4), (OX + sx * (80 - 10 * k), y + 8), (x0, y + 22)],
                      smooth=False, clip=head)
    p.inkpoly([(OX - 40, 990), (OX, 1060), (OX + 40, 990)], clip=head)
    p.eye(OX - 75, 1150, 20)
    p.eye(OX + 75, 1150, 20)
    p.inkellipse(OX - 50, 1360, 14, 20)
    p.inkellipse(OX + 50, 1360, 14, 20)
    p.arc(OX, 1400, 50, 30, 30, 150, 7)
    p.circle(OX - 230, 1420, 40, YELLOW)
    p.circle(OX + 240, 1410, 34, PINK)


# ===========================================================================
# Tab "elsa": original winter / ice themed scenes (no film characters)
# ===========================================================================
def snowman(p, x, yb, s=1.0, hat=BLUE, band=RED, scarf=RED, buttons=True, mouth=True):
    r1, r2, r3 = 150 * s, 110 * s, 80 * s
    c1 = yb - r1
    c2 = c1 - r1 - r2 + 34 * s
    c3 = c2 - r2 - r3 + 26 * s
    for sx in (-1, 1):
        x0, y0 = x + sx * r2 * 0.88, c2 + 20 * s
        x1, y1 = x + sx * (r2 + 110 * s), c2 - 60 * s
        p.line([(x0, y0), (x1, y1)], max(6, 9 * s))
        p.line([lerp((x0, y0), (x1, y1), 0.65), (x1 - sx * 10 * s, y1 - 55 * s)], max(5, 8 * s))
    p.circle(x, c1, r1, WHITE)
    p.circle(x, c2, r2, WHITE)
    p.circle(x, c3, r3, WHITE)
    by = c3 - r3 * 0.62
    p.rect(x - 62 * s, by - 125 * s, x + 62 * s, by - 6 * s, hat)
    if band:
        p.rect(x - 62 * s, by - 52 * s, x + 62 * s, by - 14 * s, band)
    p.rect(x - 98 * s, by - 18 * s, x + 98 * s, by + 16 * s, hat, r=10 * s)
    ny = c3 + r3 - 14 * s
    p.smooth([(x - r3 * 1.08, ny - 18 * s), (x, ny + 2 * s), (x + r3 * 1.08, ny - 18 * s), (x + r3 * 1.06, ny + 26 * s),
              (x, ny + 44 * s), (x - r3 * 1.06, ny + 26 * s)], scarf, 8)
    p.poly([(x + 28 * s, ny + 34 * s), (x + 76 * s, ny + 28 * s), (x + 84 * s, ny + 96 * s), (x + 38 * s, ny + 102 * s)], scarf)
    p.poly([(x - 6 * s, c3 - 8 * s), (x + 88 * s, c3 + 10 * s), (x - 6 * s, c3 + 26 * s)], ORANGE)
    p.dot(x - 30 * s, c3 - 28 * s, 10 * s)
    p.dot(x + 30 * s, c3 - 28 * s, 10 * s)
    if mouth:
        for k in range(5):
            a = math.radians(30 + 30 * k)
            p.dot(x + 42 * s * math.cos(a), c3 + 22 * s + 20 * s * math.sin(a), 5.5 * s)
    if buttons:
        for dy in (-45, 0, 45):
            p.dot(x, c2 + dy * s, 11 * s)


def penguin(p, x, yb, s=1.0, scarf=None, flip=1):
    for sx in (-1, 1):
        p.ellipse(x + sx * 95 * s, yb - 135 * s, 32 * s, 85 * s, NAVY, rot=-sx * 24)
    p.ellipse(x, yb - 150 * s, 110 * s, 150 * s, NAVY)
    p.smooth([(x, yb - 230 * s), (x + 45 * s, yb - 262 * s), (x + 80 * s, yb - 230 * s), (x + 82 * s, yb - 110 * s),
              (x, yb - 18 * s), (x - 82 * s, yb - 110 * s), (x - 80 * s, yb - 230 * s), (x - 45 * s, yb - 262 * s)], WHITE)
    p.dot(x - 32 * s, yb - 215 * s, 11 * s)
    p.dot(x + 32 * s, yb - 215 * s, 11 * s)
    p.poly([(x - 36 * s, yb - 198 * s), (x + 36 * s, yb - 198 * s), (x, yb - 158 * s)], ORANGE)
    if scarf:
        p.smooth([(x - 104 * s, yb - 128 * s), (x, yb - 108 * s), (x + 104 * s, yb - 128 * s), (x + 100 * s, yb - 84 * s),
                  (x, yb - 64 * s), (x - 100 * s, yb - 84 * s)], scarf, 8)
    for sx in (-1, 1):
        p.ellipse(x + sx * 45 * s, yb - 4 * s, 48 * s, 20 * s, ORANGE)


def snow_star(p, x, y, r, c=LSKY):
    """Filled six-point snowflake star (one region)."""
    return p.poly(catmull(star_pts(x, y, r, r * 0.48, 6, -90), True, 3), c)


def arch_pts(x0, x1, yb, top):
    r = (x1 - x0) / 2
    return [(x0, yb), (x0, top + r)] + ellipse_pts((x0 + x1) / 2, top + r, r, r, 0, 180, 360, 24) + [(x1, top + r), (x1, yb)]


def ice_tower(p, x, top, yb, w, roof_h, wall=SKY, roof=PURPLE, window=ICE, flag=None):
    if flag:
        p.line([(x, top - roof_h - 10), (x, top - roof_h - 90)], 7)
        p.poly([(x, top - roof_h - 92), (x + 75, top - roof_h - 68), (x, top - roof_h - 44)], flag)
    p.rect(x - w / 2, top, x + w / 2, yb, wall)
    p.poly([(x - w / 2 - 26, top + 8), (x, top - roof_h), (x + w / 2 + 26, top + 8)], roof)
    if window:
        p.poly(arch_pts(x - w * 0.18, x + w * 0.18, top + 60 + w * 0.5, top + 50), window)


@scene("elsa", 1, "闪亮大雪花", "one big six-armed ice snowflake in the middle with a few small snowflakes and snowdrifts")
def elsa_01(p):
    p.band([(-10, 1390), (300, 1350), (650, 1400), (1000, 1340), (1210, 1370)], 1620, LICE)
    cx, cy = 600, 760
    p.circle(cx, cy, 470, LSKY)
    arm = [(-30, -90), (-30, -185), (-108, -250), (-78, -288), (-30, -248), (-30, -330), (0, -385),
           (30, -330), (30, -248), (78, -288), (108, -250), (30, -185), (30, -90)]
    for k in range(6):
        p.poly(tf(arm, cx, cy, rot=60 * k), [ICE, SKY][k % 2])
    p.poly([(cx + 120 * math.cos(math.radians(60 * k + 30)), cy + 120 * math.sin(math.radians(60 * k + 30)))
            for k in range(6)], PURPLE)
    p.poly([(cx + 58 * math.cos(math.radians(60 * k)), cy + 58 * math.sin(math.radians(60 * k)))
            for k in range(6)], LPURPLE)
    for (x, y, r) in ((130, 170, 62), (1070, 180, 56), (140, 1250, 50), (1065, 1240, 58), (600, 1480, 60)):
        snow_star(p, x, y, r, [SKY, LPURPLE][int(x) % 2])


@scene("elsa", 2, "冰雪小皇冠", "a sparkling ice tiara with a diamond, sitting on a soft cushion")
def elsa_02(p):
    p.smooth([(220, 1090), (600, 1050), (980, 1090), (1010, 1260), (980, 1400), (600, 1360), (220, 1400), (190, 1260)], PINK)
    for (x, y) in ((205, 1400), (995, 1400)):
        p.line([(x, y), (x - 15 if x < 600 else x + 15, y + 70)], 9)
    pts = [(250, 1010), (255, 760), (360, 860), (430, 620), (520, 800), (600, 470), (680, 800), (770, 620),
           (840, 860), (945, 760), (950, 1010)]
    p.poly(pts, LSKY)
    p.rect(235, 930, 965, 1045, ICE, r=30)
    p.poly([(600, 570), (655, 660), (600, 760), (545, 660)], SKY)
    p.circle(430, 735, 38, PURPLE)
    p.circle(770, 735, 38, PURPLE)
    p.circle(255, 745, 30, PINK)
    p.circle(945, 745, 30, PINK)
    p.star(600, 455, 62, YELLOW, rounded=True)
    for x in (420, 600, 780):
        p.ellipse(x, 988, 48, 30, [SKY, PINK, SKY][(x - 420) // 180])
    for (x, y, r) in ((170, 480, 60), (1030, 500, 55), (190, 1530, 45)):
        sparkle(p, x, y, r, YELLOW)


@scene("elsa", 3, "雪人朋友", "a friendly classic snowman with a top hat, scarf and carrot nose in the snow")
def elsa_03(p):
    p.bg(LSKY)
    cloud(p, 200, 230, 1.0)
    p.band([(-10, 1330), (300, 1290), (600, 1320), (900, 1280), (1210, 1310)], 1620, WHITE)
    snowman(p, 600, 1420, 1.55)
    snow_star(p, 1000, 260, 58, WHITE)
    snow_star(p, 160, 760, 48, WHITE)
    snow_star(p, 1060, 820, 44, WHITE)


@scene("elsa", 4, "冰晶城堡", "a simple sparkling ice castle with three pointed towers")
def elsa_04(p):
    p.band([(-10, 1300), (400, 1280), (800, 1300), (1210, 1270)], 1620, LICE)
    ice_tower(p, 330, 700, 1330, 170, 210, SKY, PURPLE, None)
    ice_tower(p, 870, 700, 1330, 170, 210, SKY, PURPLE, None)
    wall = p.rect(400, 900, 800, 1330, LICE)
    for k in range(5):
        p.rect(405 + k * 82, 860, 445 + k * 82, 905, wall)
    ice_tower(p, 600, 560, 1000, 210, 240, SKY, ICE, ICE, flag=PINK)
    p.poly(arch_pts(530, 670, 1330, 1130), PURPLE)
    p.circle(330, 830, 34, ICE)
    p.circle(870, 830, 34, ICE)
    sparkle(p, 150, 380, 60, YELLOW)
    sparkle(p, 1060, 420, 54, YELLOW)


@scene("elsa", 5, "雪橇滑雪啦", "a wooden sled with a gift box sliding down a snowy hill with pine trees")
def elsa_05(p):
    sun(p, 980, 250, 90, YELLOW, rays=10, ink_rays=True, ray_len=50)
    p.band([(-10, 980), (300, 1040), (700, 1160), (1210, 1290)], 1620, LICE)
    pine(p, 180, 1010, 0.95, tiers=2)
    pine(p, 1020, 1270, 0.85, tiers=2)

    def T(pts):
        return tf(pts, 600, 1060, rot=15)
    p.curve(T([(-215, 8), (170, 8), (232, -10), (240, -50)]), 20)
    p.poly(T([(-110, -215), (60, -215), (60, -60), (-110, -60)]), BLUE)
    p.poly(T([(-45, -215), (-5, -215), (-5, -60), (-45, -60)]), YELLOW)
    x, y = T([(-25, -250)])[0]
    bowtie(p, x, y, 70, 36, PINK, rot=15)
    p.poly(T(rrect_pts(-215, -60, 215, 4, 22)), RED)
    p.curve(T([(232, -40), (300, -90), (380, -70), (460, -120)]), 7)
    snow_star(p, 160, 300, 55, LSKY)
    snow_star(p, 560, 210, 45, LSKY)


@scene("elsa", 6, "冰屋与小企鹅", "a snow igloo on the ice by the sea with two little penguins, an iceberg, sun and clouds")
def elsa_06(p):
    p.bg(LSKY)
    sun(p, 170, 210, 80, YELLOW, rays=10, ink_rays=True, ray_len=40)
    cloud(p, 620, 230, 0.95)
    cloud(p, 980, 380, 0.75)
    p.rect(-10, 760, 1210, 1010, SKY)
    p.poly([(800, 762), (880, 600), (940, 650), (1030, 545), (1130, 762)], WHITE)
    wave = []
    for x in range(-10, 1220, 10):
        wave.append((x, 960 + 16 * math.sin(x / 60)))
    p.poly(wave + [(1210, 1620), (-10, 1620)], LICE)
    dome = p.chord(400, 1180, 270, 250, 180, 360, WHITE)
    for y in (1110, 1040, 970):
        p.line([(100, y), (700, y)], 7, clip=dome)
    for (y0, y1, xs) in ((1110, 1180, (250, 550)), (1040, 1110, (260, 540)), (970, 1040, (330, 470))):
        for x in xs:
            p.line([(x, y0), (x, y1)], 7, clip=dome)
    p.poly(arch_pts(330, 470, 1180, 1050), ICE)
    penguin(p, 820, 1340, 1.0, scarf=RED)
    penguin(p, 1040, 1390, 0.85, scarf=GREEN)
    p.ellipse(560, 1460, 120, 40, SKY)
    snowflake_ink(p, 380, 420, 36, 6)
    snowflake_ink(p, 820, 560, 30, 6)


@scene("elsa", 7, "冰雪城堡全景", "a grand ice castle with five towers, flags, stairs, snowy mountains and pine trees")
def elsa_07(p):
    p.bg(LSKY)
    p.poly([(-10, 900), (250, 430), (520, 900)], LICE)
    p.poly([(250, 430), (310, 540), (270, 520), (240, 560), (200, 520), (190, 540)], WHITE)
    p.poly([(680, 900), (980, 380), (1210, 760), (1210, 900)], LICE)
    p.poly([(980, 380), (1045, 492), (1000, 470), (975, 510), (940, 470), (915, 492)], WHITE)
    p.band([(-10, 1180), (400, 1150), (800, 1170), (1210, 1140)], 1620, WHITE)
    ice_tower(p, 230, 760, 1220, 130, 170, SKY, PURPLE, ICE, flag=PINK)
    ice_tower(p, 970, 760, 1220, 130, 170, SKY, PURPLE, ICE, flag=PINK)
    ice_tower(p, 400, 620, 1220, 150, 200, LSKY, ICE, PURPLE)
    ice_tower(p, 800, 620, 1220, 150, 200, LSKY, ICE, PURPLE)
    wall = p.rect(290, 900, 910, 1230, LICE)
    for k in range(7):
        p.rect(300 + k * 88, 860, 340 + k * 88, 905, wall)
    ice_tower(p, 600, 470, 960, 200, 250, SKY, PURPLE, ICE, flag=YELLOW)
    p.poly(arch_pts(530, 670, 1230, 1050), PURPLE)
    for k, (w, y) in enumerate(((180, 1230), (230, 1280), (280, 1330))):
        p.rect(600 - w / 2, y, 600 + w / 2, y + 50, [LSKY, SKY, LSKY][k])
    pine(p, 90, 1400, 0.75, tiers=3, snow=WHITE)
    pine(p, 1110, 1400, 0.75, tiers=3, snow=WHITE)
    for (x, y) in ((560, 230), (130, 260), (1080, 220)):
        snowflake_ink(p, x, y, 34, 6)


@scene("elsa", 8, "冬日森林小屋", "a cozy log cabin in a snowy pine forest at dusk with smoke, moon and stars, plus a small snowman")
def elsa_08(p):
    p.bg(LPURPLE)
    p.circle(980, 210, 95, YELLOW)
    p.circle(1030, 180, 85, 0)
    for (x, y, r) in ((140, 160, 40), (420, 120, 40), (700, 230, 38), (250, 420, 38)):
        p.star(x, y, r, YELLOW, rounded=True)
    p.band([(-10, 1130), (400, 1100), (800, 1130), (1210, 1100)], 1620, WHITE)
    pine(p, 130, 1150, 0.9, tiers=3, snow=WHITE)
    pine(p, 1080, 1150, 0.9, tiers=3, snow=WHITE)
    cloud(p, 720, 470, 0.55, LGRAY)
    cloud(p, 800, 370, 0.45, LGRAY)
    p.rect(680, 560, 760, 760, BROWN)
    wall = p.rect(360, 840, 840, 1180, TAN)
    for y in (925, 1010, 1095):
        p.line([(360, y), (840, y)], 6, clip=wall)
    roof = p.poly([(310, 860), (600, 600), (890, 860)], RED)
    p.poly([(600, 590), (700, 680), (660, 700), (620, 670), (580, 700), (540, 670), (500, 690)], WHITE, clip=roof)
    p.rect(540, 980, 660, 1180, BROWN, r=14)
    p.dot(640, 1080, 8)
    for x0 in (400, 700):
        p.rect(x0, 900, x0 + 100, 1000, YELLOW)
        p.line([(x0 + 50, 900), (x0 + 50, 1000)], 6)
        p.line([(x0, 950), (x0 + 100, 950)], 6)
    pine(p, 1000, 1480, 0.75, tiers=3, snow=WHITE)
    pine(p, 230, 1500, 0.75, tiers=3, snow=WHITE)


@scene("elsa", 9, "雪人一家", "a family of three snowmen of different sizes with hats and scarves, snowballs and pine trees")
def elsa_09(p):
    p.bg(LSKY)
    sun(p, 1040, 180, 80, YELLOW, rays=10, ink_rays=True, ray_len=40)
    cloud(p, 230, 200, 0.9)
    cloud(p, 640, 300, 0.7)
    p.band([(-10, 1180), (300, 1150), (650, 1190), (1000, 1140), (1210, 1160)], 1620, WHITE)
    snow_star(p, 1000, 520, 48, WHITE)
    snow_star(p, 140, 560, 44, WHITE)
    snowman(p, 330, 1440, 1.0, hat=BLUE, band=RED, scarf=RED)
    snowman(p, 790, 1460, 0.82, hat=PURPLE, band=PINK, scarf=PINK)
    snowman(p, 1090, 1520, 0.6, hat=GREEN, band=None, scarf=ORANGE, mouth=False, buttons=False)
    for (x, y, r) in ((560, 1470, 50), (630, 1490, 38), (595, 1420, 36)):
        p.circle(x, y, r, WHITE)
    snowflake_ink(p, 450, 520, 32, 6)
    snowflake_ink(p, 880, 560, 28, 6)


@scene("elsa", 10, "冰雪森林极光夜", "a snowy pine forest under a glowing aurora night sky with a crescent moon, stars, mountains and a frozen lake")
def elsa_10(p):
    p.bg(ICE)
    for k, (c, y) in enumerate(((GREEN, 230), (SKY, 340), (PURPLE, 450))):
        top = [(x, y + 60 * math.sin(x / 190 + k)) for x in range(-20, 1240, 40)]
        bot = [(x, y + 80 + 50 * math.sin(x / 170 + k + 0.6)) for x in range(1220, -40, -40)]
        p.poly(catmull(top, False, 6) + catmull(bot, False, 6), c)
    p.circle(180, 140, 70, YELLOW)
    p.circle(215, 115, 62, 0)
    for (x, y, r) in ((480, 110, 38), (760, 140, 38), (1060, 110, 38), (950, 640, 36), (330, 660, 36)):
        p.star(x, y, r, YELLOW, rounded=True)
    p.poly([(-10, 1000), (260, 640), (560, 1000)], LICE)
    p.poly([(260, 640), (320, 720), (285, 705), (255, 740), (225, 705), (200, 720)], WHITE)
    p.poly([(420, 1000), (780, 580), (1230, 1060)], LSKY)
    p.poly([(780, 580), (850, 662), (812, 648), (780, 690), (748, 648), (710, 662)], WHITE)
    p.band([(-10, 990), (400, 970), (800, 995), (1210, 975)], 1620, WHITE)
    p.ellipse(600, 1330, 330, 110, LSKY)
    p.arc(560, 1320, 200, 60, 200, 260, 6)
    for (x, yb, s) in ((110, 1150, 0.8), (300, 1090, 0.65), (930, 1100, 0.65), (1110, 1160, 0.8)):
        pine(p, x, yb, s, tiers=3)
    pine(p, 160, 1570, 0.72, tiers=3)
    pine(p, 1050, 1570, 0.72, tiers=3)


# ===========================================================================
# Tab "bluey": original backyard-play themed scenes (no show characters)
# ===========================================================================
def bowtie(p, x, y, w, h, c, rot=0.0):
    pts = [(-w, -h), (0, -h * 0.2), (w, -h), (w, h), (0, h * 0.2), (-w, h)]
    return p.poly(tf(pts, x, y, rot=rot), c)


def beach_ball(p, x, y, r, cols=(RED, YELLOW, BLUE)):
    base = p.circle(x, y, r, WHITE)
    p.pie(x, y, r * 1.1, r * 1.1, -90, 30, cols[0], clip=base)
    p.pie(x, y, r * 1.1, r * 1.1, 30, 150, cols[1], clip=base)
    p.pie(x, y, r * 1.1, r * 1.1, 150, 270, cols[2], clip=base)
    return base


def bucket(p, x, yb, w, h, c=BLUE, band=None, handle=True):
    if handle:
        p.arc(x, yb - h, w * 0.5, h * 0.7, 180, 360, 9)
    p.poly([(x - w / 2, yb - h), (x + w / 2, yb - h), (x + w * 0.4, yb), (x - w * 0.4, yb)], c)
    if band:
        p.poly([(x - w / 2, yb - h), (x + w / 2, yb - h), (x + w * 0.48, yb - h * 0.8), (x - w * 0.48, yb - h * 0.8)], band)


def quad_pt(q, u, v):
    a = lerp(q[0], q[1], u)
    b = lerp(q[3], q[2], u)
    return lerp(a, b, v)


@scene("bluey", 1, "红色大气球", "three shiny party balloons tied with a bow, floating over the grass with clouds and hearts")
def bluey_01(p):
    p.band([(-10, 1420), (400, 1400), (800, 1420), (1210, 1395)], 1620, LGREEN)
    cloud(p, 180, 230, 1.0)
    cloud(p, 1010, 1020, 0.85)
    tie = (610, 1170)
    balloon(p, 400, 560, 1.55, RED, tie)
    balloon(p, 820, 600, 1.45, BLUE, tie)
    balloon(p, 620, 380, 1.5, YELLOW, tie)
    bowtie(p, tie[0], tie[1], 60, 38, PINK)
    p.heart(190, 760, 70, PINK)
    p.heart(1060, 330, 60, ROSE)


@scene("bluey", 2, "飞呀风筝", "a big diamond kite with a bow tail flying high over a green hill on a sunny day")
def bluey_02(p):
    sun(p, 1020, 200, 85, YELLOW, rays=10, ink_rays=True, ray_len=45)
    cloud(p, 210, 260, 1.0)
    cloud(p, 980, 860, 0.8)
    p.band([(-10, 1360), (400, 1300), (800, 1340), (1210, 1290)], 1620, LGREEN)
    top, rt, bot, lf, mid = (600, 260), (860, 540), (600, 920), (340, 540), (600, 540)
    for (a, b, col) in ((top, rt, ORANGE), (rt, bot, SKY), (bot, lf, ORANGE), (lf, top, SKY)):
        p.poly([a, b, mid], col)
    tail = [bot, (540, 1000), (640, 1090), (560, 1180), (620, 1260)]
    p.curve(tail, 7)
    for (x, y), c in zip(tail[1:4], (RED, GREEN, PURPLE)):
        bowtie(p, x, y, 60, 38, c, rot=-10)
    p.curve([(340, 545), (300, 800), (240, 1100), (170, 1360)], 6)
    tulip(p, 900, 1320, 1.2, RED)


@scene("bluey", 3, "神奇魔术帽", "a magician's top hat with bunny ears popping out, a magic wand with a star and sparkles")
def bluey_03(p):
    p.rect(420, 820, 780, 1230, PURPLE)
    hole = p.ellipse(600, 822, 180, 40, NAVY)
    for sx in (-1, 1):
        p.ellipse(600 + sx * 70, 640, 52, 175, WHITE, rot=sx * 10, clip=[0, hole])
        p.ellipse(600 + sx * 72, 650, 24, 120, PINK, rot=sx * 10, clip=[0, hole])
    p.rect(420, 1080, 780, 1150, RED)
    p.ellipse(600, 1240, 270, 52, PURPLE)
    stick = [(-17, 0), (17, 0), (17, 420), (-17, 420)]
    p.poly(tf(stick, 995, 860, rot=25), BLACK)
    p.poly(tf([(-17, 0), (17, 0), (17, 80), (-17, 80)], 995, 860, rot=25), WHITE)
    p.star(1000, 830, 78, YELLOW, rounded=True)
    for (x, y, r) in ((230, 470, 60), (960, 520, 50), (210, 1040, 55)):
        sparkle(p, x, y, r, YELLOW)


@scene("bluey", 4, "冰淇淋圣代", "an ice cream sundae in a glass bowl with three scoops, whipped cream, a cherry and a wafer")
def bluey_04(p):
    p.poly(tf([(-26, 0), (26, 0), (26, 300), (-26, 300)], 820, 560, rot=20), TAN)
    p.circle(600, 760, 125, LYELLOW)
    p.circle(470, 860, 118, PINK)
    p.circle(730, 860, 118, BROWN)
    p.smooth([(470, 690), (520, 620), (560, 560), (600, 530), (640, 560), (680, 620), (730, 690), (600, 720)], WHITE)
    p.circle(600, 490, 48, RED)
    p.curve([(610, 445), (630, 400), (670, 380)], 8)
    p.chord(600, 930, 270, 240, 0, 180, LSKY)
    p.rect(320, 905, 880, 960, SKY, r=24)
    p.rect(570, 1165, 630, 1330, LSKY)
    p.ellipse(600, 1345, 170, 42, SKY)
    for (x, y, a) in ((430, 830, 30), (500, 900, -40), (700, 830, 60), (760, 900, -20), (580, 760, 10), (640, 720, -60)):
        p.line(rotate([(x - 18, y), (x + 18, y)], a, x, y), 9)
    p.heart(190, 520, 60, PINK)
    sparkle(p, 1010, 1180, 55, YELLOW)


@scene("bluey", 5, "沙滩小水桶", "a sand bucket and spade on the beach with a starfish, a shell, a beach ball, sea and sun")
def bluey_05(p):
    p.bg(LSKY)
    sun(p, 180, 200, 80, YELLOW, rays=10, ink_rays=True, ray_len=40)
    wave_band(p, 640, 14, SKY, period=170)
    p.band([(-10, 900), (400, 870), (800, 900), (1210, 875)], 1620, SAND)
    bucket(p, 560, 1330, 420, 420, RED, YELLOW)
    p.poly(tf([(-14, 0), (14, 0), (14, 390), (-14, 390)], 960, 920, rot=-18), YELLOW)
    p.smooth([(1000, 1290), (1100, 1300), (1120, 1400), (1060, 1470), (1000, 1420)], BLUE)
    p.star(220, 1370, 105, ORANGE, ri=48, rounded=True)
    p.pie(760, 1500, 110, 110, 200, 340, PINK)
    for a in (230, 270, 310):
        r = math.radians(a)
        p.line([(760 + 20 * math.cos(r), 1500 + 20 * math.sin(r)), (760 + 85 * math.cos(r), 1500 + 85 * math.sin(r))], 6)
    beach_ball(p, 1040, 1120, 90)


@scene("bluey", 6, "后院大树秋千", "a backyard with a big apple tree and a wooden swing, a picket fence, flowers, sun and clouds")
def bluey_06(p):
    p.bg(LSKY)
    sun(p, 1060, 170, 75, YELLOW, rays=10, ink_rays=True, ray_len=40)
    cloud(p, 760, 220, 0.75)
    p.band([(-10, 1150), (400, 1130), (800, 1150), (1210, 1130)], 1620, LGREEN)
    fence = p.new(TAN)
    p.rect(-10, 1000, 1210, 1035, fence)
    p.rect(-10, 1062, 1210, 1095, fence)
    for k in range(10):
        x = 30 + k * 130
        p.poly([(x - 26, 1160), (x - 26, 960), (x, 925), (x + 26, 960), (x + 26, 1160)], fence)
    trunk = p.poly([(170, 1200), (230, 700), (330, 700), (380, 1200)], BROWN)
    p.poly([(300, 690), (860, 600), (870, 650), (310, 760)], trunk)
    canopy = p.new(GREEN)
    for (x, y, r) in ((170, 420, 200), (400, 330, 190), (600, 430, 160), (290, 560, 150), (60, 600, 120)):
        p.circle(x, y, r, canopy)
    for (x, y) in ((140, 330), (330, 260), (470, 420), (230, 520), (580, 380)):
        p.circle(x, y, 28, RED)
    p.line([(640, 640), (640, 1080)], 7)
    p.line([(800, 615), (800, 1080)], 7)
    p.rect(600, 1070, 840, 1112, RED, r=12)
    for (x, y, c) in ((950, 1290, PINK), (1090, 1380, YELLOW), (430, 1390, PURPLE)):
        flower(p, x, y, 1.15, c, YELLOW if c != YELLOW else ORANGE)
    beach_ball(p, 700, 1420, 75, (BLUE, YELLOW, RED))


@scene("bluey", 7, "草地野餐", "a picnic on the grass with a checkered blanket, a basket, sandwiches, juice cups and apples under a tree")
def bluey_07(p):
    p.bg(LSKY)
    sun(p, 160, 170, 75, YELLOW, rays=10, ink_rays=True, ray_len=40)
    cloud(p, 560, 220, 0.85)
    p.band([(-10, 820), (400, 790), (800, 820), (1210, 790)], 1620, LGREEN)
    round_tree(p, 1000, 930, 1.0, GREEN, BROWN, RED, 3)
    q = [(150, 1020), (1050, 1020), (1160, 1500), (40, 1500)]
    p.poly(q, RED)
    for i in range(4):
        for j in range(3):
            if (i + j) % 2 == 0:
                p.poly([quad_pt(q, i / 4, j / 3), quad_pt(q, (i + 1) / 4, j / 3),
                        quad_pt(q, (i + 1) / 4, (j + 1) / 3), quad_pt(q, i / 4, (j + 1) / 3)], WHITE)
    p.arc(380, 1060, 120, 120, 180, 360, 12)
    basket = p.poly([(250, 1060), (510, 1060), (480, 1260), (280, 1260)], TAN)
    p.line([(240, 1125), (520, 1125)], 6, clip=basket)
    p.line([(240, 1190), (520, 1190)], 6, clip=basket)
    p.ellipse(720, 1330, 150, 50, WHITE)
    p.poly([(620, 1310), (820, 1310), (720, 1180)], TAN)
    p.poly([(625, 1300), (815, 1300), (805, 1325), (635, 1325)], GREEN)
    for (x, c) in ((900, PINK), (1010, SKY)):
        p.poly([(x - 45, 1110), (x + 45, 1110), (x + 34, 1240), (x - 34, 1240)], c)
    p.circle(400, 1400, 60, RED)
    p.ellipse(430, 1325, 30, 14, GREEN, rot=-30)
    p.circle(540, 1420, 50, GREEN)


@scene("bluey", 8, "海滩堆沙堡", "a sandcastle with towers and flags on a sunny beach, with a beach umbrella, sailboat, bucket, starfish and shell")
def bluey_08(p):
    p.bg(LSKY)
    sun(p, 1060, 160, 75, YELLOW, rays=10, ink_rays=True, ray_len=40)
    cloud(p, 520, 200, 0.85)
    wave_band(p, 640, 12, SKY, period=160)
    p.poly([(840, 630), (1060, 630), (1020, 690), (880, 690)], RED)
    p.line([(950, 630), (950, 420)], 7)
    p.poly([(955, 430), (955, 610), (1060, 610)], WHITE)
    p.poly([(945, 450), (945, 610), (860, 610)], YELLOW)
    p.band([(-10, 900), (400, 870), (800, 900), (1210, 880)], 1620, SAND)
    p.line([(250, 1250), (250, 900)], 12)
    umbrella_canopy(p, 250, 740, 900, 220, [RED, WHITE, RED, WHITE], scallop=26)
    for (x, top) in ((380, 990), (820, 990)):
        t = p.rect(x - 60, top, x + 60, 1320, TAN)
        for k in range(3):
            p.rect(x - 60 + k * 45, top - 36, x - 30 + k * 45, top + 4, t)
        p.line([(x, top - 36), (x, top - 120)], 6)
        p.poly([(x, top - 120), (x + 60, top - 100), (x, top - 80)], PINK if x < 600 else SKY)
    p.rect(440, 1100, 760, 1320, SAND)
    c = p.rect(510, 880, 690, 1150, TAN)
    for k in range(4):
        p.rect(510 + k * 50, 844, 540 + k * 50, 884, c)
    p.poly(arch_pts(550, 650, 1320, 1200), BROWN)
    p.poly(arch_pts(575, 625, 1040, 960), BROWN)
    bucket(p, 1010, 1420, 150, 150, BLUE)
    p.star(170, 1440, 80, ORANGE, ri=36, rounded=True)
    p.pie(680, 1500, 80, 80, 200, 340, PINK)
    beach_ball(p, 450, 1480, 75)


@scene("bluey", 9, "客厅气球派对", "a cozy living room balloon party with a sofa, cushions, a window with curtains, a lamp, a rug and bunting")
def bluey_09(p):
    p.bg(LYELLOW)
    floor = p.rect(-10, 1150, 1210, 1620, TAN)
    for y in (1260, 1380, 1500):
        p.line([(-10, y), (1210, y)], 6, clip=floor)
    p.ellipse(600, 1400, 400, 115, PURPLE)
    p.ellipse(600, 1400, 290, 70, LPURPLE)
    p.rect(700, 300, 1080, 720, WHITE)
    p.rect(730, 330, 1050, 690, LSKY)
    p.line([(890, 330), (890, 690)], 8)
    p.line([(730, 510), (1050, 510)], 8)
    for (x0, x1) in ((640, 730), (1050, 1140)):
        p.smooth([(x0, 270), (x1, 270), (x1 - 10, 500), (x1 + 10, 770), (x0 - 10, 770), (x0 + 10, 500)], PINK, 6)
    p.rect(150, 820, 610, 1030, RED, r=40)
    p.rect(150, 980, 610, 1110, RED, r=20)
    p.rect(110, 900, 205, 1150, RED, r=30)
    p.rect(555, 900, 650, 1150, RED, r=30)
    p.rect(220, 870, 370, 985, YELLOW, r=30)
    p.rect(390, 870, 540, 985, SKY, r=30)
    p.line([(1050, 780), (1050, 1150)], 10)
    p.ellipse(1050, 1160, 75, 22, BROWN)
    p.poly([(960, 790), (1140, 790), (1105, 640), (995, 640)], YELLOW)
    balloon(p, 300, 470, 1.0, RED, (330, 760))
    balloon(p, 480, 360, 1.0, BLUE, (450, 650))
    balloon(p, 860, 940, 0.9, GREEN, (880, 1140))
    bunting(p, [(-10, 110), (300, 170), (600, 190), (900, 170), (1210, 110)], [RED, YELLOW, SKY, GREEN, PURPLE, ORANGE, PINK])


@scene("bluey", 10, "后院滑梯和沙池", "a backyard playground with a slide and ladder, a sandbox with a bucket and spade, a ball, flowers and a butterfly")
def bluey_10(p):
    p.bg(LSKY)
    sun(p, 1060, 170, 75, YELLOW, rays=10, ink_rays=True, ray_len=40)
    cloud(p, 640, 230, 0.8)
    cloud(p, 230, 170, 0.6)
    p.band([(-10, 1000), (400, 980), (800, 1000), (1210, 975)], 1620, LGREEN)
    top = catmull([(400, 590), (520, 700), (610, 1000), (700, 1180), (820, 1240)], False, 12)
    bot = [(x, y + 70) for x, y in top]
    p.poly(top + bot[::-1], RED)
    for k in range(5):
        y = 690 + k * 115
        p.rect(170 + (1250 - y) * 0.09, y, 340 + (1250 - y) * 0.09, y + 34, YELLOW)
    p.poly([(150, 1260), (210, 590), (245, 590), (185, 1260)], BLUE)
    p.poly([(330, 1260), (390, 590), (425, 590), (365, 1260)], BLUE)
    p.rect(190, 560, 440, 600, BLUE, r=10)
    p.rect(700, 1270, 1150, 1500, BROWN)
    p.rect(735, 1300, 1115, 1465, SAND)
    bucket(p, 860, 1430, 120, 110, PINK)
    p.poly(tf([(-10, 0), (10, 0), (10, 150), (-10, 150)], 1020, 1270, rot=-20), YELLOW)
    p.smooth([(1050, 1405), (1100, 1410), (1095, 1455), (1055, 1450)], GREEN)
    beach_ball(p, 520, 1440, 80)
    flower(p, 120, 1380, 1.1, PURPLE, YELLOW)
    flower(p, 300, 1450, 1.0, ORANGE, YELLOW)
    for sx in (-1, 1):
        p.ellipse(930 + sx * 38, 760, 38, 30, PINK, rot=sx * 25)
        p.ellipse(930 + sx * 32, 805, 28, 22, PURPLE, rot=-sx * 25)
    p.ellipse(930, 785, 10, 42, BLACK)
    p.curve([(925, 745), (910, 710), (895, 700)], 5)
    p.curve([(935, 745), (950, 710), (965, 700)], 5)


# ===========================================================================
# Tab "peppa": original rainy-day / family-outing themed scenes (no characters)
# ===========================================================================
def rain_boot(p, x0, yb, s=1.0, c=RED, cuff=YELLOW, sole=BROWN, flip=False):
    sx = -1 if flip else 1

    def X(dx):
        return x0 + sx * dx * s
    body = [(X(0), yb - 520 * s), (X(150), yb - 520 * s), (X(150), yb - 190 * s), (X(235), yb - 165 * s),
            (X(265), yb - 90 * s), (X(262), yb - 40 * s), (X(0), yb - 40 * s)]
    p.poly(catmull(body, True, 4), c)
    p.rect(min(X(-6), X(268)), yb - 50 * s, max(X(-6), X(268)), yb, sole, r=12 * s)
    p.rect(min(X(-12), X(162)), yb - 545 * s, max(X(-12), X(162)), yb - 470 * s, cuff, r=14 * s)


def car(p, x, yb, s=1.0, body=RED, win=LSKY, wheel=NAVY, hub=LGRAY, light=YELLOW):
    p.smooth([(x - 300 * s, yb - 80 * s), (x - 290 * s, yb - 190 * s), (x - 170 * s, yb - 210 * s),
              (x - 120 * s, yb - 330 * s), (x + 110 * s, yb - 330 * s), (x + 180 * s, yb - 210 * s),
              (x + 290 * s, yb - 190 * s), (x + 310 * s, yb - 80 * s), (x, yb - 60 * s)], body, 6)
    p.poly([(x - 140 * s, yb - 210 * s), (x - 100 * s, yb - 300 * s), (x - 15 * s, yb - 300 * s), (x - 15 * s, yb - 210 * s)], win)
    p.poly([(x + 15 * s, yb - 210 * s), (x + 15 * s, yb - 300 * s), (x + 95 * s, yb - 300 * s), (x + 150 * s, yb - 210 * s)], win)
    p.ellipse(x + 270 * s, yb - 160 * s, 26 * s, 22 * s, light)
    p.line([(x + 40 * s, yb - 170 * s), (x + 85 * s, yb - 170 * s)], max(6, 8 * s))
    for dx in (-170, 170):
        p.circle(x + dx * s, yb - 70 * s, 72 * s, wheel)
        p.circle(x + dx * s, yb - 70 * s, 30 * s, hub)


def hot_air_balloon(p, x, y, s, cols, basket=BROWN):
    p.line([(x - 60 * s, y + 170 * s), (x - 42 * s, y + 255 * s)], 6)
    p.line([(x + 60 * s, y + 170 * s), (x + 42 * s, y + 255 * s)], 6)
    p.rect(x - 55 * s, y + 250 * s, x + 55 * s, y + 340 * s, basket, r=10 * s)
    env = p.smooth([(x, y - 200 * s), (x + 150 * s, y - 140 * s), (x + 175 * s, y), (x + 120 * s, y + 120 * s),
                    (x + 62 * s, y + 180 * s), (x - 62 * s, y + 180 * s), (x - 120 * s, y + 120 * s),
                    (x - 175 * s, y), (x - 150 * s, y - 140 * s)], cols[0])
    n = len(cols)
    for k in range(1, n, 2):
        u0, u1 = -1 + 2 * k / n, -1 + 2 * (k + 1) / n
        left = [(x + 200 * s * u0 * math.sin(math.pi * t / 20) ** 0.8, y - 200 * s + t * 20 * s) for t in range(21)]
        right = [(x + 200 * s * u1 * math.sin(math.pi * t / 20) ** 0.8, y - 200 * s + t * 20 * s) for t in range(21)]
        p.poly(left + right[::-1], cols[k], clip=env)
    p.rect(x - 64 * s, y + 160 * s, x + 64 * s, y + 195 * s, cols[-1], r=8 * s)


def lighthouse(p, x, yb, s=1.0):
    body = p.poly([(x - 85 * s, yb), (x - 55 * s, yb - 430 * s), (x + 55 * s, yb - 430 * s), (x + 85 * s, yb)], WHITE)
    for k in (0, 2):
        y0 = yb - 430 * s + (k + 1) * 430 * s / 4
        p.rect(x - 100 * s, y0 - 50 * s, x + 100 * s, y0 + 50 * s, RED, clip=body)
    p.rect(x - 75 * s, yb - 470 * s, x + 75 * s, yb - 430 * s, NAVY)
    p.rect(x - 50 * s, yb - 560 * s, x + 50 * s, yb - 470 * s, YELLOW)
    p.poly([(x - 72 * s, yb - 560 * s), (x, yb - 630 * s), (x + 72 * s, yb - 560 * s)], RED)
    p.poly(arch_pts(x - 26 * s, x + 26 * s, yb, yb - 110 * s), BROWN)


@scene("peppa", 1, "雨靴跳泥坑", "a pair of shiny rain boots splashing in a muddy puddle under a rain cloud")
def peppa_01(p):
    p.smooth([(150, 1360), (400, 1260), (800, 1250), (1060, 1330), (1000, 1470), (600, 1500), (220, 1470)], BROWN)
    for (x, y) in ((140, 1180), (1080, 1170)):
        drop(p, x, y, 1.0, BROWN)
    rain_boot(p, 230, 1380, 1.0, RED, YELLOW, NAVY)
    rain_boot(p, 640, 1340, 1.0, RED, YELLOW, NAVY)
    cloud(p, 600, 250, 1.6, LGRAY)
    for (x, y) in ((360, 520), (560, 560), (760, 520), (960, 580)):
        drop(p, x, y, 1.0, SKY)


@scene("peppa", 2, "小雨伞", "a big colorful umbrella with a curved handle in the rain, with a cloud and a puddle")
def peppa_02(p):
    p.ellipse(600, 1420, 360, 80, LSKY)
    p.arc(600, 1420, 220, 40, 200, 340, 6)
    p.line([(600, 800), (600, 1250)], 16)
    p.arc(540, 1250, 60, 60, 0, 180, 16)
    umbrella_canopy(p, 600, 370, 800, 420, [RED, YELLOW, SKY, GREEN, PURPLE, ORANGE], scallop=30)
    p.circle(600, 350, 30, PINK)
    cloud(p, 200, 180, 0.9, LGRAY)
    for (x, y) in ((1040, 230), (150, 950), (1050, 960), (900, 1220), (280, 1200)):
        drop(p, x, y, 1.1, SKY)


@scene("peppa", 3, "滑板车", "a kick scooter with a red deck and big wheels on a sunny path")
def peppa_03(p):
    sun(p, 1020, 220, 85, YELLOW, rays=10, ink_rays=True, ray_len=45)
    cloud(p, 230, 250, 1.0)
    p.band([(-10, 1330), (400, 1310), (800, 1330), (1210, 1310)], 1620, LGREEN)
    p.poly([(790, 1170), (840, 1170), (760, 640), (712, 646)], LGRAY)
    p.rect(600, 590, 860, 640, BLUE, r=20)
    p.rect(570, 580, 660, 650, PINK, r=20)
    p.rect(830, 580, 920, 650, PINK, r=20)
    p.rect(270, 1130, 880, 1200, RED, r=30)
    for x in (340, 860):
        p.circle(x, 1250, 85, NAVY)
        p.circle(x, 1250, 34, YELLOW)


@scene("peppa", 4, "生日蛋糕", "a two-layer birthday cake with frosting, hearts and three candles on a plate")
def peppa_04(p):
    p.ellipse(600, 1340, 370, 62, LSKY)
    bot = p.rect(280, 1050, 920, 1330, PINK, r=24)
    pts = [(275, 1045), (925, 1045)]
    for k in range(10, -1, -1):
        pts.append((280 + 64 * k, 1110 + (34 if k % 2 == 0 else 0)))
    p.poly(catmull(pts[:2], False, 1) + catmull(pts[2:], False, 6), WHITE, clip=bot)
    top = p.rect(390, 820, 810, 1060, LYELLOW, r=20)
    pts = [(385, 815), (815, 815)]
    for k in range(7, -1, -1):
        pts.append((390 + 60 * k, 870 + (30 if k % 2 == 0 else 0)))
    p.poly(catmull(pts[:2], False, 1) + catmull(pts[2:], False, 6), PINK, clip=top)
    for x, c in ((490, SKY), (600, GREEN), (710, PURPLE)):
        p.rect(x - 20, 690, x + 20, 822, c, r=6)
        p.smooth([(x, 600), (x + 26, 650), (x, 680), (x - 26, 650)], ORANGE, 6)
    p.heart(420, 1220, 55, RED)
    p.heart(780, 1220, 55, RED)
    sparkle(p, 170, 600, 60, YELLOW)
    sparkle(p, 1030, 640, 55, YELLOW)


@scene("peppa", 5, "红色小汽车", "a cute little red family car driving on a road under the sun")
def peppa_05(p):
    sun(p, 1000, 230, 90, YELLOW, rays=10, ink_rays=True, ray_len=45)
    cloud(p, 230, 260, 1.0)
    p.band([(-10, 1080), (400, 1060), (800, 1080), (1210, 1060)], 1620, LGREEN)
    p.rect(-10, 1180, 1210, 1420, LGRAY)
    for x in (150, 1050):
        p.rect(x - 70, 1290, x + 70, 1315, WHITE)
    car(p, 600, 1320, 1.35)


@scene("peppa", 6, "茶话会", "a little tea party: a table with a scalloped tablecloth, a teapot, cups and saucers, cupcakes and a flower vase, with bunting")
def peppa_06(p):
    p.bg(LYELLOW)
    bunting(p, [(-10, 120), (300, 190), (600, 210), (900, 190), (1210, 120)], [RED, SKY, GREEN, PURPLE, ORANGE, PINK, BLUE])
    p.rect(-10, 1300, 1210, 1620, TAN)
    p.rect(160, 1100, 200, 1420, BROWN)
    p.rect(1000, 1100, 1040, 1420, BROWN)
    pts = [(80, 900), (1120, 900)]
    for k in range(12, -1, -1):
        x0 = 80 + 80 * k
        pts += ellipse_pts(x0 + 40, 1150, 40, 40, 0, 0, 180, 10)
    p.poly(pts, PINK)
    p.rect(60, 880, 1140, 930, ROSE, r=12)
    # vase + flowers
    p.smooth([(140, 880), (220, 880), (240, 800), (210, 700), (150, 700), (120, 800)], SKY)
    for (x, y, c) in ((140, 560, YELLOW), (240, 600, RED)):
        flower(p, x, y, 0.9, c, ORANGE if c == YELLOW else YELLOW, stem_len=130, leaf=None)
    # teapot
    p.arc(580, 740, 70, 80, 270, 450, 16)
    p.poly([(400, 760), (300, 640), (330, 625), (440, 730)], PURPLE)
    p.ellipse(480, 760, 140, 120, PURPLE)
    p.chord(480, 660, 90, 40, 180, 360, LPURPLE)
    p.circle(480, 600, 24, YELLOW)
    p.heart(480, 780, 46, PINK)
    # cups
    for (x, c) in ((720, SKY), (1000, GREEN)):
        p.arc(x + 70, 820, 34, 30, 270, 450, 12)
        p.ellipse(x, 880, 110, 22, WHITE)
        p.chord(x, 790, 75, 85, 0, 180, c)
    # cupcakes on stand
    p.rect(845, 640, 865, 860, LGRAY)
    p.ellipse(855, 640, 120, 18, LGRAY)
    for dx, c in ((-60, PINK), (60, YELLOW)):
        p.poly([(855 + dx - 45, 625), (855 + dx + 45, 625), (855 + dx + 35, 560), (855 + dx - 35, 560)], ORANGE)
        p.smooth([(855 + dx - 50, 565), (855 + dx, 490), (855 + dx + 50, 565)], c, 6)


@scene("peppa", 7, "菜园和小火车", "a vegetable garden with carrots, cabbages and a sunflower, and a toy steam train puffing along the track")
def peppa_07(p):
    p.bg(LSKY)
    sun(p, 1060, 160, 70, YELLOW, rays=10, ink_rays=True, ray_len=36)
    cloud(p, 560, 170, 0.8)
    p.band([(-10, 560), (300, 500), (600, 540), (900, 490), (1210, 540)], 1620, LGREEN)
    # train
    p.rect(-10, 830, 1210, 860, GRAY)
    cloud(p, 220, 380, 0.55, WHITE)
    p.rect(150, 560, 390, 640, RED, r=10)
    p.rect(170, 640, 370, 800, RED)
    p.rect(210, 670, 330, 750, LSKY)
    p.rect(370, 680, 600, 800, BLUE, r=14)
    p.poly([(470, 680), (450, 560), (550, 560), (520, 680)], NAVY)
    p.poly([(590, 730), (670, 815), (590, 815)], YELLOW)
    for (x, r) in ((240, 50), (420, 38), (540, 38)):
        p.circle(x, 810, r, NAVY)
        p.dot(x, 810, 10)
    for x0, c in ((680, GREEN), (920, ORANGE)):
        p.line([(x0 - 30, 760), (x0, 760)], 10)
        p.rect(x0, 640, x0 + 210, 800, c, r=12)
        for dx in (50, 160):
            p.circle(x0 + dx, 815, 36, NAVY)
            p.dot(x0 + dx, 815, 9)
    # garden
    p.rect(-10, 1000, 1210, 1620, BROWN)
    for k, x in enumerate(range(130, 1200, 235)):
        p.poly([(x - 34, 1085), (x + 34, 1085), (x, 1172)], ORANGE)
        p.smooth([(x - 30, 1088), (x - 48, 1030), (x - 22, 1040), (x - 10, 1000), (x, 1030), (x + 12, 1000),
                  (x + 22, 1040), (x + 48, 1030), (x + 30, 1088)], GREEN, 6)
    for x in range(160, 1200, 280):
        p.circle(x, 1300, 80, LGREEN)
        p.ellipse(x, 1310, 42, 52, GREEN)
    for x in range(270, 1100, 280):
        p.ellipse(x, 1490, 90, 62, ORANGE)
        p.arc(x, 1490, 40, 52, 120, 240, 6)
        p.arc(x, 1490, 40, 52, -60, 60, 6)
        p.line([(x, 1428), (x + 10, 1395)], 12)
    p.rect(1080, 880, 1100, 1060, GREEN)
    p.circle(1090, 860, 70, YELLOW)
    p.circle(1090, 860, 32, BROWN)


@scene("peppa", 8, "热气球飞上天", "three striped hot-air balloons floating over green hills with clouds, birds and little houses")
def peppa_08(p):
    p.bg(LSKY)
    cloud(p, 200, 200, 0.9)
    cloud(p, 1000, 640, 0.8)
    cloud(p, 640, 960, 0.6)
    hot_air_balloon(p, 340, 520, 1.2, [RED, YELLOW, RED, YELLOW, RED, BLUE])
    hot_air_balloon(p, 850, 330, 0.95, [SKY, WHITE, SKY, WHITE, SKY, PINK])
    hot_air_balloon(p, 900, 900, 0.7, [GREEN, ORANGE, GREEN, ORANGE, GREEN, PURPLE])
    bird(p, 600, 230)
    bird(p, 680, 290, 0.8)
    p.band([(-10, 1260), (300, 1180), (600, 1260), (900, 1200), (1210, 1270)], 1620, GREEN)
    p.band([(-10, 1430), (400, 1380), (800, 1440), (1210, 1390)], 1620, LGREEN)
    house(p, 260, 1320, 160, 120, PEACH, RED, BROWN, LSKY, chimney=False)
    house(p, 900, 1330, 160, 120, LYELLOW, BLUE, BROWN, LSKY, chimney=False)
    for x in (560, 1100):
        p.circle(x, 1300, 50, FOREST)


@scene("peppa", 9, "海边度假", "a sunny seaside holiday with a striped lighthouse, sailboat, beach umbrella, deck chair, sandcastle, beach ball and shells")
def peppa_09(p):
    p.bg(LSKY)
    sun(p, 160, 170, 75, YELLOW, rays=10, ink_rays=True, ray_len=40)
    cloud(p, 640, 180, 0.8)
    wave_band(p, 620, 12, SKY, period=180)
    p.poly([(560, 650), (780, 650), (740, 710), (600, 710)], ORANGE)
    p.line([(670, 650), (670, 420)], 7)
    p.poly([(676, 430), (676, 630), (780, 630)], WHITE)
    p.poly([(664, 450), (664, 630), (570, 630)], RED)
    p.band([(-10, 880), (300, 860), (600, 890), (1210, 860)], 1620, SAND)
    p.poly([(820, 900), (1210, 760), (1210, 940)], GRAY)
    lighthouse(p, 1040, 900, 1.0)
    p.line([(260, 1300), (300, 930)], 12)
    umbrella_canopy(p, 300, 780, 940, 230, [YELLOW, BLUE, YELLOW, BLUE], scallop=26)
    p.poly([(420, 1180), (640, 1180), (700, 1300), (480, 1300)], SKY)
    p.poly([(400, 1060), (470, 1050), (520, 1185), (440, 1190)], SKY)
    p.line([(480, 1300), (440, 1380)], 9)
    p.line([(700, 1300), (730, 1380)], 9)
    p.rect(780, 1260, 980, 1400, TAN)
    for k in range(3):
        p.rect(780 + k * 75, 1225, 830 + k * 75, 1265, TAN)
    p.poly(arch_pts(850, 910, 1400, 1320), BROWN)
    beach_ball(p, 160, 1420, 80)
    p.star(400, 1490, 60, ORANGE, ri=27, rounded=True)
    p.pie(1080, 1500, 70, 70, 200, 340, PINK)


@scene("peppa", 10, "雨后彩虹泥坑", "after the rain: a rainbow over a hill with a little house and tree, muddy puddles, rain boots, an umbrella and flowers")
def peppa_10(p):
    p.bg(LSKY)
    for k, c in enumerate((RED, ORANGE, YELLOW, GREEN, SKY, PURPLE)):
        p.pie(600, 900, 560 - k * 50, 560 - k * 50, 180, 360, c)
    p.pie(600, 900, 260, 260, 180, 360, 0)
    cloud(p, 120, 820, 1.1)
    cloud(p, 1080, 820, 1.1)
    p.band([(-10, 980), (300, 760), (600, 700), (900, 760), (1210, 980)], 1620, LGREEN)
    house(p, 600, 860, 200, 150, LYELLOW, RED, BROWN, LSKY, chimney=True)
    round_tree(p, 950, 960, 0.6, GREEN, BROWN, None)
    for (x, y, rx) in ((300, 1250, 200), (850, 1400, 240)):
        p.ellipse(x, y, rx, 60, BROWN)
    rain_boot(p, 620, 1330, 0.5, YELLOW, RED, NAVY)
    rain_boot(p, 760, 1330, 0.5, YELLOW, RED, NAVY)
    p.line([(160, 1560), (230, 1330)], 12)
    p.poly(tf(arch_pts(-150, 150, 0, -130)[1:-1], 260, 1330, rot=20), PINK)
    for (x, c) in ((420, PURPLE), (1080, ORANGE)):
        flower(p, x, 1440, 1.0, c, YELLOW)


# ===========================================================================
# Tab "anpanman": original bakery / bread themed scenes (no characters, no faces on bread)
# ===========================================================================
def stroke(p, pts, w, c):
    """Thick filled stroke painted into the region-id buffer (one region)."""
    i = p._id(c)
    s = p.ss
    r = w * s / 2
    P = p._P(pts)
    for (x0, y0), (x1, y1) in zip(P[:-1], P[1:]):
        n = max(1, int(math.hypot(x1 - x0, y1 - y0) / (r * 0.2)))
        for k in range(n + 1):
            x, y = x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n
            p.d.ellipse([x - r, y - r, x + r, y + r], fill=i)
    return i


def baguette(p, x, y, length, rot=0.0, c=TAN, cut=LYELLOW, ncut=3):
    body = p.poly(ellipse_pts(x, y, length / 2, length * 0.13, rot, n=120), c)
    for k in range(ncut):
        t = (k + 0.5) / ncut - 0.5
        cx = x + math.cos(math.radians(rot)) * t * length * 0.8
        cy = y + math.sin(math.radians(rot)) * t * length * 0.8
        p.ellipse(cx, cy, length * 0.09, length * 0.035, cut, rot=rot - 30, clip=body)
    return body


def croissant(p, x, y, s=1.0, c=LORANGE, c2=TAN, rot=0.0):
    parts = [(-150, 30, 55, 34, -40, c2), (150, 30, 55, 34, 40, c2), (-85, -10, 70, 52, -25, c),
             (85, -10, 70, 52, 25, c), (0, -30, 85, 70, 0, c2)]
    for (dx, dy, rx, ry, a, col) in parts:
        cx, cy = rotate([(dx * s, dy * s)], rot)[0]
        p.ellipse(x + cx, y + cy, rx * s, ry * s, col, rot=a + rot)


def donut(p, x, y, r, icing=PINK, dough=TAN, hole_id=0):
    p.circle(x, y, r, dough)
    ic = [(x + r * (0.84 + 0.06 * math.sin(6 * t)) * math.cos(t), y + r * (0.84 + 0.06 * math.sin(6 * t)) * math.sin(t))
          for t in [2 * math.pi * k / 48 for k in range(48)]]
    p.poly(ic, icing)
    p.circle(x, y, r * 0.3, hole_id)
    for k in range(5):
        a = 2 * math.pi * k / 5 + 0.3
        rx, ry = x + r * 0.58 * math.cos(a), y + r * 0.58 * math.sin(a)
        p.line(rotate([(rx - r * 0.1, ry), (rx + r * 0.1, ry)], k * 47, rx, ry), max(5, r * 0.07))


def cupcake(p, x, yb, s=1.0, wrap=SKY, frost=PINK, cherry=RED, pleats=3):
    w = p.poly([(x - 120 * s, yb - 200 * s), (x + 120 * s, yb - 200 * s), (x + 90 * s, yb), (x - 90 * s, yb)], wrap)
    for k in range(1, pleats + 1):
        t = k / (pleats + 1)
        p.line([(x - 120 * s + 240 * s * t, yb - 200 * s), (x - 90 * s + 180 * s * t, yb)], max(6, 8 * s), clip=w)
    p.smooth([(x - 140 * s, yb - 200 * s), (x - 130 * s, yb - 270 * s), (x, yb - 300 * s), (x + 130 * s, yb - 270 * s),
              (x + 140 * s, yb - 200 * s), (x, yb - 180 * s)], frost)
    p.smooth([(x - 100 * s, yb - 270 * s), (x - 80 * s, yb - 340 * s), (x, yb - 370 * s), (x + 80 * s, yb - 340 * s),
              (x + 100 * s, yb - 270 * s), (x, yb - 255 * s)], frost)
    if cherry:
        p.circle(x, yb - 385 * s, 36 * s, cherry)
        p.curve([(x + 5 * s, yb - 418 * s), (x + 20 * s, yb - 460 * s), (x + 50 * s, yb - 475 * s)], max(6, 7 * s))


@scene("anpanman", 1, "香喷喷面包", "a freshly baked loaf of bread with scored top on a wooden board, with butter and a knife")
def anpanman_01(p):
    p.rect(150, 1050, 1000, 1300, BROWN, r=40)
    p.rect(990, 1120, 1130, 1230, BROWN, r=40)
    p.circle(1080, 1175, 28, 0)
    loaf = p.smooth([(220, 1100), (230, 880), (400, 760), (600, 730), (800, 760), (940, 880), (950, 1100), (600, 1130)], TAN)
    for k in range(3):
        x = 400 + k * 180
        p.ellipse(x, 900, 85, 32, LYELLOW, rot=-35, clip=loaf)
    p.ellipse(330, 1430, 190, 55, LSKY)
    p.rect(250, 1360, 410, 1430, YELLOW, r=14)
    p.poly([(600, 1450), (900, 1390), (905, 1430), (610, 1480)], LGRAY)
    p.rect(890, 1370, 1060, 1440, RED, r=20)
    for x in (480, 620, 760):
        p.curve([(x, 690), (x - 30, 620), (x + 20, 560), (x - 10, 490)], 8)
    sparkle(p, 160, 560, 60, YELLOW)
    sparkle(p, 1050, 620, 55, YELLOW)


@scene("anpanman", 2, "吐司片", "a slice of toast with a pat of butter and a jam heart on a plate, next to a jam jar and a strawberry")
def anpanman_02(p):
    p.ellipse(500, 1240, 400, 90, LSKY)
    crust = [(200, 1220), (200, 700), (150, 620), (180, 480), (330, 420), (500, 450), (670, 420), (820, 480),
             (850, 620), (800, 700), (800, 1220)]
    p.poly(catmull(crust, True, 8), BROWN)
    inner = [(250, 1170), (250, 690), (210, 620), (230, 520), (340, 475), (500, 505), (660, 475), (770, 520),
             (790, 620), (750, 690), (750, 1170)]
    p.poly(catmull(inner, True, 8), LYELLOW)
    p.poly(rotate([(380, 700), (520, 700), (520, 820), (380, 820)], 12, 450, 760), YELLOW)
    p.heart(560, 970, 120, RED)
    p.rect(870, 1050, 1110, 1380, PINK, r=30)
    p.rect(850, 980, 1130, 1060, ORANGE, r=16)
    p.rect(900, 1150, 1080, 1280, WHITE, r=16)
    p.smooth([(320, 1370), (400, 1350), (430, 1430), (370, 1510), (310, 1440)], RED)
    p.poly([(330, 1365), (360, 1320), (375, 1355), (410, 1330), (395, 1375)], GREEN)
    sparkle(p, 1000, 500, 60, YELLOW)
    sparkle(p, 1000, 800, 45, YELLOW)


@scene("anpanman", 3, "牛角面包", "a big flaky croissant on a plate next to a cup of warm milk")
def anpanman_03(p):
    p.ellipse(500, 1080, 400, 110, LSKY)
    croissant(p, 500, 1010, 1.9)
    p.arc(980, 1270, 60, 70, 270, 450, 18)
    p.ellipse(880, 1430, 200, 45, PINK)
    p.poly([(750, 1170), (1010, 1170), (980, 1410), (780, 1410)], WHITE)
    p.heart(880, 1290, 70, PINK)
    for x in (830, 930):
        p.curve([(x, 1130), (x - 25, 1070), (x + 15, 1010), (x - 10, 950)], 8)
    sparkle(p, 170, 600, 60, YELLOW)
    sparkle(p, 1020, 560, 55, YELLOW)
    sparkle(p, 200, 1450, 45, ORANGE)


@scene("anpanman", 4, "纸杯蛋糕", "a big cupcake with swirly frosting, a cherry and sprinkles in a pleated paper wrapper")
def anpanman_04(p):
    p.ellipse(600, 1370, 330, 60, LSKY)
    cupcake(p, 600, 1360, 2.0, SKY, PINK, RED)
    for (x, y, a) in ((480, 890, 30), (580, 860, -40), (700, 880, 60), (630, 790, 10), (520, 770, -60), (690, 760, 45)):
        p.line(rotate([(x - 18, y), (x + 18, y)], a, x, y), 10)
    for (x, y, r) in ((180, 500, 60), (1020, 560, 55), (170, 1150, 50)):
        p.star(x, y, r, YELLOW, rounded=True)


@scene("anpanman", 5, "飞碟", "a friendly flying saucer with lights and a glowing beam among stars and a ringed planet")
def anpanman_05(p):
    p.poly([(470, 900), (730, 900), (900, 1450), (300, 1450)], LYELLOW)
    p.chord(600, 820, 200, 210, 180, 360, LSKY)
    p.ellipse(600, 850, 380, 110, PURPLE)
    p.ellipse(600, 820, 250, 50, LPURPLE)
    for k in range(5):
        x = 360 + k * 120
        y = 880 + 12 * (1 - abs(k - 2) / 2)
        p.circle(x, y, 30, YELLOW)
    p.circle(950, 300, 85, ORANGE)
    p.ellipse(950, 300, 150, 32, PINK, rot=-15)
    p.chord(950, 300, 85, 85, 180, 360, ORANGE, clip=None)
    for (x, y, r) in ((180, 250, 50), (300, 520, 40), (1050, 760, 45), (150, 1150, 45)):
        p.star(x, y, r, YELLOW, rounded=True)


@scene("anpanman", 6, "面包工厂", "a cheerful bakery shop with a striped awning, bread in the display window, a chimney puffing smoke, flower pots and trees")
def anpanman_06(p):
    p.bg(LSKY)
    sun(p, 1060, 160, 70, YELLOW, rays=10, ink_rays=True, ray_len=36)
    cloud(p, 230, 170, 0.8)
    cloud(p, 760, 230, 0.7, LGRAY)
    cloud(p, 680, 330, 0.5, LGRAY)
    p.rect(600, 380, 690, 560, BROWN)
    p.band([(-10, 1300), (600, 1290), (1210, 1300)], 1620, LGRAY)
    for x in (55, 1145):
        p.rect(x - 22, 1000, x + 22, 1300, BROWN)
        p.circle(x, 940, 95, GREEN)
    p.rect(200, 620, 1000, 1300, PEACH)
    p.poly([(170, 640), (600, 430), (1030, 640)], RED)
    p.rect(400, 640, 800, 740, YELLOW, r=24)
    p.smooth([(540, 715), (545, 680), (600, 660), (655, 680), (660, 715)], TAN, 6)
    cols = [RED, WHITE] * 4
    pts_top = 790
    for k in range(8):
        x0 = 200 + k * 100
        p.poly([(x0, pts_top)] + [(x0 + 100, pts_top)] + ellipse_pts(x0 + 50, 860, 50, 40, 0, 0, 180, 12), cols[k])
    p.rect(260, 930, 620, 1180, LSKY)
    p.rect(250, 1180, 630, 1210, BROWN)
    baguette(p, 370, 1120, 190, -20, TAN, LYELLOW, 2)
    croissant(p, 520, 1130, 0.55)
    p.circle(440, 1010, 45, TAN)
    p.circle(560, 1010, 45, TAN)
    p.rect(700, 940, 920, 1300, BROWN, r=16)
    p.rect(740, 980, 880, 1080, LSKY, r=12)
    p.dot(890, 1150, 9)
    for x, c in ((150, PINK), (1050, PURPLE)):
        p.poly([(x - 50, 1230), (x + 50, 1230), (x + 38, 1310), (x - 38, 1310)], ORANGE)
        flower(p, x, 1150, 0.9, c, YELLOW, stem_len=80, leaf=None)


@scene("anpanman", 7, "彩虹下的小镇", "a little town of houses under a big rainbow with clouds, sun, trees and birds")
def anpanman_07(p):
    p.bg(LSKY)
    for k, c in enumerate((RED, ORANGE, YELLOW, GREEN, SKY, PURPLE)):
        p.pie(600, 1000, 600 - k * 52, 600 - k * 52, 180, 360, c)
    p.pie(600, 1000, 288, 288, 180, 360, 0)
    sun(p, 1080, 150, 65, YELLOW, rays=8, ink_rays=True, ray_len=30)
    cloud(p, 130, 900, 1.1)
    cloud(p, 1070, 900, 1.1)
    bird(p, 300, 200)
    bird(p, 400, 260, 0.8)
    p.band([(-10, 1060), (300, 1020), (600, 1050), (900, 1010), (1210, 1050)], 1620, LGREEN)
    house(p, 260, 1300, 220, 180, PEACH, RED, BROWN, LSKY)
    house(p, 600, 1260, 200, 170, LYELLOW, BLUE, RED, LSKY, chimney=False)
    house(p, 940, 1310, 220, 180, LPINK, PURPLE, BROWN, LSKY)
    round_tree(p, 430, 1580, 0.6, GREEN, BROWN, None)
    round_tree(p, 780, 1585, 0.6, FOREST, BROWN, None)


@scene("anpanman", 8, "野餐篮", "a woven picnic basket full of bread: a baguette, round rolls and a milk bottle, on a checkered blanket with apples")
def anpanman_08(p):
    p.bg(LGREEN)
    q = [(60, 1000), (1140, 1000), (1200, 1600), (0, 1600)]
    p.poly(q, BLUE)
    for i in range(5):
        for j in range(3):
            if (i + j) % 2 == 0:
                p.poly([quad_pt(q, i / 5, j / 3), quad_pt(q, (i + 1) / 5, j / 3),
                        quad_pt(q, (i + 1) / 5, (j + 1) / 3), quad_pt(q, i / 5, (j + 1) / 3)], WHITE)
    stroke(p, catmull([(260, 760), (330, 420), (600, 330), (870, 420), (940, 760)], False, 10), 50, BROWN)
    baguette(p, 520, 620, 380, -55, TAN, LYELLOW, 3)
    p.rect(700, 480, 820, 760, WHITE, r=30)
    p.rect(715, 430, 805, 490, RED, r=10)
    p.circle(380, 720, 80, TAN)
    p.circle(600, 740, 70, LORANGE)
    b = p.poly([(200, 760), (1000, 760), (920, 1200), (280, 1200)], TAN)
    for y in (870, 980, 1090):
        p.line([(150, y), (1050, y)], 7, clip=b)
    for k in range(1, 6):
        x0 = 200 + 800 * k / 6
        x1 = 280 + 640 * k / 6
        p.line([(x0, 760), (x1, 1200)], 7, clip=b)
    p.rect(180, 740, 1020, 790, BROWN, r=20)
    for (x, y, c) in ((150, 1350, RED), (1060, 1320, GREEN), (960, 1460, RED)):
        p.circle(x, y, 60, c)
        p.ellipse(x + 28, y - 70, 26, 12, GREEN if c == RED else FOREST, rot=-30)


@scene("anpanman", 9, "面包店橱窗", "a bakery display case with shelves of breads: baguettes, croissants, donuts, cupcakes, a pretzel and rolls")
def anpanman_09(p):
    p.bg(LYELLOW)
    p.rect(60, 120, 1140, 1520, BROWN, r=30)
    back = p.rect(110, 170, 1090, 1470, LORANGE)
    shelves = (560, 980, 1400)
    for y in shelves:
        p.rect(110, y, 1090, y + 40, TAN)
    # top shelf: baguettes + croissant
    baguette(p, 300, 470, 340, -12, TAN, LYELLOW, 3)
    baguette(p, 330, 330, 300, -12, TAN, LYELLOW, 3)
    croissant(p, 820, 440, 1.2)
    # middle shelf: donuts
    for x, c in ((260, PINK), (520, PURPLE), (780, SKY)):
        donut(p, x, 870, 105, c, TAN, back)
    p.circle(990, 900, 75, TAN)
    # bottom shelf: cupcakes, pretzel
    cupcake(p, 260, 1395, 0.85, SKY, PINK, RED, pleats=2)
    cupcake(p, 520, 1395, 0.85, GREEN, LYELLOW, RED, pleats=2)
    pretzel = catmull([(800, 1380), (720, 1250), (760, 1150), (850, 1170), (880, 1260), (920, 1340), (960, 1380),
                       (1000, 1340), (1030, 1260), (1060, 1170), (1000, 1150), (920, 1180),
                       (880, 1260), (840, 1340), (760, 1380)], False, 10)
    stroke(p, [(x - 20, y) for x, y in pretzel], 46, BROWN)
    p.rect(470, 40, 730, 130, RED, r=26)


@scene("anpanman", 10, "小飞机巡逻", "a little propeller plane flying over a small town, pulling a banner, with clouds and the sun")
def anpanman_10(p):
    p.bg(LSKY)
    sun(p, 1080, 150, 65, YELLOW, rays=10, ink_rays=True, ray_len=32)
    cloud(p, 180, 200, 0.9)
    cloud(p, 900, 560, 0.75)
    cloud(p, 330, 760, 0.6)
    p.line([(250, 420), (420, 420)], 6)
    p.line([(250, 540), (420, 450)], 6)
    p.poly([(40, 390), (260, 405), (260, 555), (40, 540)], YELLOW)
    p.poly([(430, 400), (450, 250), (530, 260), (560, 400)], BLUE)
    p.smooth([(420, 420), (520, 360), (760, 340), (900, 370), (950, 430), (900, 480), (700, 500), (480, 480)], RED)
    p.ellipse(470, 455, 70, 22, BLUE, rot=-8)
    p.smooth([(790, 360), (860, 365), (905, 410), (820, 415)], LSKY, 6)
    for x in (560, 640, 720):
        p.circle(x, 420, 22, WHITE)
    p.poly([(620, 440), (780, 450), (700, 620), (590, 610)], BLUE)
    p.ellipse(975, 430, 18, 105, LGRAY)
    p.circle(960, 430, 24, YELLOW)
    p.band([(-10, 1100), (300, 1060), (600, 1090), (900, 1050), (1210, 1090)], 1620, LGREEN)
    house(p, 230, 1330, 220, 170, PEACH, RED, BROWN, LSKY)
    house(p, 620, 1300, 200, 160, LYELLOW, BLUE, RED, LSKY, chimney=False)
    house(p, 980, 1340, 220, 170, LPINK, PURPLE, BROWN, LSKY)
    p.band([(-10, 1470), (400, 1450), (800, 1470), (1210, 1450)], 1620, LGRAY)


# ===========================================================================
# --mode gemini : two-step generation (line art -> local clean -> colourise)
# ===========================================================================
LINE_PROMPT = (
    "Children's coloring book page, portrait 3:4. {desc}. Pure black and white line art only: "
    "{weight} black outlines on a pure white background, every shape fully closed, no shading, "
    "no grey, no hatching, no text, no signature. {complexity} Original artwork; do not depict "
    "any existing cartoon or film character."
)
COLOR_PROMPT = (
    "Color this exact coloring page like a neat child's finished artwork. Keep every black line "
    "exactly where it is (do not move, add or remove lines); fill each closed area with ONE flat, "
    "bright, saturated crayon color (no gradients, no shading, no texture). Background stays white "
    "unless it is sky/ground. Output the same composition and size."
)


def _gemini_call(base_url, model, api_key, parts, timeout=180):
    url = f"{base_url}/v1beta/models/{model}:generateContent"
    body = {"contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"responseModalities": ["IMAGE", "TEXT"]}}
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "x-goog-api-key": api_key})
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = json.loads(resp.read())
            for cand in data.get("candidates", []):
                for p in cand.get("content", {}).get("parts", []):
                    blob = p.get("inline_data") or p.get("inlineData")
                    if blob and blob.get("data"):
                        return Image.open(io.BytesIO(base64.b64decode(blob["data"])))
            raise RuntimeError("no image in response")
        except (urllib.error.URLError, RuntimeError, TimeoutError) as ex:
            last = ex
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"gemini call failed: {last}")


def _fit_canvas(img: Image.Image, fill):
    """Letterbox-fit into W x H."""
    img = img.convert("RGB")
    s = min(W / img.width, H / img.height)
    img = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), fill)
    canvas.paste(img, ((W - img.width) // 2, (H - img.height) // 2))
    return canvas


def clean_line_art(img: Image.Image, lw: float):
    g = np.asarray(_fit_canvas(img, (255, 255, 255)).convert("L"), dtype=np.float32)
    # adaptive-ish threshold: global Otsu bounded to sane range
    hist = np.bincount(g.astype(np.uint8).ravel(), minlength=256).astype(np.float64)
    p = hist / hist.sum()
    omega = np.cumsum(p)
    mu = np.cumsum(p * np.arange(256))
    with np.errstate(divide="ignore", invalid="ignore"):
        sb = (mu[-1] * omega - mu) ** 2 / (omega * (1 - omega))
    thr = float(np.clip(np.nanargmax(sb), 90, 190))
    line = g < thr
    # denoise: drop small black specks, then thicken a little and close gaps
    lab, n = ndi.label(line)
    if n:
        sz = np.bincount(lab.ravel())
        keep = sz >= 40
        keep[0] = False
        line = keep[lab]
    line = ndi.binary_closing(np.pad(line, 3, mode="edge"), structure=np.ones((5, 5), bool), iterations=1)[3:-3, 3:-3]
    if lw >= 9:
        line = ndi.binary_dilation(line, structure=_CROSS)
    lab, n = ndi.label(~line, structure=_CROSS)
    if n:
        sz = np.bincount(lab.ravel())
        speck = sz < 160
        speck[0] = False
        line |= speck[lab]
    return line


def flatten_colors(line, colored: Image.Image):
    """Force one flat colour per closed region (mode colour) + overlay black lines."""
    col = np.asarray(_fit_canvas(colored, (255, 255, 255)), dtype=np.uint8)
    q = (col.astype(np.int64) >> 3)
    key = (q[..., 0] << 10) | (q[..., 1] << 5) | q[..., 2]
    lab, n = label_regions(line)
    ref = np.full((H, W, 3), 255, np.uint8)
    m = lab > 0
    keys = lab[m].astype(np.int64) * (1 << 15) + key[m]
    uk, cnt = np.unique(keys, return_counts=True)
    labs = uk >> 15
    order = np.lexsort((-cnt, labs))
    first = np.ones(len(order), bool)
    first[1:] = labs[order][1:] != labs[order][:-1]
    lut = np.full((n + 1, 3), 255, np.uint8)
    for idx in order[first]:
        v = int(uk[idx] & 0x7FFF)
        lut[int(labs[idx])] = [((v >> 10) & 31) * 8 + 4, ((v >> 5) & 31) * 8 + 4, (v & 31) * 8 + 4]
    ref = lut[lab]
    ref[line] = 0
    return ref


def render_gemini(sd: SceneDef, api_key, model, base_url):
    easy = sd.difficulty == "easy"
    weight = "very thick (about 10 px)" if easy else "clear thick (about 7 px)"
    complexity = ("Very simple: 10 to 18 large closed areas, one big subject, plain white background."
                  if easy else "A complete simple scene with 20 to 40 closed areas, a few cute props and "
                  "a simple background.")
    prompt = LINE_PROMPT.format(desc=sd.desc, weight=weight, complexity=complexity)
    raw = _gemini_call(base_url, model, api_key, [{"text": prompt}])
    line = clean_line_art(raw, 10 if easy else 8)
    lab, n = label_regions(line)
    lo, hi = REGION_RANGE[sd.difficulty]
    if not lo <= n <= hi:
        raise RuntimeError(f"line art has {n} regions (want {lo}-{hi})")
    buf = io.BytesIO()
    Image.fromarray(np.where(line, 0, 255).astype(np.uint8)).save(buf, "PNG")
    colored = _gemini_call(base_url, model, api_key, [
        {"text": COLOR_PROMPT},
        {"inline_data": {"mime_type": "image/png", "data": base64.b64encode(buf.getvalue()).decode()}},
    ])
    ref = flatten_colors(line, colored)
    return line, ref


# ===========================================================================
# Pipeline: render, write, QC
# ===========================================================================
def render_offline(sd: SceneDef):
    pg = new_page(sd.difficulty)
    sd.fn(pg)
    return pg.render()


def save_page(sd: SceneDef, line, ref, out_dir: Path):
    cat_dir = out_dir / sd.category
    cat_dir.mkdir(parents=True, exist_ok=True)
    line_img = Image.fromarray(np.where(line, 0, 255).astype(np.uint8), "L").convert("1", dither=Image.Dither.NONE)
    line_img.save(cat_dir / f"{sd.id}_line.png", optimize=True)
    Image.fromarray(ref, "RGB").save(cat_dir / f"{sd.id}_ref.webp", "WEBP", lossless=True, quality=100, method=6)


def qc_page(sd: SceneDef, out_dir: Path):
    """Reload the written files and run every check.  Returns dict."""
    errs = []
    lp = out_dir / sd.category / f"{sd.id}_line.png"
    rp = out_dir / sd.category / f"{sd.id}_ref.webp"
    li = Image.open(lp)
    ri = Image.open(rp)
    if li.mode != "1":
        errs.append(f"line mode {li.mode}")
    la = np.array(li.convert("L"))
    if not np.isin(la, (0, 255)).all():
        errs.append("line not binary")
    if li.size != (W, H) or ri.size != (W, H):
        errs.append(f"size line={li.size} ref={ri.size}")
    line = la < 128
    ref = np.array(ri.convert("RGB"))
    n, tiny, worst, sizes = region_stats(line, ref)
    lo, hi = REGION_RANGE[sd.difficulty]
    if not lo <= n <= hi:
        errs.append(f"regions {n} not in {lo}-{hi}")
    if tiny > MAX_TINY:
        errs.append(f"{tiny} tiny regions")
    if worst < UNIFORM_MIN:
        errs.append(f"ref misaligned (worst region {worst:.3f})")
    return {
        "id": sd.id, "regions": int(n), "tiny": tiny, "uniform": worst,
        "palette": dominant_palette(ref), "errors": errs,
        "bytes": lp.stat().st_size + rp.stat().st_size,
        "min_region": int(sizes.min()) if len(sizes) else 0,
    }


def _job(args):
    sid, out_dir, mode, gopts = args
    sd = SCENES[sid]
    src = "offline"
    line = ref = None
    if mode == "gemini":
        try:
            line, ref = render_gemini(sd, **gopts)
            src = "gemini"
            save_page(sd, line, ref, out_dir)
            r = qc_page(sd, out_dir)
            if r["errors"]:
                print(f"[gemini] {sid}: QC failed {r['errors']} -> offline fallback", flush=True)
                line = None
        except Exception as ex:  # noqa: BLE001
            print(f"[gemini] {sid}: {ex} -> offline fallback", flush=True)
            line = None
        if line is None:
            src = "offline"
    if line is None:
        line, ref = render_offline(sd)
        save_page(sd, line, ref, out_dir)
    r = qc_page(sd, out_dir)
    r["source"] = src
    return r


def contact_sheet(ids, out_dir: Path, path: Path, cols=11, tw=200):
    th = int(tw * H / W)
    pad, lab = 8, 22
    rows = math.ceil(len(ids) / cols)
    sheet = Image.new("RGB", (cols * (tw + pad) + pad, rows * (2 * th + lab + 2 * pad) + pad), (235, 235, 235))
    dr = ImageDraw.Draw(sheet)
    try:
        f = ImageFont.truetype(FONT_PATH, 16)
    except OSError:
        f = ImageFont.load_default()
    for k, sid in enumerate(ids):
        sd = SCENES[sid]
        cx = pad + (k % cols) * (tw + pad)
        cy = pad + (k // cols) * (2 * th + lab + 2 * pad)
        li = Image.open(out_dir / sd.category / f"{sid}_line.png").convert("RGB").resize((tw, th), Image.LANCZOS)
        ri = Image.open(out_dir / sd.category / f"{sid}_ref.webp").convert("RGB").resize((tw, th), Image.LANCZOS)
        dr.text((cx, cy), sid, fill=(0, 0, 0), font=f)
        sheet.paste(li, (cx, cy + lab))
        sheet.paste(ri, (cx, cy + lab + th + 2))
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path)


def select_ids(only: str | None):
    ids = sorted(SCENES, key=lambda s: ([c["id"] for c in CATEGORIES].index(SCENES[s].category), SCENES[s].index))
    if not only:
        return ids
    keys = [k.strip() for k in only.split(",") if k.strip()]
    return [i for i in ids if any(i == k or SCENES[i].category == k for k in keys)]


def main(argv=None):
    global FONT_PATH
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=["offline", "gemini"], default="offline")
    ap.add_argument("--font", default=DEFAULT_FONT, help="TTF used for alphabet bubble letters")
    ap.add_argument("--model", default="gemini-nano-banana-2.1")
    ap.add_argument("--base-url", default="https://generativelanguage.googleapis.com")
    ap.add_argument("--only", help="comma separated page ids or categories (writes to --preview dir)")
    ap.add_argument("--preview", help="output dir for partial/preview runs (manifest is not written)")
    ap.add_argument("--out", default=str(OUT_DIR))
    ap.add_argument("--contact-sheet", help="write a contact sheet PNG here")
    ap.add_argument("--jobs", type=int, default=min(8, os.cpu_count() or 2))
    a = ap.parse_args(argv)
    FONT_PATH = a.font
    os.environ["XN_FONT"] = a.font

    ids = select_ids(a.only)
    partial = bool(a.only or a.preview)
    out_dir = Path(a.preview) if a.preview else Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    gopts = {}
    if a.mode == "gemini":
        key = os.environ.get("GEMINI_API_KEY")
        if not key:
            print("GEMINI_API_KEY not set", file=sys.stderr)
            return 2
        gopts = {"api_key": key, "model": a.model, "base_url": a.base_url.rstrip("/")}

    t0 = time.time()
    jobs = [(sid, out_dir, a.mode, gopts) for sid in ids]
    if a.jobs > 1 and len(jobs) > 1:
        with ProcessPoolExecutor(a.jobs) as ex:
            results = list(ex.map(_job, jobs))
    else:
        results = [_job(j) for j in jobs]

    # ---- QC table ---------------------------------------------------------
    fails = 0
    print(f"\n{'page':<14}{'diff':<10}{'regions':>8}{'tiny':>6}{'minpx':>7}{'uniform':>9}{'KB':>7}  src      status")
    for r in results:
        sd = SCENES[r["id"]]
        st = "OK" if not r["errors"] else "FAIL " + "; ".join(r["errors"])
        fails += bool(r["errors"])
        print(f"{r['id']:<14}{sd.difficulty:<10}{r['regions']:>8}{r['tiny']:>6}{r['min_region']:>7}{r['uniform']:>9.3f}"
              f"{r['bytes'] / 1024:>7.0f}  {r['source']:<8} {st}")
    total = sum(r["bytes"] for r in results)

    if not partial:
        expected = select_ids(None)
        missing = [i for i in expected if i not in {r["id"] for r in results}]
        if len(expected) != 66 or missing:
            print(f"FAIL: expected 66 pages, missing {missing}")
            fails += 1
        by_id = {r["id"]: r for r in results}
        manifest = {
            "version": 1,
            "canvas": {"width": W, "height": H},
            "categories": CATEGORIES,
            "pages": [{
                "id": sid, "category": SCENES[sid].category, "index": SCENES[sid].index,
                "title": SCENES[sid].title, "difficulty": SCENES[sid].difficulty,
                "line": f"{SCENES[sid].category}/{sid}_line.png",
                "ref": f"{SCENES[sid].category}/{sid}_ref.webp",
                "regions": by_id[sid]["regions"], "palette": by_id[sid]["palette"],
            } for sid in expected],
        }
        (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        total = sum(p.stat().st_size for p in out_dir.rglob("*") if p.is_file())
        if total >= 9 * 1024 * 1024:
            print(f"FAIL: builtin_packs is {total / 1048576:.2f} MB (>= 9 MB)")
            fails += 1
    if a.contact_sheet:
        contact_sheet(ids, out_dir, Path(a.contact_sheet))
    print(f"\n{len(results)} pages, {fails} failing, total {total / 1048576:.2f} MB, {time.time() - t0:.1f}s")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
