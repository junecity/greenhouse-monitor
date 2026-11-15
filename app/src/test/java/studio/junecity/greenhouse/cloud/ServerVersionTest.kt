package studio.junecity.greenhouse.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerVersionTest {
    @Test fun comparesBuildsAndLegacyNumericVersions() {
        assertEquals(ServerVersion.Newer, compareServerVersion("2.32.0", 75, "2.33.0", 73))
        assertEquals(ServerVersion.Same, compareServerVersion(null, 73, "2.33.0", 73))
        assertEquals(ServerVersion.Older, compareServerVersion("2.9.9", null, "2.33.0", 73))
        assertEquals(ServerVersion.Unknown, compareServerVersion("unknown", null))
        assertEquals(ServerVersion.Unknown, compareServerVersion(null, null))
    }
}
