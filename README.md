# Photo Unroll

Compose Multiplatform app (Android · iOS · Desktop · Web) that flattens what photographs curve.

## Modes

**Unroll** — walk around a cylinder (can, column, bottle, tree trunk, tattooed forearm), one photo every 30–45°, same height and distance. Each photo gets guides marking the silhouette and both rims; auto-detect proposes them, you correct them. The visible face of each photo is unwrapped uniformly in angle and the strips are stitched into the surface laid flat. With **Full 360°** the last photo is aligned back onto the first, so the output is exactly one circumference wide and tiles seamlessly.

**Panorama** — stand still and turn, overlapping frames by about a third. Frames are reprojected onto a cylinder (set the lens FOV; phone main cameras are ~65–75°), stitched, then mapped to an equirectangular band and saved as JPEG with Photo Sphere (GPano) XMP. Google Photos, Facebook and most 360° viewers wrap it; the in-app viewer scrolls it forever.

Both results open in a viewer that drags/flings horizontally and, when closed into a full turn, never ends.

## Layout

~~~
composeApp/src/
  commonMain/  imaging/  all math — pure Kotlin, shared by every target
                 RgbaImage            ARGB raster, bilinear sampling, EXIF rotation
                 CylinderGuides       silhouette + rim ellipses, normalized
                 CylinderUnroller     photo face -> flat strip (uniform in θ)
                 CylindricalProjector frame -> cylinder; cylinder -> equirect band
                 Stitcher             coarse-to-fine NCC alignment, loop closure, feather blend
                 EdgeSuggester        background segmentation + rim ellipse fit
                 PanoramaXmp          GPano XMP, APP1 injection
                 Exif                 orientation tag reader
                 Pipelines            the two end-to-end operations
               ui/  GuideEditor, StripViewer, Theme
               App.kt, AppState.kt
  skiaMain/        codec for desktop, iOS, web (Skia)
  androidMain/     codec (Bitmap), MainActivity
  fileSystemMain/  save dialog (Android, desktop, iOS)
  wasmJsMain/      browser download, entry point
  commonTest/      synthetic-scene tests for unroll, stitch, panorama, auto-detect, EXIF/XMP
iosApp/iosApp/   SwiftUI host for the shared UI
~~~

## Build

~~~
./gradlew :composeApp:desktopTest                 # imaging tests
./gradlew :composeApp:run                         # desktop
./gradlew :composeApp:assembleDebug               # Android APK
./gradlew :composeApp:wasmJsBrowserDevelopmentRun # web
~~~

iOS: open an Xcode project at `iosApp/` whose target embeds the `ComposeApp` framework (build phase `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`) and uses the sources in `iosApp/iosApp/`. The `.xcodeproj` itself is not checked in yet; generate it from `HereLiesAz/compose-multiplatform-template` or Xcode's App template.

## Limits

- Unroll assumes a near-orthographic view with the axis vertical in frame. Shoot from a few diameters away; telephoto beats wide.
- Stitching aligns by translation only. Keep height and distance constant; vertical drift up to ~6% is absorbed.
- Capture order matters (either direction works, detected automatically); photos must be imported in shooting order.
- Inputs are capped at 1600 px on the long side to stay inside mobile and browser memory.
