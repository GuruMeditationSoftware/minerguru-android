package app.minerguru

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalAssetsTest {
    @Test fun licenseInTheAppMatchesTheRepository() {
        // Gradle runs unit tests from the module folder.
        assertEquals(File("../LICENSE.txt").readText(), File("src/main/assets/LICENSE.txt").readText())
    }

    @Test fun noticesCarryTheApacheLicense() {
        assertTrue(File("src/main/assets/THIRD-PARTY-NOTICES.txt").readText().contains("Apache License"))
    }
}
