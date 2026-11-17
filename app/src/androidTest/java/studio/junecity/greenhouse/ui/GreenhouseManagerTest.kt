package studio.junecity.greenhouse.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GreenhouseManagerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun withOwner(block: (CloudEnrollment) -> Unit) {
        val secure = SecureSettingsStore(compose.activity)
        val previous = secure.cloudEnrollment()
        val owner = CloudEnrollment("device-" + UUID.randomUUID(), "", CloudDeviceRole.ViewerClient,
            localSensors = true, tenantId = "test-" + UUID.randomUUID())
        secure.saveCloudEnrollment(owner)
        GreenhousePlanCache.resetLocalCollectorOwner(compose.activity,owner)
        try { block(owner) }
        finally {
            if (previous == null) secure.clearCloudEnrollment() else secure.saveCloudEnrollment(previous)
        }
    }

    private fun waitFor(label: String) {
        compose.waitUntil(12_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun addingAfterADeclinedRequestReusesItsIdentityAndOpensTheNewLocalPlan() = withOwner { owner ->
        val context = compose.activity
        val cache = GreenhousePlanCache(context, owner)
        val original = AppPreferences()
        cache.rememberPreviousInstallation(original, false)
        val primary = cache.canonicalId(original.activeGreenhouse, null)
        val state = mutableStateOf(original)
        val slots = AtomicInteger(0)
        val requests = CopyOnWriteArrayList<String>()
        val created = AtomicInteger()
        val service = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val houses = JSONArray().put(JSONObject().put("id", primary).put("free", true)
                .put("name","Main Greenhouse").put("active",true))
            var code = 200
            if (req.url.encodedPath.endsWith("/activate")) {
                val house = JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8())
                requests.add(house.getString("houseId"))
                if (slots.get() == 0) code = 409
                else houses.put(JSONObject().put("id",house.getString("houseId")).put("name",house.getString("name"))
                    .put("active",true).put("free",false))
            }
            val data = if (code == 409) JSONObject().put("error","greenhouse_plan_required")
            else JSONObject().put("houses",houses).put("extraSlots",slots.get()).put("maxExtraSlots",5)
                .put("revision",requests.size).put("checkoutAvailable",false)
                .put("paidThroughEpochMs",System.currentTimeMillis()+86_400_000)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(data.toString().toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid",owner.deviceId), {"test"}, client=service)
        compose.activityRule.scenario.onActivity { it.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GreenhouseManager(state.value, emptyList(), true, { update -> state.value=update(state.value) },
                    {}, { created.incrementAndGet() }, owner, api)
            }
        } }
        waitFor("One greenhouse included")
        compose.onNodeWithText("Add greenhouse").performClick()
        waitFor("Greenhouse subscriptions are unavailable for this account.")
        compose.runOnIdle { assertEquals(1, state.value.greenhouses.size) }
        slots.set(1)
        compose.onNodeWithText("Add greenhouse").performClick()
        compose.waitUntil(12_000) { created.get() == 1 }
        assertEquals(2, requests.size)
        assertEquals(requests.first(), requests.last())
        compose.runOnIdle {
            assertEquals(requests.first(),state.value.activeGreenhouse.id)
            assertTrue(state.value.activeGreenhouse.isMonitoring())
            assertEquals(2,state.value.greenhouses.size)
        }
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        waitFor("Pause Greenhouse 2?")
        compose.onNodeWithText("Keep monitoring").performClick()
        compose.runOnIdle { assertTrue(state.value.activeGreenhouse.isMonitoring()) }
    }

    @Test fun aSecondStandalonePhoneExplicitlySelectsTheExistingGreenhouseBeforeCollecting() = withOwner { owner ->
        val cache = GreenhousePlanCache(compose.activity,owner)
        val original=AppPreferences()
        cache.rememberPreviousInstallation(original,false)
        assertTrue(cache.applyTo(original).activeGreenhouse.awaitingPlanCheck)
        assertFalse(cache.applyTo(original).activeGreenhouse.isMonitoring())
        val ui=mutableStateOf(cache.applyTo(original))
        val rows=JSONArray().put(JSONObject().put("id","existing").put("name","North house").put("active",true).put("free",true))
        val data=JSONObject().put("houses",rows).put("extraSlots",0).put("maxExtraSlots",5).put("revision",1)
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertEquals("GET",request.method)
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(data.toString().toResponseBody()).build()
        }.build()
        val api=AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid",owner.deviceId),{"test"},client=client)
        compose.activityRule.scenario.onActivity { it.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GreenhouseManager(ui.value,emptyList(),true,{ update -> ui.value=update(ui.value) },{},{},owner,api)
            }
        } }
        waitFor("Use here")
        compose.runOnIdle { assertFalse("Second phone must choose a house: ${ui.value.activeGreenhouse}",ui.value.activeGreenhouse.isMonitoring()) }
        compose.onNodeWithText("Use here").performScrollTo().performClick()
        compose.onNodeWithText("Use this greenhouse").performClick()
        compose.waitUntil(12_000) { ui.value.activeGreenhouse.billingHouseId=="existing" }
        compose.runOnIdle {
            assertTrue(ui.value.activeGreenhouse.isMonitoring())
            assertFalse(ui.value.activeGreenhouse.awaitingPlanCheck)
            assertEquals("North house",ui.value.activeGreenhouse.name)
        }
    }

    @Test fun serverInvitationUsesTheSelectedExistingFreeOrPaidHouse() = withOwner { owner ->
        val context = compose.activity
        val store = AppPreferencesStore(context,"_standalone")
        val saved = store.load()
        val first = GreenhouseConfig(billingHouseId="south", name="South")
        val second = GreenhouseConfig(id="north", billingHouseId="north",name="North")
        val registrations = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val data=if (request.url.encodedPath.endsWith("/invitations")) {
                val payload=JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                registrations += payload.getString("planHouseId")
                JSONObject().put("displayCode","fixture").put("invitationId","invite")
                    .put("greenhouseId",payload.getString("greenhouseId")).put("greenhouseName",payload.getString("greenhouseName"))
                    .put("expiresAt",System.currentTimeMillis()/1000+120)
            } else {
                assertEquals("/v1/greenhouse-plan",request.url.encodedPath)
                val houses = JSONArray()
                for (house in listOf(first,second)) houses.put(JSONObject().put("id",house.billingHouseId)
                    .put("name",house.name).put("free",house == first).put("active",true))
                JSONObject().put("houses",houses).put("maxExtraSlots",5).put("extraSlots",1)
                    .put("paidThroughEpochMs",System.currentTimeMillis()+86_400_000)
            }
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(data.toString().toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid",owner.deviceId),{"test"},client=client)
        try {
            kotlinx.coroutines.runBlocking {
                for (local in listOf(first,second)) {
                    store.save(AppPreferences(greenhouses=listOf(first,second),activeGreenhouseId=local.id))
                    val selected=checkNotNull(selectedStandalonePlanHouse(context,owner,api))
                    api.createServerInvitation("physical-"+local.id,selected.name,planHouseId=selected.id)
                }
            }
            assertEquals(listOf("south","north"),registrations.toList())
        } finally { store.restore(saved) }
    }

    @Test fun dashboardShowsReadOnlyHistoryWhenPausedAndReopensAfterVerifiedRenewal() = withOwner { owner ->
        val cache = GreenhousePlanCache(compose.activity,owner)
        val prefs = AppPreferences(greenhouses=listOf(GreenhouseConfig(),GreenhouseConfig(id="annex",name="Annex",billingHouseId="annex")),activeGreenhouseId="annex")
        val ended = GreenhousePlanState(listOf(PlanHouse("annex","Annex",false,false)),0,null,
            0,"test",true,true,5,revision=1)
        cache.record(ended)
        val ui = mutableStateOf(GreenhouseUiState(preferences=cache.applyTo(prefs),standalone=true))
        val screen = mutableStateOf(AppScreen.Dashboard)
        compose.activityRule.scenario.onActivity { it.setContent {
            DashboardScreen(ui.value,DeviceStatus(90,false,true),java.time.LocalDateTime.now(),{ target -> screen.value=target })
        } }
        waitFor("Monitoring paused")
        compose.onNodeWithText("View history").performClick()
        compose.runOnIdle { assertEquals(AppScreen.History,screen.value) }
        cache.record(ended.copy(houses=listOf(ended.houses.single().copy(active=true)),extraSlots=1,
            paidThroughEpochMs=System.currentTimeMillis()+86_400_000,revision=2))
        compose.runOnIdle { ui.value=ui.value.copy(preferences=cache.applyTo(prefs)) }
        waitFor("Avg Temp")
        compose.onNodeWithText("Monitoring paused").assertDoesNotExist()
    }

    @Test fun pausedAndExpiredHousesKeepTheirLayoutAndIgnoreOlderOrOtherAccountsAccess() = withOwner { owner ->
        val context = compose.activity
        val cache = GreenhousePlanCache(context, owner)
        val original = AppPreferences(greenhouses=listOf(GreenhouseConfig(),
            GreenhouseConfig(id="annex",name="Annex",billingHouseId="annex",
                sensorPositions=mapOf("bench" to SensorPosition(2.0,3.0,heightFeet=4.0)))),
            sensorGreenhouseIds=mapOf("bench" to "annex"))
        cache.rememberPreviousInstallation(original,false)
        val cutoff = System.currentTimeMillis()+60_000
        val primary = cache.canonicalId(original.activeGreenhouse,null)
        val plan = GreenhousePlanState(listOf(PlanHouse(primary,"Main",true,true),
            PlanHouse("annex","Annex",true,false,pauseAtEpochMs=cutoff)),1,"greenhouse_extra_1",
            cutoff,"binding",true,true,5, nextExtraSlots=0, revision=2)
        cache.record(plan)
        val local = cache.applyTo(original)
        assertEquals(cutoff,local.greenhouses[1].scheduledPauseAtEpochMs)
        assertTrue(local.greenhouses[1].isMonitoring(cutoff-1))
        assertFalse(local.greenhouses[1].isMonitoring(cutoff))
        assertTrue(local.greenhouses[0].isMonitoring(cutoff+1))
        val duplicate=original.greenhouses[1].copy(id="different-floor")
        assertFalse(cache.applyTo(original.copy(greenhouses=original.greenhouses+duplicate)).greenhouses.last().isMonitoring())
        cache.record(plan.copy(revision=3, houses=listOf(plan.houses.first(),
            plan.houses.last().copy(active=false,pauseAtEpochMs=0))))
        cache.record(plan)
        val paused = cache.applyTo(original)
        assertFalse(paused.greenhouses[1].isMonitoring())
        assertEquals(original.greenhouses[1].sensorPositions,paused.greenhouses[1].sensorPositions)
        assertEquals(original.sensorGreenhouseIds,paused.sensorGreenhouseIds)
        val secure = SecureSettingsStore(context)
        val stranger = owner.copy(tenantId="other-"+UUID.randomUUID())
        secure.saveCloudEnrollment(stranger)
        cache.record(plan.copy(revision=4))
        assertFalse(cache.applyTo(original).greenhouses[1].isMonitoring())
        val strangerCache=GreenhousePlanCache(context,stranger)
        assertNotEquals(primary,strangerCache.canonicalId(original.activeGreenhouse,null))
        assertFalse(strangerCache.applyTo(original).greenhouses[1].isMonitoring())
    }
}
