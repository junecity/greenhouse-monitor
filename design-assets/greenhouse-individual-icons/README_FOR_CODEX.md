# Greenhouse Monitor — individual icon assets

This is a package of ORIGINAL vector icon assets generated specifically for the greenhouse monitor. Each icon is its own standalone SVG, plus a matching 128px transparent PNG preview. They are actual files, not cropped pieces of an icon sheet.

## Usage
- `svg/`: scalable vectors, 24×24 viewBox, 1.8 stroke, rounded caps/joins.
- `png_128/`: transparent PNG previews, 128×128 pixels.
- `manifest.json`: exact names, groups, variants, colors and relative paths.
- Variants: `default` white, `active` amber, and selected semantic states (info, success, warning, danger, disabled).
- Android: import SVG as VectorDrawable when compatible, or convert the paths into Compose ImageVector/vector XML. Some SVG features (text and gradient) require manual vector conversion or rendering through a supported SVG library. Do not ship PNG previews as the primary icons on high-density screens.
- All icons should be drawn at 24dp or 28dp with minimum 48dp touch targets.
- No branding or decorative leaf icons.

## Motion specification
- Tab change: 220ms crossfade, 12dp slide of incoming content, bottom bar stays stationary.
- Active nav icon: 180ms color fade to amber, optional 1.04x scale, never pulsate continuously.
- Button press: 90ms down / 140ms release, scale 0.98.
- Card reveal: 260ms alpha 0→1 and Y translation 8dp→0 on first entrance only.
- Temperature ring: 700ms value interpolation, not infinite rotation.
- Sensor update: 220ms text crossfade with tabular numerals.
- Camera connecting: 250ms transition from connecting to verified live image.
- Respect Android reduced-motion settings; avoid constant decorative animations.

## Codex integration
Inspect existing native Android project and preserve working Govee integration. Use these icons consistently across Dashboard, Zones, History, Cameras, Settings, weather, sensors, camera, alerts, and editing. Do not replace approved landscape layout. These icon assets implement the agreed dark charcoal + warm amber visual style. Validate actual rendered app screenshots.
