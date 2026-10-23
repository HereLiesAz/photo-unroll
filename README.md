# Photo Unroll

Compose Multiplatform app (Android · iOS · Desktop · Web) that flattens what photographs curve.

## Modes

**Unroll** — walk around a cylinder (can, column, bottle, tree trunk, tattooed forearm), one photo every 30–45°, same height and distance. Each photo gets guides marking the silhouette and both rims; auto-detect proposes them, you correct them. The visible face of each photo is unwrapped uniformly in angle and the strips are stitched into the surface laid flat. With **Full 360°** the last photo is aligned back onto the first, so the output is exactly one circumference wide and tiles seamlessly.

**Panorama** — stand still and turn, overlapping frames by about a third. Frames are reprojected onto a cylinder (set the lens FOV; phone main cameras are ~65–75°), stitched, then mapped to an equirectangular band and saved as JPEG with Photo Sphere (GPano) XMP. Google Photos, Facebook and most 360° viewers wrap it; the in-app viewer scrolls it forever.

Both results open in a viewer that drags/flings horizontally and, when closed into a full turn, never ends.

### Video

**+ VIDEO** takes a slow lap around the subject (or a slow turn on the spot) instead of photos. The app pulls 16–40 evenly spaced frames (about 1.5 per second), shares one set of guides across them (the median of every frame's own guess), and, if you walked past a full turn, drops the frames after the view comes back to the start. Frames arrive already in order.

| Target | Decoder |
| ------ | ------- |
| Android | `MediaMetadataRetriever` |
| Web | `<video>` + canvas (formats the browser can play: MP4/H.264, WebM) |
| Desktop | `ffmpeg` and `ffprobe` on the PATH |
| iOS | `AVAssetImageGenerator` |

### Automatic order

Photos are put in capture order on import (and by **ORDER**), whatever order the picker returned them in. Each photo is flattened with its guides (or reprojected with the lens FOV), reduced to texture only (shading and contrast evened out), and every pair is correlated at every slide. The strongest chain through all of them wins; a full walk-around closes into a ring that is cut at its weakest seam, then single photos are moved wherever they strengthen the chain. Run **ORDER** again after correcting guides. Video frames are never reordered.

### Rotate and warp

Each photo has **Rotate** (any angle, level grid, live preview) and **Warp** (drag four corners onto something that should be rectangular) tabs. Both are applied in one resample from the untouched original, before guides, unrolling and stitching.

## Layout

~~~
composeApp/src/
  commonMain/  imaging/  all math — pure Kotlin, shared by every target
                 RgbaImage            ARGB raster, bilinear sampling, EXIF rotation
                 PhotoAdjust          rotation + four-corner perspective (homography)
                 CylinderGuides       silhouette + rim ellipses, normalized
                 CylinderUnroller     photo face -> flat strip (uniform in θ)
                 CylindricalProjector frame -> cylinder; cylinder -> equirect band
                 Stitcher             coarse-to-fine NCC alignment (sub-pixel), loop closure, feather blend
                 Arranger             capture-order recovery; one-turn detection for video
                 EdgeSuggester        background segmentation + rim ellipse fit
                 PanoramaXmp          GPano XMP, APP1 injection
                 Exif                 orientation tag reader
                 Pipelines            the end-to-end operations
               video/  VideoFrames (expect), frame count policy
               ui/  GuideEditor, AdjustEditors, StripViewer, Theme
               App.kt, AppState.kt
  skiaMain/        codec for desktop, iOS, web (Skia)
  androidMain/     codec (Bitmap), video (MediaMetadataRetriever), MainActivity
  desktopMain/     video (ffmpeg)
  iosMain/         video (AVFoundation)
  wasmJsMain/      video (<video> + canvas), browser download, entry point
  fileSystemMain/  save dialog (Android, desktop, iOS)
  commonTest/      synthetic-scene tests for unroll, stitch, panorama, arrange, adjust, auto-detect, EXIF/XMP
iosApp/iosApp/   SwiftUI host for the shared UI
~~~

## Build

~~~
./gradlew :composeApp:desktopTest                 # imaging tests
./gradlew :composeApp:run                         # desktop
./gradlew :composeApp:assembleDebug               # Android APK
./gradlew :composeApp:wasmJsBrowserDevelopmentRun # web (installable PWA, offline after first load)
~~~

iOS: open an Xcode project at `iosApp/` whose target embeds the `ComposeApp` framework (build phase `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`) and uses the sources in `iosApp/iosApp/`. The `.xcodeproj` itself is not checked in yet; generate it from `HereLiesAz/compose-multiplatform-template` or Xcode's App template.

## Limits

- Unroll assumes a near-orthographic view with the axis vertical in frame. Shoot from a few diameters away; telephoto beats wide.
- Stitching aligns by translation only. Keep height and distance constant; vertical drift up to ~6% is absorbed.
- Order is recovered from content, so photos with no overlap, a plain featureless surface, or a surface with exact repeats (evenly spaced identical stripes) can be misordered; check the thumbnails and fix the guides, then press ORDER.
- Inputs are capped at 1600 px on the long side to stay inside mobile and browser memory.
