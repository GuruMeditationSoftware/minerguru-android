package app.minerguru

import app.minerguru.model.UpdateCheck
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckTests {
    private fun json(tag: String, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":false,"prerelease":$prerelease}"""

    @Test fun readsTheVersionOfARelease() {
        assertEquals("1.6.0", UpdateCheck.versionFromResponse(json("v1.6.0")))
        assertEquals("2.0", UpdateCheck.versionFromResponse(json("2.0")))
    }

    @Test fun ignoresPrereleasesAndOtherTags() {
        assertNull(UpdateCheck.versionFromResponse(json("v2.0.0", prerelease = true)))
        assertNull(UpdateCheck.versionFromResponse(json("v2.0.0-beta")))
        assertNull(UpdateCheck.versionFromResponse(json("nightly")))
        assertNull(UpdateCheck.versionFromResponse("not json"))
    }

    @Test fun comparesNumbersNotText() {
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateCheck.isNewer("1.5.1", "1.5.0"))
        assertTrue(UpdateCheck.isNewer("1.6", "1.5.9"))
        assertFalse(UpdateCheck.isNewer("1.5.0", "1.5.0"))
        assertFalse(UpdateCheck.isNewer("1.5", "1.5.0"))
        assertFalse(UpdateCheck.isNewer("1.4.9", "1.5.0"))
    }
}
