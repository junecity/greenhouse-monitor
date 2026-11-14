package studio.junecity.greenhouse.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val states = ("Alabama|Alaska|Arizona|Arkansas|California|Colorado|Connecticut|Delaware|District of Columbia|Florida|Georgia|" +
    "Hawaii|Idaho|Illinois|Indiana|Iowa|Kansas|Kentucky|Louisiana|Maine|Maryland|Massachusetts|Michigan|Minnesota|Mississippi|" +
    "Missouri|Montana|Nebraska|Nevada|New Hampshire|New Jersey|New Mexico|New York|North Carolina|North Dakota|Ohio|Oklahoma|" +
    "Oregon|Pennsylvania|Rhode Island|South Carolina|South Dakota|Tennessee|Texas|Utah|Vermont|Virginia|Washington|West Virginia|" +
    "Wisconsin|Wyoming").split('|').zip(("AL AK AZ AR CA CO CT DE DC FL GA HI ID IL IN IA KS KY LA ME MD MA MI MN MS " +
    "MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY").split(' ')).toMap()
internal fun usStateCode(name: String): String? = states[name] ?: name.takeIf { it in states.values }

internal data class FuelPrice(val dollars: Double, val area: String, val period: String)
internal fun EnergyUnit.priceUrl() = when (this) {
    EnergyUnit.Wood -> "https://www.eia.gov/state/seds/sep_prices/total/csv/pr_all.csv"
    EnergyUnit.Kwh -> "https://www.eia.gov/electricity/monthly/epm_table_grapher.php?t=epmt_5_6_a"
    EnergyUnit.Therm -> "https://www.eia.gov/dnav/ng/ng_pri_sum_a_EPG0_PRS_DMcf_m.htm"
    EnergyUnit.Propane -> "https://www.eia.gov/dnav/pet/pet_pri_wfr_a_EPLLPA_PRS_dpgal_w.htm"
}

internal fun publicEnergyData(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "GreenhouseStudio/2.19 (greenhouse weather and energy estimates)")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}

// Each table contains residential retail prices. Read the published observation period from the same column.
internal fun parsePrices(fuel: EnergyUnit, text: String): Map<String, FuelPrice> {
    val result = mutableMapOf<String, FuelPrice>()
    fun area(label: String) = if (label in setOf("U.S.", "U.S. Total", "US")) "US" else usStateCode(label)
    fun record(region: String?, raw: String?, period: String, factor: Double = 1.0) {
        val price = raw?.trim()?.toDoubleOrNull() ?: return
        if (region != null && price.isFinite() && price > 0) result[region] = FuelPrice(price * factor, region, period)
    }
    when (fuel) {
        EnergyUnit.Wood -> {
            val lines = text.lineSequence()
            val header = lines.first().split(',')
            require(header.take(3) == listOf("Data_Status","State","MSN"))
            for (row in lines.drop(1).filter { ",WDRCD," in it }) {
                val columns = row.split(',')
                val col = (3 until minOf(header.size, columns.size)).lastOrNull { columns[it].toDoubleOrNull()?.let { n -> n > 0 } == true } ?: continue
                record(area(columns[1]), columns[col], header[col].trim())
            }
        }
        EnergyUnit.Kwh -> {
            val month = Regex("""by State, ([A-Za-z]+ \d{4}) and \d{4} \(Cents per Kilowatthour\)""").find(text)?.groupValues?.get(1) ?: return emptyMap()
            val period = YearMonth.parse(month, DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)).toString()
            if (!text.contains(">Residential</th>")) return emptyMap()
            val cells = Regex("""<td\b[^>]*>\s*([^<>]+?)\s*</td>""")
            for (row in text.split("</tr>")) {
                val values = cells.findAll(row).map { it.groupValues[1].trim() }.toList()
                if (values.size >= 3) record(area(values[0]), values[1], period, .01)
            }
        }
        EnergyUnit.Propane, EnergyUnit.Therm -> {
            val dates = Regex("""<th class="Series\d+">([^<]+)</th>""").findAll(text).map { it.groupValues[1].trim() }.toList()
            val cells = Regex("""class="(?:DataB|Current2)">([^<]*)</td>""")
            for (row in text.split("class=\"DataRow\"").drop(1)) {
                val label = Regex("""class="DataStub1">(.*?)</td>""").find(row)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")?.trim() ?: continue
                val values = cells.findAll(row).map { it.groupValues[1].trim() }.toList()
                if (values.size != dates.size) continue
                val col = values.indices.lastOrNull { values[it].toDoubleOrNull()?.let { n -> n > 0 } == true } ?: continue
                if (fuel == EnergyUnit.Propane) {
                    val date = LocalDate.parse(dates[col], DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US))
                    record(area(label), values[col], date.toString())
                } else {
                    val month = YearMonth.parse(dates[col], DateTimeFormatter.ofPattern("MMM-yy", Locale.US))
                    // EIA quotes $/Mcf. A typical Mcf delivers approximately 10.37 therms.
                    record(area(label), values[col], month.toString(), 1 / 10.37)
                }
            }
        }
    }
    return result
}

internal class FuelPrices(context: Context) {
    private val app = context.applicationContext
    private val cache = app.getSharedPreferences("public_fuel_quotes", Context.MODE_PRIVATE)
    private fun decode(table: JSONObject, state: String?): FuelPrice? {
        val area = state?.takeIf(table::has) ?: "US"
        val row = table.optJSONArray(area) ?: return null
        return FuelPrice(row.getDouble(0), area, row.getString(1))
    }
    fun cached(fuel: EnergyUnit, state: String?): FuelPrice? {
        val raw = cache.getString(fuel.name, null)
        val table = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: bundled.getJSONObject(fuel.name)
        return decode(table, state)
    }
    private val bundled by lazy { app.assets.open("fuel-prices.json").bufferedReader().use { JSONObject(it.readText()) } }

    suspend fun refresh(fuel: EnergyUnit, state: String?): FuelPrice? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - cache.getLong(fuel.name + ".checked", 0) !in 0 until 24 * HEATING_HOUR) {
            val table = runCatching { parsePrices(fuel, publicEnergyData(fuel.priceUrl())) }.getOrNull()
            if (table != null && table.containsKey("US") && table.size > 1) {
                val json = JSONObject()
                table.forEach { (code, quote) -> json.put(code, org.json.JSONArray().put(quote.dollars).put(quote.period)) }
                cache.edit().putString(fuel.name, json.toString()).putLong(fuel.name + ".checked", now).apply()
            }
        }
        cached(fuel, state)
    }
}
