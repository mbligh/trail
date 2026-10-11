# Trail — project context and requirements

Last collated: 2026-10-10. This document describes the agreed product requirements and the implementation as of Trail 1.8. Use it to resume development without needing the original conversation. Keep it current when behavior or requirements change.

## Purpose and scope

Trail is a small native Android application for following trails, recording walks or rides, and reporting trail problems with a location and optional photograph. Favor a simple map interface, standard Android camera/share flows, and on-device storage. No account, backend, analytics, Google Play Services, or API key is required.

The displayed application name is **Trail**. The canonical repository is **mbligh/trail**, with the Android project at the repository root. It was moved out of **mbligh/tools/trail**; earlier development history remains in that repository. The Android application ID stays **net.pocketmap** so new APKs update the original Pocket Map/Trail installation.

## 1. Current GPS position and map interaction

- Obtain the current GPS position and display a blue location dot over a basemap.
- Follow the position while the user moves, retaining the chosen zoom.
- Support pinch zoom and map dragging. Dragging pauses following; **Recenter** restores it.
- Display location status/accuracy and handle missing permission, disabled location providers, and waiting for a GPS fix.
- Preserve map center and zoom across sessions.
- Foreground map following is distinct from explicitly recording a track: merely opening the app does not start a recording.

Current implementation uses Android LocationManager with GPS and network providers for foreground location updates. Foreground updates stop when the activity is paused. Approximate location can display a position, but recording requires precise location and GPS enabled.

## 2. Basemap

Default to **OpenStreetMap street tiles**, rendered with osmdroid. Display OpenStreetMap attribution with a link to its copyright page.

Tiles are cached through the map engine's normal cache behavior. Internet access is required for tiles not already cached. There is currently no bulk tile download or guaranteed offline map package. Satellite imagery is requested only in shared Google Maps links; it is not the app's default basemap.

## 3. Google My Maps trail overlay

Source map:
https://www.google.com/maps/d/u/0/edit?mid=1zy-nQvF6-6P7JSs3paMU8byWHoE&usp=sharing

Map ID: **1zy-nQvF6-6P7JSs3paMU8byWHoE**.

Requirements:

- Overlay the source trail geometry and places on the OpenStreetMap basemap.
- Use only the layer named **Trails**; ignore all other layers. The exact source layer name is plural, even when described conversationally as the “trail layer.”
- Prefer automatically fetching current data at app startup. An exported KML/KMZ was the acceptable fallback discussed if dynamic loading proved impossible.
- Preserve source styles, including line colors and My Maps marker artwork.
- Allow users to refresh, hide/show, or zoom to the overlay. Zooming to trails pauses GPS following; Recenter restores it.
- Tap features to display their names. Show whether overlay data is refreshed, cached, or unavailable.

Implemented ingestion is the dynamic **KML** export:
https://www.google.com/maps/d/kml?mid=1zy-nQvF6-6P7JSs3paMU8byWHoE&forcekml=1

A background fetch runs at startup. The last validated KML is stored privately, shown on subsequent starts, and retained if refreshing fails. The map must remain accessible without Google sign-in. This Google export endpoint is not a versioned API.

**Implementation boundary:** arbitrary local KML/KMZ file selection and KMZ decompression are not implemented. Do not describe them as existing capabilities. The current KML route satisfies the preference for dynamic source updates.

The initial Trails layer contains 108 lines and 10 places, with no polygons. These are historical smoke-test expectations, not a limit on future map edits. The parser supports style maps, KML color conversion, multigeometry, polygons and holes. Styles are resolved from the whole document even though only Trails features are displayed. Unsafe XML/DOCTYPE/entity input and oversized data are rejected; network links, ground overlays, and HTML balloons are not loaded.

## 4. My Maps icons

The requirement is to retain recognizable source icons, particularly the blue camper/trailer artwork for **Hell Mountain Parking**, rather than replacing everything with generic map pins.

Current implementation bundles the current My Maps camper, blue pin, blank marker, and medical artwork in drawable-nodpi so those icons also work offline. KML marker scale is respected; blank markers use the KML tint. Some My Maps KML exports use a generic blank icon for custom symbols, so the original composed artwork was obtained from the My Maps viewer and bundled separately.

Startup refresh downloads KML, not arbitrary remote artwork. New unsupported icon types currently fall back to a standard pin until their artwork/mapping is added. Preserving icons remains the requirement; generic fallback is a known implementation limitation.

## 5. Track recording and GPX sharing

- Provide a red circular **Record** control at the bottom right, approximately 56 dp across. While recording it becomes a red stop square.
- Start a new recording on activation. Request the first fresh GPS point immediately; do not intentionally wait 30 seconds before requesting it.
- Subsequently save GPS samples at least 30 seconds apart. Actual availability/delivery depends on GPS and Android; never fabricate a point when a fix is missing.
- Draw the recorded path so far as a **blue line**, growing as saved samples arrive. At least two points are required to draw a segment.
- Continue recording when the screen is locked or the map activity is backgrounded, using a location foreground service and a recording notification with a Stop action.
- When stopped from the app, retain the track and offer to share it as a **GPX** file through the standard Android share chooser, including Gmail or another email app.
- Keep earlier recordings and support sharing them later through **Trails → Share recorded GPX…**.
- The current/latest recorded path remains visible after stopping or reopening the app.

The first sample is the first delivered fresh fix, not a guarantee of an instantaneous fix when the user taps Record. Very short recordings may have zero or one point and cannot necessarily draw a line. A zero-point track produces an explanatory message rather than an empty GPX attachment.

Each recording has a separate private CSV file; completed samples are appended immediately. GPX 1.1 exports include latitude, longitude, UTC timestamps, and elevation when supplied. Gaps longer than 90 seconds produce separate GPX/map segments to avoid drawing a misleading connection across a GPS outage.

Screen-locked recording was reported working by the user. Process termination or a phone restart stops recording; previously saved points remain available. Starting a new recording after reopening is explicit. No automatic resumption is currently provided.

## 6. Location report flag, optional camera photo, and sharing

- Provide a red **Flag** control at the bottom left, approximately the same size as Record.
- Capture the current location when the flag is activated. A fix older than 30 seconds is rejected; ask the user to wait for a fresh location.
- Start an editable report with latitude and longitude as plain text, six decimal places, separated by a comma and space: **40.672725, -74.749000**. Decimal formatting must remain consistent regardless of phone locale.
- Include a Google Maps URL for the same coordinates, requesting **satellite view**.
- Display GPS accuracy separately; coordinate decimal precision is not a claim of equivalent physical accuracy.
- Allow the user to add a description, such as “tree blocking trail,” before sharing.
- Offer **Take photo** as an optional action using the phone's standard camera app. Confirming returns to the report with a full-resolution JPEG attached. Offer **Retake photo** once attached.
- Preserve the captured coordinates and edited description while using the camera, including activity recreation. Canceling the camera retains the report and any earlier attachment.
- Share text alone or text plus photo through the standard Android share chooser.
- Supply **mbligh@mbligh.org** as the suggested email To recipient and **Trail location report** as the subject. Nothing is sent automatically; the receiving app handles review and sending.

Current satellite URL format:
https://www.google.com/maps/@?api=1&map_action=map&center=40.672725%2C-74.749000&zoom=18&basemap=satellite

This documented map-view URL centers the satellite map on the reported location **without a pin**. The Google Maps app or browser handles opening it. Receiving share apps decide whether they retain supplied text, image, subject, and recipient extras; the email recipient cannot be enforced by Trail.

Reports capture a fixed location instead of silently updating while the user edits or takes a photo. Camera capture uses a URI grant and the existing camera app, without requesting camera or general storage permission. Photos are kept in private app storage. Camera access is revoked on return; share targets receive read access. Attachments remain available after sharing so receiving apps can read them. There is no photo-retention cleanup policy yet; uninstalling removes private data.

## Architecture, storage, and compatibility

- Native Kotlin Android activity/UI. Android 8+ (minimum API 26); compile/target API 36 as of this snapshot.
- JDK 17, Kotlin 2.1.20, Android Gradle Plugin 8.9.1, Gradle wrapper 8.13; osmdroid 6.1.20.
- MainActivity: map, following, overlay display, controls, report editor/camera result, and sharing.
- TrailRepository and TrailKml: startup refresh/cache and parsing/style resolution.
- RecordingService and RecordedTrack: foreground recording, private persisted samples, and GPX export.
- LocationReport: consistent coordinate formatting and satellite URL.
- GpxProvider and ReportPhotoProvider: scoped URI access to exported tracks and report photos; never expose unrelated private files.
- Location permission is needed; precise permission and enabled GPS are required for recording. Foreground-service/notification declarations support recording while locked.
- No analytics or app backend. Tile/KML requests contact their respective external services; user-initiated sharing hands report data to the chosen app.
- osmdroid is archived upstream and currently pinned. Replacing it is a future implementation decision, not an agreed new feature.
- iPhone support was discussed and deferred. There is no iOS target or shared Kotlin Multiplatform UI currently.

Preserve application ID and the included development signing key across builds so APKs update existing installations. The key alias is androiddebugkey and its standard debug passwords are android. These are personal development APKs, not a Play Store release. App version at this snapshot is 1.8 / versionCode 9; increment version metadata when releasing application changes.

## Repository, automated builds, and distribution

Canonical source: https://github.com/mbligh/trail
Default branch: **main**.
Download page: https://mbligh.github.io/trail/
APK URL: https://mbligh.github.io/trail/Trail.apk

The **Build and publish APK** Actions workflow runs on main pushes changing app/** and also relevant Gradle files, wrapper, signing key, workflow, or site files. Pull requests run validation without publishing; workflow_dispatch supports manual builds.

On an Ubuntu 24.04 runner with Java 17, it runs:
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

It publishes the signed debug APK, an HTML download page, and SHA-256 checksum to GitHub Pages, and retains an APK Actions artifact. Failed validation must not replace the previous download. GitHub Pages requires the repository's Pages source to be set to **GitHub Actions**; deployment status should be checked in Actions rather than inferred from this document. Documentation-only changes such as project.md do not trigger an APK rebuild.

## Validation and guidance for future work

Ten unit tests passed with the Trail 1.7 build, covering KML parsing, GPX recovery/export, report formatting/link consistency under different locales, and photo-path confinement. Android lint and APK signing verification passed. The first standalone-repository CI build also passed tests, lint, and APK assembly; Pages enablement was a separate configuration step.

Keep manual phone checks for GPS acquisition/permissions, map gestures, a multi-minute screen-off recording, stop/share behavior, photo capture/cancel/retake, activity rotation, and Gmail text/photo/recipient handling. User confirmation of screen-locked recording is useful evidence but does not cover every device or camera/share app.

When editing, preserve the simple two-corner controls, current-location behavior, exact Trails-layer filtering, existing icon artwork, blue recorded line, editable report, optional photo flow, and install compatibility. Update this document when requirements or implementation boundaries change; do not silently turn previously discussed options into claimed implemented features.

## Configurable basemap selector (1.8)

A selector at the top-left of the map lists every entry in repository-root basemaps.json. Each entry requires name and url; the HTTPS URL uses {z}, {x}, and {y} placeholders for standard 256-pixel Web Mercator tiles at supported app zoom levels 3–19. Optional attribution and attributionUrl update the attribution footer. The configuration is bundled as an Android asset by Gradle, so adding entries requires rebuilding, not changing Kotlin code. Config changes also trigger Actions.

Initial choices are openstreetmap and njsatellite (2020 NJ aerial imagery). OSM is the initial default; the user's choice is remembered. Switching preserves position, zoom, trails, recording, and report controls. Each URL has its own disk-cache namespace so tiles from different basemaps cannot mix. NJ ArcGIS takes /tile/{z}/{y}/{x}, despite the initially suggested x/y order. The service exposes 256-pixel EPSG:3857 tiles; imagery coverage is New Jersey.

## Phone-facing position marker (1.9)

The blue GPS dot becomes a blue triangle when the phone reports a reliable compass heading. Its tip follows the top of the displayed screen, including landscape rotation, and updates while stationary. The map stays north-up; this is phone-facing direction, not GPS travel bearing. Rotation-vector sensors are preferred, with geomagnetic rotation-vector fallback. Magnetic heading is corrected to true north using the current GPS position, and circular smoothing handles crossing 0/360 degrees.

If no supported heading sensor is available, accuracy is low/unreliable, the top edge is almost vertical (horizontal facing direction undefined), or heading data is older than five seconds, show the original dot. Sensor listening runs only while the activity is visible. Recording remains independent. Phone checks: turn in place outdoors, rotate the display, compare to known directions, and check magnetic interference/low-accuracy fallback.
