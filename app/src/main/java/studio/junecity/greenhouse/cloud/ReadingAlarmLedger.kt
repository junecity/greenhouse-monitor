package studio.junecity.greenhouse.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.math.BigDecimal

/** Joins a phone-observed threshold excursion to a cloud incident, irrespective of arrival order. */
internal class ReadingAlarmLedger(encoded: String?) {
    private val data = encoded?.let(::JSONObject) ?: JSONObject()
    private val markers = section("markers")
    private val episodes = section("episodes")

    private fun section(key: String): JSONObject = data.optJSONObject(key) ?: JSONObject().also { data.put(key, it) }
    fun encode() = data.toString()

    data class Update(val deliver: List<MonitoringAlert>, val acknowledge: Set<String>)

    fun update(
        batches: List<ServerTemperatures>, remote: List<MonitoringAlert>, live: List<MonitoringAlert>,
        stopped: Set<String>, now: Long
    ): Update {
        val raised = mutableListOf<MonitoringAlert>()
        val acks = mutableSetOf<String>()
        val liveIds = live.mapTo(mutableSetOf()) { it.id }
        val knownCloud = (remote + live.filterNot { isLocal(it.id) }).distinctBy { it.id }
        for (batch in batches) for (obs in batch.observations) {
            val key = JSONArray(listOf(batch.house, batch.server, batch.generation, obs.sensorId)).toString()
            val marker = markers.optJSONObject(key) ?: JSONObject()
            val last = marker.optLong("last")
            if (obs.measuredAtEpochMs < last ||
                (obs.measuredAtEpochMs == last && batch.evaluatedAt <= marker.optLong("evaluated"))) continue
            val direction = TemperatureBreach.Direction.forReading(obs.temperatureF, obs.lowF, obs.highF)
            val old = marker.optString("episode")
            val previous = episodes.optJSONObject(old)
            marker.put("last", obs.measuredAtEpochMs).put("evaluated", batch.evaluatedAt)
            markers.put(key, marker)
            if (previous != null && !previous.has("end") && previous.optString("direction") == direction?.name) continue
            previous?.put("end", batch.evaluatedAt)
            marker.remove("episode")
            if (direction == null) continue
            val episode = JSONObject().put("house", batch.house).put("server", batch.server).put("sensor", obs.sensorId)
                .put("name", obs.sensorName).put("direction", direction.name)
                .put("after", if (last == 0L) batch.generation else last).put("since", obs.measuredAtEpochMs)
                .put("aliases", JSONArray())
            // If a network-only alert was already displayed, retain its notification and Stop target.
            val cloud = knownCloud.firstOrNull { alert ->
                alert.resolvedAtEpochMs == null && matches(episode, alert) && linkedEpisode(alert.id) == null
            }
            val id = cloud?.id ?: "reading/${UUID.randomUUID()}"
            episodes.put(id, episode)
            marker.put("episode", id)
            if (cloud != null) {
                episode.getJSONArray("aliases").put(id)
                if (cloud.sensorId != null) episode.put("sensorAlias", id)
            }
            val limit = if (direction == TemperatureBreach.Direction.LOW) obs.lowF else obs.highF
            val comparison = when {
                obs.temperatureF == limit -> "at"
                direction == TemperatureBreach.Direction.LOW -> "below"
                else -> "above"
            }
            val message = "${obs.sensorName} measured ${format(obs.temperatureF)}°F, $comparison the ${direction.name.lowercase()} limit of ${format(limit)}°F."
            if (id !in stopped && id !in liveIds) {
                raised += MonitoringAlert(id, batch.house, MonitoringAlertType.CriticalTemperature, MonitoringAlertPriority.Critical,
                    message, obs.measuredAtEpochMs, true, batch.server, obs.sensorId)
                liveIds += id
            }
        }
        for (alert in remote) {
            val id = linkedEpisode(alert.id) ?: episodes.keys().asSequence()
                .filter { matches(episodes.getJSONObject(it), alert) }
                .maxByOrNull { episodes.getJSONObject(it).getLong("since") }
            if (id == null) {
                if (alert.id !in stopped) raised += alert
                continue
            }
            val episode = episodes.getJSONObject(id)
            val aliases = episode.getJSONArray("aliases")
            if (alert.id !in aliases.strings()) aliases.put(alert.id)
            if (alert.sensorId != null) episode.put("sensorAlias", alert.id)
            alert.resolvedAtEpochMs?.let { end ->
                episode.put("end", end)
                // A recovery may arrive before this phone sees a safe measurement.
                markers.keys().forEach { key ->
                    val marker = markers.getJSONObject(key)
                    if (marker.optString("episode") == id) {
                        marker.remove("episode")
                        marker.put("last", maxOf(marker.optLong("last"), end))
                        marker.put("evaluated", maxOf(marker.optLong("evaluated"), end))
                    }
                }
            }
            if (id !in liveIds || id in stopped) {
                if (alert.id !in stopped) acks += alert.id
            } else if (id == alert.id || alert.resolvedAtEpochMs != null) {
                raised += alert.copy(id = id, sensorId = episode.getString("sensor"))
            }
        }
        // Keep the active watermark through stale/missing data. Retain recent recoveries for delayed cloud delivery.
        episodes.keys().asSequence().toList().forEach { id ->
            val end = episodes.getJSONObject(id).optLong("end")
            if (end > 0 && end < now - 86_400_000L && id !in liveIds) episodes.remove(id)
        }
        markers.keys().asSequence().toList().forEach { key ->
            val marker = markers.getJSONObject(key)
            if (!marker.has("episode") && marker.optLong("evaluated") < now - 86_400_000L) markers.remove(key)
        }
        return Update(raised, acks)
    }

    fun remoteIds(ids: Set<String>): Set<String> = buildSet {
        for (id in ids) {
            if (!isLocal(id)) add(id)
            episodes.optJSONObject(id)?.getJSONArray("aliases")?.let { addAll(it.strings()) }
        }
    }

    private fun linkedEpisode(id: String) = episodes.keys().asSequence().firstOrNull {
        id in episodes.getJSONObject(it).getJSONArray("aliases").strings()
    }

    private fun matches(episode: JSONObject, cloud: MonitoringAlert): Boolean {
        if (cloud.greenhouseId != episode.getString("house") || cloud.serverId != episode.getString("server")) return false
        if (cloud.sensorId != null) {
            if (cloud.sensorId != episode.getString("sensor") ||
                (episode.has("sensorAlias") && episode.optString("sensorAlias") != cloud.id)) return false
        } else if (!cloud.message.startsWith(episode.getString("name").trim().replace(Regex("\\s+"), " ") + " measured ")) return false
        // Cloud timestamps are rounded to seconds, and the initial local reading may be received well into an excursion.
        if (cloud.occurredAtEpochMs + 999 < episode.getLong("after")) return false
        if (episode.has("end") && cloud.occurredAtEpochMs > episode.getLong("end")) return false
        return if (episode.getString("direction") == "LOW") cloud.message.contains("the low limit")
            else cloud.message.contains("the high limit")
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun format(number: Double) = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()

    companion object { fun isLocal(id: String) = id.startsWith("reading/") }
}
