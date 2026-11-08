# Greenhouse Monitor 2.6 validation

- Version 2.6.1 preserves one uniform feet-to-pixels scale on both map axes; the heat map is aspect-fitted rather than stretched to its card.
- Equal-size connected sections render as distinct equal-area rectangles, separated at their junction and labeled with their physical dimensions. One-foot grid cells remain square on both axes.
- General Settings defaults to concise greenhouse and layout summaries; dimension editing and multi-greenhouse management remain available on demand.
- Build: `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed on November 8, 2026.
- Geometry tests verify a 16 × 8 ft rectangle is 128 sq ft.
- Geometry tests verify two connected 16 × 8 ft sections form a 256 sq ft T plan with a 16 × 24 ft bounding footprint.
- Geometry tests verify centered offsets and point containment when the stem is wider than the crossbar.
- The Settings editor and Zones heat map use the same persisted `GreenhouseConfig` geometry.
- Existing sensor positions are proportionally reprojected and constrained to the new footprint after a layout change.
- Settings typography was increased independently from the other application screens.
- Screens were rendered and visually checked at the Moto G7 landscape target of 2270 × 1080.
