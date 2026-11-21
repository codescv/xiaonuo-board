#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["google-genai", "pillow", "numpy", "scipy"]
# ///
"""Generate character-referenced built-in coloring packs via Vertex AI Nano Banana 2.1.

Uses reference character images in ``~/Developer/band/images/snow/`` (Elsa, Anna, Kristoff,
Bluey, Bingo, Bandit, Chilli, Muffin, Anpanman, Baikinman, Shokupanman, Ramen Tenshi) plus
Peppa Pig scenes, generating paired 1200x1600 1-bit PNG line art and pixel-aligned flat-color
WebP references, then updates ``app/src/main/assets/builtin_packs/manifest.json``.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import io
import json
import os
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from google import genai
from google.genai import types
from PIL import Image, ImageOps
from scipy import ndimage as ndi

W, H = 1200, 1600
REPO = Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "app" / "src" / "main" / "assets" / "builtin_packs"
DEFAULT_REF_DIR = Path.home() / "Developer" / "band" / "images" / "snow"
DEFAULT_MODEL = "gemini-nano-banana-2.1"

SAFETY_CATEGORIES: tuple[str, ...] = (
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_CIVIC_INTEGRITY",
    "HARM_CATEGORY_IMAGE_HATE",
    "HARM_CATEGORY_IMAGE_DANGEROUS_CONTENT",
    "HARM_CATEGORY_IMAGE_HARASSMENT",
    "HARM_CATEGORY_IMAGE_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_JAILBREAK",
)

_THREAD_LOCAL = threading.local()


@dataclass(frozen=True)
class PackPage:
    id: str
    category: str
    index: int
    title: str
    difficulty: str  # "easy" | "standard"
    refs: tuple[str, ...]
    prompt: str


PAGES: list[PackPage] = [
    # ── 艾莎 (Elsa / Frozen) ──
    PackPage("elsa_01", "elsa", 1, "艾莎微笑半身像", "easy", ("elsa.png",),
             "Cute smiling portrait of the ice princess character shown in the reference image waving gently, wearing her iconic ice-blue gown and side braid, with 2 large simple snowflakes floating beside her. Plain white background."),
    PackPage("elsa_02", "elsa", 2, "艾莎挥舞魔法雪花", "easy", ("elsa.png",),
             "Cute 2D children's coloring drawing of the smiling girl with a blonde braid from the reference image raising both hands joyfully to conjure one big geometric snowflake and twinkling stars. Simple open composition."),
    PackPage("elsa_03", "elsa", 3, "安娜开心招手", "easy", ("anna.png",),
             "Cute 2D children's coloring drawing of the cheerful girl with twin braids from the reference image waving hello happily in her winter cloak on a snowy hill with 2 clouds."),
    PackPage("elsa_04", "elsa", 4, "艾莎冰雪长裙", "easy", ("elsa.png",),
             "Full-body drawing of the ice princess character shown in the reference image standing gracefully in her flowing dress and cape, surrounded by simple snow mounds and 3 stars."),
    PackPage("elsa_05", "elsa", 5, "克里斯托夫微笑", "easy", ("kristoff.png",),
             "The friendly winter mountain boy character shown in the reference image smiling warmly in his winter tunic and boots, holding a carrot, standing on snowy ground with simple pine trees behind."),
    PackPage("elsa_06", "elsa", 6, "安娜冬日小屋", "standard", ("anna.png",),
             "Cute simple 2D coloring drawing of the cheerful braided girl from the reference image standing happily outside a cozy wooden winter cottage with snowy pine trees and fluffy clouds."),
    PackPage("elsa_07", "elsa", 7, "安娜和克里斯托夫喝热可可", "standard", ("anna.png", "kristoff.png"),
             "Cute simple 2D coloring drawing of the two cheerful characters from the two reference images sitting at a cozy wooden table with steaming mugs of hot cocoa and cookies."),
    PackPage("elsa_08", "elsa", 8, "安娜和可爱雪人", "standard", ("anna.png",),
             "Cute simple 2D coloring drawing of the smiling braided girl shown in the reference image standing happily next to a cute little round snowman wearing a scarf and hat, with 2 snowflakes in the sky."),
    PackPage("elsa_09", "elsa", 9, "安娜与克里斯托夫雪地散步", "standard", ("anna.png", "kristoff.png"),
             "The two winter characters shown in the two reference images walking happily together along a snowy forest path with evergreen trees and falling snowflakes."),
    PackPage("elsa_10", "elsa", 10, "汉斯王子冬日微笑", "standard", ("hans.png",),
             "Cute simple 2D coloring drawing of the smiling young prince character from the reference image waving politely in his winter coat in a snowy garden with pine trees and twinkling stars."),

    # ── 布鲁伊 (Bluey) ──
    PackPage("bluey_01", "bluey", 1, "布鲁伊开心招手", "easy", ("bluey.png",),
             "The blue cartoon puppy character shown in the reference image standing happily and waving hello with a big joyful smile, simple grassy hill and fluffy cloud."),
    PackPage("bluey_02", "bluey", 2, "宾果抱小兔玩偶", "easy", ("bingo.png",),
             "The little orange cartoon puppy character shown in the reference image hugging a cute plush bunny toy with a sweet smile, simple hearts floating nearby."),
    PackPage("bluey_03", "bluey", 3, "布鲁伊玩红气球", "easy", ("bluey.png",),
             "The blue cartoon puppy character shown in the reference image jumping up joyfully to tap a big round balloon floating in the air, simple yard grass below."),
    PackPage("bluey_04", "bluey", 4, "玛芬戴皇冠开心笑", "easy", ("muffin.png",),
             "The white-and-gray cartoon puppy character shown in the reference image wearing a cute toy princess crown on her head and laughing playfully, surrounded by 3 simple stars."),
    PackPage("bluey_05", "bluey", 5, "布鲁伊吃冰淇淋", "easy", ("bluey.png",),
             "The blue cartoon puppy character shown in the reference image sitting happily holding a big two-scoop ice cream cone, smiling sun in the corner."),
    PackPage("bluey_06", "bluey", 6, "布鲁伊和宾果玩气球", "standard", ("bluey.png", "bingo.png"),
             "The two cartoon puppy sisters shown in the two reference images playing keepy-uppy together, bouncing a big balloon in their sunny backyard with a fence and clouds."),
    PackPage("bluey_07", "bluey", 7, "布鲁伊一家四口野餐", "standard", ("bluey.png", "bingo.png", "bandit.png", "chilli.png"),
             "The four cartoon puppy family members shown in the four reference images sitting together happily on a checkered picnic blanket with a picnic basket and fruit."),
    PackPage("bluey_08", "bluey", 8, "后院大树荡秋千", "standard", ("bluey.png", "bingo.png"),
             "The blue puppy from the first reference image sitting on a wooden tire swing hanging from a big leafy backyard tree while the smaller orange puppy from the second reference image cheers happily below."),
    PackPage("bluey_09", "bluey", 9, "爸爸班迪特骑马游戏", "standard", ("bandit.png", "bluey.png", "bingo.png"),
             "The dad dog from the first reference image playfully crawling on hands and knees while the two little puppies from the second and third reference images giggle riding on his back in a cozy living room with a rug."),
    PackPage("bluey_10", "bluey", 10, "妈妈奇莉海滩堆沙堡", "standard", ("chilli.png", "bluey.png", "bingo.png"),
             "The mum dog and two little puppies shown in the three reference images building a big sandcastle with a bucket and shovel on a sunny beach with gentle ocean waves."),

    # ── 小猪佩奇 (reference-driven; zero IP names in prompts) ──
    PackPage("peppa_01", "peppa", 1, "佩奇穿红裙微笑", "easy", ("peppa.png",),
             "The cheerful cartoon pink pig character in a red dress shown in the reference image smiling and waving hello with one big smiling sun in the top corner. Plain white background."),
    PackPage("peppa_02", "peppa", 2, "乔治抱恐龙先生", "easy", ("george.png",),
             "The small cartoon pink pig character in a blue shirt shown in the reference image happily hugging a cute simple green toy dinosaur. Plain white background."),
    PackPage("peppa_03", "peppa", 3, "佩奇开心跳泥坑", "easy", ("peppa.png",),
             "The cartoon pink pig character in a red dress shown in the reference image wearing simple rain boots and jumping joyfully in one big oval puddle with a simple 3-band rainbow arch above. Plain white background."),
    PackPage("peppa_04", "peppa", 4, "佩奇骑滑板车", "easy", ("peppa.png",),
             "The cartoon pink pig character in a red dress shown in the reference image happily riding a simple chunky scooter with 2 big round wheels. Plain white background."),
    PackPage("peppa_05", "peppa", 5, "佩奇的生日蛋糕", "easy", ("peppa.png",),
             "The cartoon pink pig character in a red dress shown in the reference image wearing a pointy party hat next to one big birthday cake with 3 thick candles and 1 balloon. Plain white background."),
    PackPage("peppa_06", "peppa", 6, "佩奇一家开红汽车", "standard", ("peppa.png", "george.png", "mummy_pig.png", "daddy_pig.png"),
             "The four cartoon pink pig family characters shown in the four reference images riding happily together inside one simple chunky convertible car with big round wheels. Plain white background."),
    PackPage("peppa_07", "peppa", 7, "佩奇花园茶话会", "standard", ("peppa.png", "suzy_sheep.png"),
             "The cartoon pink pig character and the cartoon white sheep character shown in the two reference images sitting happily at a simple round table with one teapot and two teacups. Plain white background."),
    PackPage("peppa_08", "peppa", 8, "佩奇和乔治坐小火车", "standard", ("peppa.png", "george.png"),
             "The two cartoon pink pig characters shown in the two reference images waving happily from a simple chunky toy steam train engine with big round wheels and one puff of cloud. Plain white background."),
    PackPage("peppa_09", "peppa", 9, "佩奇和乔治坐热气球", "standard", ("peppa.png", "george.png"),
             "The two cartoon pink pig characters shown in the two reference images waving happily from the basket of one big striped hot-air balloon floating beside 2 fluffy clouds. Plain white background."),
    PackPage("peppa_10", "peppa", 10, "佩奇和乔治海边堆沙堡", "standard", ("peppa.png", "george.png"),
             "The two cartoon pink pig characters shown in the two reference images smiling beside one big simple sandcastle and a striped beach ball. Plain white background."),

    # ── 面包超人 (Anpanman) ──
    PackPage("anpanman_01", "anpanman", 1, "面包超人飞行敬礼", "easy", ("anpanman.png",),
             "The round-faced smiling superhero character shown in the reference image flying cheerfully through the sky with his cape flowing behind him and one fist raised heroically, fluffy clouds nearby."),
    PackPage("anpanman_02", "anpanman", 2, "细菌人坐飞碟", "easy", ("baikinman.png",),
             "The mischievous black-and-purple cartoon character shown in the reference image grinning playfully while piloting his round UFO flying saucer in the sky with stars around."),
    PackPage("anpanman_03", "anpanman", 3, "吐司超人微笑招手", "easy", ("shokupanman.png",),
             "The friendly square-headed superhero character shown in the reference image standing kindly and waving hello with his gentle smile and cape, simple stars and clouds."),
    PackPage("anpanman_04", "anpanman", 4, "拉面天使端拉面碗", "easy", ("ramen_tenshi.png",),
             "The cute cheerful cartoon character shown in the reference image smiling brightly while floating happily next to a big steaming bowl of yummy ramen noodles."),
    PackPage("anpanman_05", "anpanman", 5, "面包超人开心大笑", "easy", ("anpanman.png",),
             "Big friendly close-up portrait of the round-faced smiling superhero character shown in the reference image smiling warmly and giving a thumbs up, surrounded by simple twinkling stars."),
    PackPage("anpanman_06", "anpanman", 6, "面包超人与面包工厂", "standard", ("anpanman.png",),
             "The round-faced smiling superhero character shown in the reference image waving happily outside a cozy storybook bakery cottage with a round chimney, flower garden, and sunny clouds."),
    PackPage("anpanman_07", "anpanman", 7, "面包超人和吐司超人巡逻", "standard", ("anpanman.png", "shokupanman.png"),
             "The two friendly superhero characters shown in the two reference images flying side by side happily over green hills, a little village, and fluffy clouds."),
    PackPage("anpanman_08", "anpanman", 8, "面包超人与细菌人空中追逐", "standard", ("anpanman.png", "baikinman.png"),
             "The superhero character from the first reference image flying playfully toward the mischievous character from the second reference image in his round UFO saucer above fluffy clouds, lively kid-friendly cartoon action scene."),
    PackPage("anpanman_09", "anpanman", 9, "大家一起野餐吃拉面", "standard", ("anpanman.png", "shokupanman.png", "ramen_tenshi.png"),
             "The three cheerful cartoon characters shown in the three reference images enjoying a happy outdoor picnic on a grassy meadow with a basket and bowls of ramen."),
    PackPage("anpanman_10", "anpanman", 10, "彩虹天空大冒险", "standard", ("anpanman.png", "shokupanman.png", "baikinman.png"),
             "The first two superhero characters flying happily under a big rainbow arch while the third mischievous character watches playfully from his UFO among fluffy clouds."),
]


def _get_client(project: str, location: str) -> genai.Client:
    c = getattr(_THREAD_LOCAL, "client", None)
    if c is None:
        c = genai.Client(vertexai=True, project=project, location=location)
        _THREAD_LOCAL.client = c
    return c


def _safety() -> list[types.SafetySetting]:
    return [types.SafetySetting(category=cat, threshold="OFF") for cat in SAFETY_CATEGORIES]


def _extract_image(resp: object) -> Image.Image:
    parts = getattr(resp, "parts", None)
    if not parts:
        cands = getattr(resp, "candidates", None) or []
        if cands:
            parts = getattr(getattr(cands[0], "content", None), "parts", None)
    for p in parts or []:
        inline = getattr(p, "inline_data", None)
        data = getattr(inline, "data", None) if inline is not None else None
        if isinstance(data, (bytes, bytearray)) and data:
            with Image.open(io.BytesIO(bytes(data))) as im:
                rgb = im.convert("RGB")
                rgb.load()
                return rgb
    raise RuntimeError("No image returned in response")


def _call_vertex(client: genai.Client, model: str, contents: list, retries: int = 3) -> Image.Image:
    last_err = None
    for attempt in range(retries):
        try:
            resp = client.models.generate_content(
                model=model,
                contents=contents,
                config=types.GenerateContentConfig(
                    temperature=1,
                    top_p=0.95,
                    safety_settings=_safety(),
                    image_config=types.ImageConfig(
                        aspect_ratio="3:4",
                        person_generation="ALLOW_ALL",
                        prominent_people="ALLOW_PROMINENT_PEOPLE",
                    ),
                ),
            )
            try:
                return _extract_image(resp)
            except RuntimeError:
                cands = getattr(resp, "candidates", None) or []
                reason = getattr(cands[0], "finish_reason", None) if cands else None
                raise RuntimeError(f"No image returned (finish_reason={reason})")
        except Exception as e:  # noqa: BLE001
            last_err = e
            if attempt + 1 < retries:
                time.sleep(2.0 * (attempt + 1))
    raise RuntimeError(f"Vertex call failed after {retries} attempts: {last_err}")


def clean_line_art(raw: Image.Image, easy: bool) -> np.ndarray:
    """Convert raw coloring page into crisp 1200x1600 boolean mask (True = black ink line)."""
    im = ImageOps.contain(raw.convert("L"), (W, H), Image.Resampling.LANCZOS)
    canvas = Image.new("L", (W, H), 255)
    canvas.paste(im, ((W - im.width) // 2, (H - im.height) // 2))
    arr = np.array(canvas, dtype=np.uint8)
    # Adaptive threshold: dark ink pixels become True.
    mask = arr < 175
    # Remove tiny isolated dust specks (< 45 px).
    lbl, n = ndi.label(mask)
    if n > 0:
        sizes = np.bincount(lbl.ravel())
        keep = sizes >= 45
        keep[0] = False
        mask = keep[lbl]
    # Close 2px micro-gaps so flood-fill doesn't leak.
    struct = ndi.generate_binary_structure(2, 2)
    mask = ndi.binary_closing(mask, structure=struct, iterations=2)
    # Thicken lines slightly so every stroke is bold and easy to color on a 7.8-inch tablet.
    mask = ndi.binary_dilation(mask, structure=struct, iterations=1)
    # Absorb tiny unpaintable white slivers (< 180 px) into black ink so kids never hunt microscopic gaps.
    wlab, wn = ndi.label(~mask)
    if wn > 0:
        wsizes = np.bincount(wlab.ravel())
        tiny_white = (wsizes < 180)
        tiny_white[0] = False
        mask = mask | tiny_white[wlab]
    # Clear 2px outer border so background connects cleanly.
    mask[:2, :] = False
    mask[-2:, :] = False
    mask[:, :2] = False
    mask[:, -2:] = False
    return mask


def flatten_reference(line: np.ndarray, colored_raw: Image.Image) -> tuple[np.ndarray, int]:
    """Flatten each connected white region to its median color from colored_raw, then stamp crisp 1-bit lines."""
    im = colored_raw.convert("RGB").resize((W, H), Image.Resampling.LANCZOS)
    col = np.array(im, dtype=np.uint8)
    lab, n = ndi.label(~line)
    out = np.full((H, W, 3), 255, dtype=np.uint8)
    if n > 0:
        # Compute per-region median RGB cleanly for pixel-exact alignment.
        flat_lab = lab.ravel()
        order = np.argsort(flat_lab, kind="mergesort")
        sorted_lab = flat_lab[order]
        starts = np.searchsorted(sorted_lab, np.arange(1, n + 1), side="left")
        ends = np.searchsorted(sorted_lab, np.arange(1, n + 1), side="right")
        r_flat = col[:, :, 0].ravel()
        g_flat = col[:, :, 1].ravel()
        b_flat = col[:, :, 2].ravel()
        palette_r = np.full(n + 1, 255, dtype=np.uint8)
        palette_g = np.full(n + 1, 255, dtype=np.uint8)
        palette_b = np.full(n + 1, 255, dtype=np.uint8)
        for rid in range(1, n + 1):
            s, e = starts[rid - 1], ends[rid - 1]
            if e <= s:
                continue
            idx = order[s:e]
            # Sample up to 2048 pixels for fast median.
            if len(idx) > 2048:
                idx = idx[:: max(1, len(idx) // 2048)]
            palette_r[rid] = int(np.median(r_flat[idx]))
            palette_g[rid] = int(np.median(g_flat[idx]))
            palette_b[rid] = int(np.median(b_flat[idx]))
        out[:, :, 0] = palette_r[lab]
        out[:, :, 1] = palette_g[lab]
        out[:, :, 2] = palette_b[lab]
    out[line] = 0
    return out, int(n)


def extract_palette(ref: np.ndarray, k: int = 6) -> list[str]:
    """Extract up to 6 vibrant non-white, non-black colors for the recommended palette strip."""
    small = ref[::6, ::6].reshape(-1, 3)
    lum = (small[:, 0].astype(np.int32) * 299 + small[:, 1].astype(np.int32) * 587 + small[:, 2].astype(np.int32) * 114) // 1000
    sat = small.max(axis=1).astype(np.int32) - small.min(axis=1).astype(np.int32)
    mask = (lum > 40) & (lum < 245) & (sat > 25)
    pts = small[mask]
    if len(pts) == 0:
        return ["#E61919", "#0077B6", "#FFD600", "#38B000", "#FF6F00", "#9D4EDD"]
    # Quantize to 24-step buckets and pick most frequent distinct colors.
    q = (pts // 24) * 24 + 12
    uniq, counts = np.unique(q, axis=0, return_counts=True)
    order = np.argsort(-counts)
    chosen: list[np.ndarray] = []
    for idx in order:
        cand = uniq[idx]
        if all(np.linalg.norm(cand.astype(float) - c.astype(float)) >= 48.0 for c in chosen):
            chosen.append(cand)
            if len(chosen) >= k:
                break
    defaults = [(230, 25, 25), (0, 119, 182), (255, 214, 0), (56, 176, 0), (255, 111, 0), (157, 78, 221)]
    while len(chosen) < k:
        chosen.append(np.array(defaults[len(chosen) % len(defaults)]))
    return [f"#{int(c[0]):02X}{int(c[1]):02X}{int(c[2]):02X}" for c in chosen[:k]]


def generate_one(
    page: PackPage,
    *,
    ref_dir: Path,
    out_dir: Path,
    model: str,
    project: str,
    location: str,
    skip_existing_marker: bool = False,
) -> dict:
    client = _get_client(project, location)
    ref_imgs: list[Image.Image] = []
    for fname in page.refs:
        p = ref_dir / fname
        if not p.exists():
            raise FileNotFoundError(f"Missing reference image: {p}")
        with Image.open(p) as im:
            rgb = im.convert("RGB")
            rgb.load()
            ref_imgs.append(rgb)

    easy = page.difficulty == "easy"
    complexity = (
        "ULTRA-SIMPLE TODDLER COLORING BOOK PAGE (ages 3-5, designed for a 7-inch tablet): "
        "extra-thick bold smooth black outlines, ONLY 12 to 24 LARGE open closed shapes, "
        "ONE huge zoomed-in foreground character filling 75% of the page, PLAIN SOLID WHITE BACKGROUND, "
        "ZERO grass blades, ZERO small flowers, ZERO tiny stars, ZERO complex patterns or small details."
        if easy
        else "SIMPLE CHUNKY KIDS COLORING BOOK PAGE (ages 4-6, designed for a 7-inch tablet): "
        "thick bold smooth black outlines, ONLY 20 to 40 LARGE open closed shapes, "
        "BIG zoomed-in foreground character(s) filling 75% of the page with at most 1-2 simple large props, "
        "PLAIN CLEAN WHITE BACKGROUND, ZERO cluttered scenery, ZERO tiny leaves, ZERO grass tufts, ZERO checkered patterns, ZERO small details."
    )
    ref_clause = (
        "Match the character design, face, hair/ears, and outfit of the character(s) in the attached reference image(s), simplified into big bold toddler-coloring-book shapes. "
        if ref_imgs
        else ""
    )
    line_prompt = (
        f"Create a black-and-white toddler coloring book page (3:4 vertical portrait). {ref_clause}"
        f"Subject: {page.prompt} "
        f"{complexity} "
        "CRITICAL RULES: Pure black thick outlines (#000000) on a pure solid white background (#FFFFFF). "
        "NO gray tones, NO shading, NO cross-hatching, NO gradients, NO color, NO text, NO watermarks, NO border frame. "
        "Simplify all clothes, hair, and props into BIG, WIDE, EASY-TO-COLOR closed regions with NO tiny narrow gaps."
    )

    # Step 1: Generate clean black-and-white line art (retry once if > 70 regions)
    best_line = None
    best_n = 10**9
    for attempt in range(2):
        p_txt = line_prompt if attempt == 0 else (
            line_prompt + " MAKE IT EVEN SIMPLER AND BIGGER: zoom in closer on the character(s), remove all background elements completely, keep fewer than 35 large shapes!"
        )
        raw_line = _call_vertex(client, model, [*ref_imgs, p_txt])
        cand = clean_line_art(raw_line, easy=easy)
        _, cand_n = ndi.label(~cand)
        if cand_n < best_n:
            best_line, best_n = cand, int(cand_n)
        if cand_n <= 65:
            break
    line = best_line

    # Step 2: Colorize the exact cleaned line art using the character reference(s)
    line_png_buf = io.BytesIO()
    line_pil = Image.fromarray(np.where(line, 0, 255).astype(np.uint8), "L").convert("1", dither=Image.Dither.NONE)
    line_pil.save(line_png_buf, format="PNG", optimize=True)
    line_rgb_for_ai = Image.fromarray(np.where(line, 0, 255).astype(np.uint8), "L").convert("RGB")

    color_prompt = (
        "Color this exact black-and-white coloring book page cleanly for children. "
        + ("Use the EXACT character colors (skin, hair, fur, clothes, eyes) from the attached character reference image(s). " if ref_imgs else "")
        + "Fill every enclosed area with bright, cheerful, flat solid colors (like a finished children's coloring book). "
        "CRITICAL: Do NOT move, shift, erase, or redraw any black outline. Keep the exact composition and pixel positions identical to the black-and-white line drawing."
    )
    colored_raw = _call_vertex(client, model, [line_rgb_for_ai, *ref_imgs, color_prompt])

    # Step 3: Flatten per-region median color & stamp exact 1-bit lines on top -> 100% pixel alignment
    ref_arr, region_count = flatten_reference(line, colored_raw)
    palette = extract_palette(ref_arr, k=6)

    cat_dir = out_dir / page.category
    cat_dir.mkdir(parents=True, exist_ok=True)
    lp = cat_dir / f"{page.id}_line.png"
    rp = cat_dir / f"{page.id}_ref.webp"
    line_pil.save(lp, format="PNG", optimize=True)
    Image.fromarray(ref_arr, "RGB").save(rp, format="WEBP", lossless=True, quality=100, method=6)

    return {
        "id": page.id,
        "category": page.category,
        "index": page.index,
        "title": page.title,
        "difficulty": page.difficulty,
        "line": f"{page.category}/{page.id}_line.png",
        "ref": f"{page.category}/{page.id}_ref.webp",
        "regions": region_count,
        "palette": palette,
        "bytes": lp.stat().st_size + rp.stat().st_size,
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ref-dir", default=str(DEFAULT_REF_DIR))
    ap.add_argument("--out", default=str(OUT_DIR))
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--project", default=os.environ.get("GOOGLE_CLOUD_PROJECT", "gps-babel-tower"))
    ap.add_argument("--location", default=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"))
    ap.add_argument("--only", help="Comma-separated page ids or categories (e.g. elsa_01 or elsa,bluey)")
    ap.add_argument("--concurrency", type=int, default=8)
    ap.add_argument("--no-manifest", action="store_true")
    args = ap.parse_args(argv)

    os.environ.setdefault("GOOGLE_GENAI_USE_VERTEXAI", "true")
    ref_dir = Path(args.ref_dir)
    out_dir = Path(args.out)

    selected = PAGES
    if args.only:
        keys = {k.strip() for k in args.only.split(",") if k.strip()}
        selected = [p for p in PAGES if p.id in keys or p.category in keys]
    if not selected:
        print("No pages matched --only", file=sys.stderr)
        return 1

    t0 = time.monotonic()
    print(f"Generating {len(selected)} page(s) via Vertex AI {args.model} (concurrency={args.concurrency})...", flush=True)
    results: dict[str, dict] = {}
    failures: list[tuple[str, str]] = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        fmap = {
            pool.submit(
                generate_one,
                p,
                ref_dir=ref_dir,
                out_dir=out_dir,
                model=args.model,
                project=args.project,
                location=args.location,
            ): p
            for p in selected
        }
        done = 0
        for fut in concurrent.futures.as_completed(fmap):
            p = fmap[fut]
            done += 1
            try:
                res = fut.result()
                results[p.id] = res
                print(
                    f"[{done}/{len(selected)}] OK {p.id} ({p.title}) regions={res['regions']} size={res['bytes']/1024:.0f}KB",
                    flush=True,
                )
            except Exception as e:  # noqa: BLE001
                failures.append((p.id, str(e)))
                print(f"[{done}/{len(selected)}] FAIL {p.id}: {e}", file=sys.stderr, flush=True)

    if not args.no_manifest and results:
        manifest_path = out_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        updated_pages = []
        for entry in manifest["pages"]:
            pid = entry["id"]
            if pid in results:
                r = results[pid]
                updated_pages.append({
                    "id": r["id"],
                    "category": r["category"],
                    "index": r["index"],
                    "title": r["title"],
                    "difficulty": r["difficulty"],
                    "line": r["line"],
                    "ref": r["ref"],
                    "regions": r["regions"],
                    "palette": r["palette"],
                })
            else:
                updated_pages.append(entry)
        manifest["pages"] = updated_pages
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        total_mb = sum(p.stat().st_size for p in out_dir.rglob("*") if p.is_file()) / (1024 * 1024)
        print(f"Updated manifest.json (total builtin_packs = {total_mb:.2f} MB)", flush=True)

    print(f"Finished in {time.monotonic() - t0:.1f}s: ok={len(results)}, failed={len(failures)}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
