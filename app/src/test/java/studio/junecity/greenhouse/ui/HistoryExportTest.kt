package studio.junecity.greenhouse.ui

import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.model.HistoryReading

class HistoryExportTest {
    @Test fun historyExportScopesRowsAndPreservesQuotedSensorLabels() {
        val writer = StringWriter()
        val records = listOf(HistoryReading("primary", "South", 63.0, 82.0, 1234),
            HistoryReading("bench", "=Bench, \"north\"", 59.0, 88.0, 2000, "Dark"))
        val count = writeHistoryCsv(writer, mapOf("bench" to "annex"), "annex", records::forEach)
        assertEquals(1, count)
        assertTrue(writer.toString().contains("1970-01-01T00:00:02Z,\"bench\",\"'=Bench, \"\"north\"\"\",59.0,88.0,\"Dark\""))
        assertFalse(writer.toString().contains("South"))
    }
}
