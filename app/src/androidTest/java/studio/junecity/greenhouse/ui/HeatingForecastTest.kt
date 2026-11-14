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

    @Test fun liveFuelAndWeatherFeeds() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEnergy") == "true")
        for (fuel in EnergyUnit.entries) {
            val prices = parsePrices(fuel, publicEnergyData(fuel.priceUrl()))
            for (area in listOf("CT", "US")) {
                val price = requireNotNull(prices[area]) { "Missing ${fuel.fuel} quote for $area" }
                check(price.dollars > 0 && price.period.isNotBlank())
            }
        }
        val weather = kotlinx.coroutines.runBlocking {
            StationWeather(instrumentation.targetContext).update(LiveWeatherProvider().current("06382"))
        }
        assertEquals("CT", weather.stateCode)
        check(weather.stationId != null && weather.stationHours.isNotEmpty())
        val now = System.currentTimeMillis()
        val nights = NightHeating.forecast(weather, 60.0, now)
        check(nights.size == 7 && nights.all { it.degreeHours != null })
        check(weather.stationHours.keys.any { it in (now - 24 * HEATING_HOUR)..now })
    }

    @Test fun firstRunSinglePhoneGuideValidatesAndReturnsInputs() {
        assumeTrue(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        var result: studio.junecity.greenhouse.cloud.QuickStartData? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity.setContent {
                studio.junecity.greenhouse.cloud.QuickStartScreen(
                    studio.junecity.greenhouse.settings.CloudEnrollment("fixture", "", studio.junecity.greenhouse.cloud.CloudDeviceRole.ViewerClient),
                    {}, { result = it })
            }
        }
        fun node(text: String) = compose.onNodeWithText(text)
        node("Use this phone only").performClick()
        node("Continue").assertIsNotEnabled()
        node("Greenhouse ZIP code").performTextInput("06382")
        node("Length (ft)").performTextInput("16")
        node("Width (ft)").performTextInput("8")
        node("Continue").performScrollTo().performClick()
        node("Natural gas").performScrollTo().performClick()
        node("Finish setup").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("06382", result!!.zip)
            assertEquals(EnergyUnit.Therm, result!!.unit)
            assertEquals(16.0, result!!.length, .0001)
        }
    }

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
        val previousNight = time(today.minusDays(1),18)
        val sensor = GoveeSensor("a", "H5179", "Test sensor", greenhouseId = g.id)
        val history = (0..4).map { HistoryReading("a", "Test sensor", 63.0, 60.0, previousNight + it*h/4) }
        val state = GreenhouseUiState(weather = weather, sensors = listOf(sensor), history = history, preferences = AppPreferences(greenhouses = listOf(g), activeGreenhouseId = g.id))
        val rate = HeatingSettings()
        try {
            store.save(rate)
            compose.activityRule.scenario.onActivity { a ->
                a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                val reopened = HeatingStore(a, null, g.id)
                a.setContent { NightHeatingContent(state, reopened, {}) }
            }
            fun label(text: String) = compose.onNodeWithText(text)
            compose.waitUntil(8000) { compose.onAllNodesWithText("First 8 hours · est.").fetchSemanticsNodes().isNotEmpty() }
            label("First 8 hours · est.").assertExists()
            label(if (java.time.LocalTime.now(zone).hour < 6) "Next night" else "Tomorrow").performClick()
            label("Change fuel").performClick()
            label("Propane").performClick()
            assertEquals(HeatingSettings(EnergyUnit.Propane), HeatingStore(context,null,g.id).settings())
            compose.onNodeWithText("Propane  ·", substring=true).assertExists()
            label("Change fuel").performClick()
            label("Wood").performClick()
            compose.onNodeWithText("million Btu",substring=true).assertExists()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                java.io.File(context.cacheDir, "qa-heating-estimate.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
        } finally { store.save(original) }
    }

}
