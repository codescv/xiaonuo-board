# 小诺画板 (XiaonuoBoard)

A native Android coloring app for kids, tuned for the **BOOX Nova Air C** (7.8" 3:4 Kaleido Plus color E-Ink, Wacom EMR stylus, Android 11, 3 GB RAM).
Written in Kotlin with Jetpack Compose, plus a reflection-only bridge to the BOOX Onyx SDK. On other Android devices it falls back to standard rendering.

## Build

```bash
# JDK 21 (configured globally via ~/.gradle/gradle.properties: org.gradle.java.home)
./gradlew testDebugUnitTest assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Gradle 8.10.2, AGP 8.7.1, Kotlin 2.0.21, Compose BOM 2024.10.01, compileSdk/targetSdk 35, minSdk 26.

## Modules (`app/src/main/java/dev/xiaonuoboard/app/`)

| Package | Contents |
| --- | --- |
| `eink/` | `OnyxEinkBridge`: reflection-safe `EpdController` access (NORMAL / REGAL / FAST / full-refresh ghost clearing, partial-rect refresh, scribble-mode stylus preview), with a graceful fallback on non-BOOX devices |
| `engine/` | Pure Kotlin, unit-tested on the JVM. `RegionMapEngine` (morphological closing plus scanline union-find labeling), `FloodFill` (O(1) region lookup, 1px bleed under the lines), `CrayonBrush` (stays inside the lines), `DirtyRectUndoStack` (64px tiles with RLE, 20 steps or 15 MB, whichever is hit first; allocation-free per-sample capture), `DirtyTileSet` (per-frame dirty-tile tracking for GPU uploads), `PaletteExtractor` (top-6 colors from 4096 bins), `RefAligner`, `ColoringCanvasController` |
| `ai/` | `GeminiImageClient` (direct `v1beta …:generateContent` call, `x-goog-api-key`, custom Base URL proxy, default model `gemini-nano-banana-2.1`), `PromptBuilder`, `LineArtSanitizer` (adaptive binarization, speckle removal, gap closing, QC), `AiPipeline` (3 steps) |
| `data/` | Manifest model, `PageRepository` (built-in packs, AI cache in `filesDir/ai_packs/`, imports), `ArtworkRepository` (auto-save, multiple copies per template), `GalleryExporter` (MediaStore → `Pictures/小诺画板/`), `SettingsStore`, `ParentGate`, `SoundFeedback` (silent by default) |
| `ui/` | Zero-animation Compose theme (`NoOpIndication`, 2dp `#111111` borders, `#F8F8F5` paper, touch targets ≥56dp), `HomeScreen`, `CanvasScreen` + `ColoringCanvasView` (tiled paint layer: only the tiles a stroke touches are re-uploaded; one coalesced invalidate / EPD partial refresh per frame; auto-save encodes off the UI thread), `AiWorkshopScreen`, `GalleryScreen`, `SettingsScreen`, `ParentGateDialog` |

## Built-in packs

`app/src/main/assets/builtin_packs/` holds 66 pages: 4 themed tabs × 10, plus 26 alphabet pages (`Aa`–`Zz`).
Each page has a 1-bit line-art PNG and a pixel-aligned colored reference WebP, all listed in `manifest.json`.

To regenerate them:

```bash
uv run scripts/generate_builtin_packs.py --mode offline                   # deterministic vector art + QC
GEMINI_API_KEY=… uv run scripts/generate_builtin_packs.py --mode gemini   # AI art (falls back to offline per page)
```

The offline art uses original themed scenes (winter/ice, backyard play, rainy-day and farm fun, bakery). It does not depict any third-party characters.
