# Greenhouse Dashboard — Extracted Design Specification

Source: `Dark Greenhouse Dashboard and Zones Map.png` (1672 × 941 px). Stage 1 uses only the upper dashboard. The visible upper display was measured at approximately x=63, y=10, width=1555, height=443 (3.51:1).

## Responsive canvas

- Normalized Compose canvas: 1536 × 432 dp-equivalent design units.
- Scaling: typography and icons use the smaller width/height scale; horizontal panel widths use responsive proportions while vertical regions use full available height.
- Galaxy A71 5G target: 2400 × 1080 landscape, filled edge to edge.
- Verified responsive sizes: 1280×720, 1600×900, 1920×1080, Moto G7 at 2270×1080, and Galaxy A71 5G at 2400×1080.
- Outer horizontal inset: 22 units.
- Header: 78 units high.
- Main panels: y=78, height=276 units.
- Bottom navigation: y=368, height=64 units.

## Main geometry

| Component | x | y | width | height | radius |
|---|---:|---:|---:|---:|---:|
| Brand block | 22 | 16 | 320 | 50 | — |
| Clock block | 610 | 5 | 316 | 68 | — |
| Device status | 1280 | 10 | 230 | 58 | — |
| Greenhouse panel | 22 | 78 | 460 | 276 | 18 |
| Outdoor panel | 500 | 78 | 353 | 276 | 18 |
| Camera panel | 871 | 78 | 643 | 276 | 18 |
| Bottom navigation | 22 | 368 | 1492 | 64 | 28 |

Panel gaps are 18 units. Panel borders are 1 unit. The main-panel left/right padding ranges from 18–38 units depending on content density.

## Sampled palette

| Token | Hex | Use |
|---|---|---|
| Canvas deep | `#00090C` | letterbox and deepest background |
| Canvas | `#001014` | primary dashboard background |
| Panel | `#091A1E` at ~79% opacity | card surface |
| Panel edge | `#17343A` | 1-unit borders |
| Divider | `#153037` | status/nav separators |
| Primary white | `#F7F9FA` | values, titles, brand |
| Muted text | `#C4D0DF` | labels and secondary values |
| Green | `#52F596` | temperature, active state, connectivity |
| Blue | `#49AFF4` | humidity and rain |
| Amber | `#FFB600` | sun/weather accent |

## Type hierarchy

- Brand: 25 units, bold, white; supporting tagline 10 units with 2.2-unit tracking.
- Clock: 52 units bold; AM/PM 27 units bold; date 15 units.
- Panel titles: 23 units semibold; subtitles 16 units.
- Greenhouse temperature: 72 units bold.
- Humidity: 62 units bold; label 18 units.
- Outdoor temperature: 58 units bold; condition 17 units.
- Forecast values: 27 units bold; labels 13 units.
- Bottom navigation: 15 units; active item bold.

Android's bundled sans-serif (Roboto on the test emulator and Galaxy A71 family) is used. The reference's exact source font was not embedded, so font rasterization may vary slightly across Samsung firmware.

## Current stage decisions

- Sensor values are live when online H5179 devices are discovered; otherwise the dashboard uses clearly marked demonstration values.
- Outdoor weather is live from device location through Open-Meteo; unavailable or unapproved location access is stated instead of fabricated.
- Camera content is a local demonstration still cropped from the approved reference. No camera stream is initialized.
- Clock, date, battery, charging, and Wi-Fi status are read from Android.
- Zones dynamically discover H5179 sensors and persist a Zone 1–4 assignment per sensor.
- History retains live readings locally for 30 days. Foreground and background refresh paths evaluate notification thresholds.
- Remote phones use the same encrypted Govee cloud integration; no unsupported H5179 LAN protocol is assumed.
