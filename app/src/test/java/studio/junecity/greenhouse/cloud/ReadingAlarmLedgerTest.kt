package studio.junecity.greenhouse.cloud

import org.junit.Assert.*
import org.junit.Test

class ReadingAlarmLedgerTest {
    private val now = 1_800_000_000_000L

    private fun obs(id: String, temp: Double, t: Long = now) =
        TemperatureObservation(id, "Bench", temp, 45.0, 82.0, t)
    private fun batch(vararg obs: TemperatureObservation, at: Long = now) =
        listOf(ServerTemperatures("house", "server", now - 86_400_000, at, obs.toList()))
    private fun cloud(id: String, sensor: String?, time: Long = now) =
        MonitoringAlert(id, "house", MonitoringAlertType.CriticalTemperature, MonitoringAlertPriority.Critical,
            "Bench measured 83°F, above the high limit of 82°F.", time, true, "server", sensor)
    private fun ReadingAlarmLedger.run(readings: List<ServerTemperatures> = emptyList(),
        remote: List<MonitoringAlert> = emptyList(), live: List<MonitoringAlert> = emptyList(),
        stopped: Set<String> = emptySet()) = update(readings, remote, live, stopped, now)

    @Test fun continuingBreachStaysLatchedAndAnotherSensorWithSameNameRings() {
        val ledger = ReadingAlarmLedger(null)
        val first = ledger.run(batch(obs("a", 82.0))).deliver.single()
        assertEquals("a", first.sensorId)
        assertTrue(first.message.contains("at the high limit"))
        assertTrue(ledger.remoteIds(setOf(first.id)).isEmpty())
        // Phone stop removes first from the live list. Reading state survives relaunch.
        val restored = ReadingAlarmLedger(ledger.encode())
        val next = restored.run(batch(obs("a", 88.0, now + 1_000), obs("b", 85.0, now + 1_000), at = now + 1_000))
            .deliver.single()
        assertEquals("b", next.sensorId)
        assertTrue(restored.run(batch(obs("a", 70.0, now - 1_000), at = now + 2_000)).deliver.isEmpty())
        assertTrue(restored.run(batch(obs("a", 70.0, now + 2_000), at = now + 2_000)).deliver.isEmpty())
        val repeat = restored.run(batch(obs("a", 84.0, now + 3_000), at = now + 3_000)).deliver.single()
        assertEquals("a", repeat.sensorId)
        assertNotEquals(first.id, repeat.id)
    }

    @Test fun delayedCloudAlertCanBeStoppedWithoutSoundingTwice() {
        val ledger = ReadingAlarmLedger(null)
        val first = ledger.run(batch(obs("a", 83.0))).deliver.single()
        val alert = cloud("alert-1", "a")
        assertTrue(ledger.run(remote = listOf(alert), live = listOf(first)).deliver.isEmpty())
        assertEquals(setOf(alert.id), ledger.remoteIds(setOf(first.id)))
        assertEquals(setOf(alert.id), ledger.run(remote = listOf(alert)).acknowledge)
        assertTrue(ledger.run(remote = listOf(alert), stopped = setOf(alert.id)).acknowledge.isEmpty())
    }

    @Test fun cloudFirstOrLegacyAggregateCannotMaskOtherSources() {
        val ledger = ReadingAlarmLedger(null)
        val aggregate = cloud("aggregate", null)
        val result = ledger.run(batch(obs("a", 84.0), obs("b", 87.0)), listOf(aggregate), listOf(aggregate))
        assertEquals(listOf("b"), result.deliver.filter { it.id != aggregate.id }.map { it.sensorId })
        assertTrue(result.acknowledge.isEmpty())
        val perSensor = cloud("sensor-a", "a")
        val aliases = ledger.run(remote = listOf(perSensor), live = listOf(aggregate))
        assertTrue(aliases.deliver.none { it.id == perSensor.id })
        assertEquals(setOf(aggregate.id, perSensor.id), ledger.remoteIds(setOf(aggregate.id)))
    }

    @Test fun verifiedCloudRecoveryRearmsForSubsequentReadingAndRejectsEarlierSnapshots() {
        val ledger = ReadingAlarmLedger(null)
        val first = ledger.run(batch(obs("a", 82.0))).deliver.single()
        val recovered = cloud("c", "a").copy(resolvedAtEpochMs = now + 1_000)
        assertEquals(first.id, ledger.run(remote = listOf(recovered), live = listOf(first)).deliver.single().id)
        assertTrue(ledger.run(batch(obs("a", 84.0, now + 500), at = now + 1_500)).deliver.isEmpty())
        val next = ledger.run(batch(obs("a", 89.0, now + 3_000), at = now + 3_000)).deliver.single()
        assertNotEquals(first.id, next.id)
    }
}
