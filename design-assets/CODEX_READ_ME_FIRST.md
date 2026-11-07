# CODEX: EXACT DESIGN IMPLEMENTATION PACKAGE — GREENHOUSE MONITOR

Read all files in this folder before editing. This is an EXISTING native Android project. Inspect architecture and preserve the working Govee H5179 API-key integration, sensor assignments, stored history, notification preferences, and existing navigation. Do not create a replacement app.

## SOURCE OF TRUTH / CONFLICT RESOLUTION
1. `approved_landscape_dashboard.png` is the exact approved DASHBOARD composition. Reproduce its layout and visual hierarchy. Do not add a greeting, title, branding, duplicate navigation, or extra status panel.
2. `premium_style_reference.png` is the visual-language reference ONLY: refined near-black surfaces, warm amber active states, quiet borders, elegant type, soft illumination and restrained transitions. It contains unrelated portrait smart-home features: DO NOT copy those.
3. `design_tokens.json`, `icon_manifest.json`, `motion_spec.json` provide implementation starting points. They are explicitly ESTIMATED, not original source design files. Refine them against actual screenshot comparisons.
4. For Zones, History, Cameras and Settings, use their separately user-approved screenshots when provided. Do not invent replacements or change previously approved layout.
5. When a detail is ambiguous, preserve the screenshot's visible arrangement; don't add UI.

## EXACT DASHBOARD COMPOSITION
- LANDSCAPE ONLY. A responsive Android UI, not a static screenshot. Use Compose if consistent with existing repo.
- Left: compact outdoor-weather card at top; 2x2 tiles for average temperature, average humidity, VPD and dew point; chart at lower left.
- Center: dominant amber radial readout showing current greenhouse temperature and RH, with small HEAT/COOL/FAN indicators below. Do NOT implement controls for heaters/fans unless real, safe hardware control already exists. Otherwise render as status indicators or disable.
- Right: greenhouse camera image/actual live feed only when configured and connected; below it sensor list with current temp/RH and connection state. Never label a demo image 'Live'.
- Bottom: fixed floating nav Dashboard / Zones / History / Cameras / Settings, amber highlight only on active tab.
- No 'Good evening' heading, no Junecity wordmark, no leaf-logo, no top navigation.
- Weather ZIP code editable in Settings, weather card always visible, display whole-number outdoor temperature.
- Temperatures and RH rounded to integers FOR DISPLAY ONLY. Retain raw precision for all calculations.
- Dynamic sensor count; list scrolls when necessary without shrinking the main temperature.

## COMPONENT AND ASSET SPECIFICATIONS
- Create reusable `AppColors`, `AppTypography`, `AppSpacing`, `AppRadii`, `AppMotion`, `AppIcons` from JSON.
- Use vector icons matching `icon_manifest.json`; do not embed emoji or bitmap screenshots as interactive UI.
- Use system/redistributable typography, not a copied proprietary font. Start with a geometric sans available to the project and tune weights/letterspacing via screenshot comparison. No font files included.
- Radial ring: 270–320dp nominal diameter at target landscape reference, 8–10dp rounded amber arc, subtle radial halo (not a harsh neon glow), static dark track; responsive scaling. The ring conveys monitoring and must not falsely indicate heater actuation.
- Card surfaces: near-black charcoal with subtle vertical gradient, thin 1dp neutral border, 20–26dp corners, very soft shadow. Keep the camera image corners rounded.
- All main readings must be legible from several feet away. Minimum 48dp touch targets; no overlap/clipping at font scale 1.0, 1.3 and 1.5.
- Use a layout grid that preserves relative panel proportions across A71 5G, Pixel 10 and a compact landscape phone; collapse secondary details into scrollable subpanels rather than scale all text down.

## MOTION / FADES
- Implement motion_spec.json values centrally, with Android Compose animation primitives and reduced-motion handling.
- Bottom navigation: 220ms crossfade + 12dp incoming translation; bar itself never jumps.
- Initial card appearance: 260ms fade/8dp rise; max 35ms stagger. Never repeat on every polling update.
- Sensor selection: 180ms border/background fade.
- Ring: 700ms interpolation from last measured value to new value, not continuous looping.
- Number changes: 220ms crossfade, fixed-width/tabular numerals, no flicker.
- Chart initial reveal: 420ms; later points update smoothly without clearing the chart.
- Camera: 250ms transition from 'Connecting' to verified live video; show 'Offline' when disconnected.
- Disable ornamental movement when OS reduce-motion is enabled; never animate away warnings.

## FEATURE PRESERVATION AND FUNCTIONAL REQUIREMENTS
- Govee H5179 existing API-key flow must keep working; secure key storage, test connection, rate-limit-aware polling, freshness timestamps.
- Zones: user enters greenhouse length/width (square footage computed), drags each sensor to physical position, saves coordinates; separate greenhouses and mounting heights; distance-weighted interpolated heat map with honest estimated center/area average. Offline/stale sensors excluded. No decorative fabricated values.
- History: time-series chart for temperature/RH/VPD/dew point, 24h/7d/30d selection, real stored data only.
- Cameras: greenhouse phone rear camera can stream to authorized remote Android viewer with encrypted WebRTC and signaling/TURN as needed, obey Android camera/background restrictions. Do not mark as complete without actual cross-network test.
- Alerts: thresholds, email recipients with secure backend, local pop-up on/off, sound on/off, mute/snooze and cooldown; recurring unwanted five-minute popups OFF by default; monitoring must continue while alerts are muted.
- Weather: user-entered ZIP code, validated geocoding, cached weather with age indicator, outdoor temperature always visible.

## VISUAL QA / DELIVERY
1. Audit repo and report components to preserve.
2. Build dashboard first; capture REAL emulator/device screenshot at the reference aspect ratio. Compare side by side with approved PNG and fix layout/colors/typography. Do not declare pixel perfect without measuring.
3. Build remaining screens against their individual approved references, using same tokens.
4. Test multiple landscape sizes and font scales. Verify touch targets and no clipping.
5. Test real Govee values, ZIP weather, alert suppression, camera permission flow and error states.
6. Deliver updated project, APK if buildable, screenshot of EACH screen, design-token changes, and a clear list of verified vs unverified features.

Do not ask the user to approve a new style. The design is already approved.
