package studio.junecity.greenhouse.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.data.*
import studio.junecity.greenhouse.model.*

class HeatingForecastTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun heatingForecastDisplaysAndRestoresPricedHouse() {
        assumeTrue("Use isolated emulator", android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val context = instrumentation.targetContext
        val g = GreenhouseConfig(id = "heat-fixture", name = "Test greenhouse")
        val store = HeatingStore(context, null, g.id)
        val original = store.settings()
        val zone = java.time.ZoneId.of("UTC")
        val today = java.time.LocalDate.now(zone)
        fun time(day: java.time.LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val sun = (-1L..8L).associate { i -> val d = today.plusDays(i); d to SolarDay(time(d,6),time(d,18)) }
        val h = 3_600_000L
        val outdoor = (0..239).associate { time(today.minusDays(1),0) + it*h to 40.0 }
        val weather = WeatherReading("06382", "Fixture", 40.0, "Clear", System.currentTimeMillis(),
            zone = zone, hourlyOutdoorF = outdoor, solarDays = sun)
        val state = GreenhouseUiState(weather = weather, preferences = AppPreferences(greenhouses = listOf(g), activeGreenhouseId = g.id))
        val rate = HeatingSettings(price=.20)
        try {
            store.save(rate)
            compose.activityRule.scenario.onActivity { a ->
                a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                val reopened = HeatingStore(a, null, g.id)
                a.setContent { NightHeatingDialog(state, reopened, {}, {}) }
            }
            val k = heatingCoefficient(g, rate.unit)
            fun label(text: String) = compose.onNodeWithText(text)
            label("First 8 hours · est.").assertExists()
            label(money(160*k.nominal*.20)).assertExists()
            label(if (java.time.LocalTime.now(zone).hour < 6) "Next night" else "Tomorrow").performClick()
            label("Edit").performClick()
            label("therm").performClick()
            label("Price ($ / therm)").assertExists()
            label("Cancel").performClick()
            label("Edit").performClick()
            label("60").performTextReplacement("62")
            label("Save").performClick()
            label(money(176*k.nominal*.20)).assertExists()
            assertEquals(rate.copy(targetF=62.0), HeatingStore(context,null,g.id).settings())
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                java.io.File(context.cacheDir, "qa-heating-estimate.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
        } finally { store.save(original) }
    }

}
