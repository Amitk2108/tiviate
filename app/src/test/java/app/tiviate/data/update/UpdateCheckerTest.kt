package app.tiviate.data.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateCheckerTest {

    @Test
    fun versionsCompareNumerically() {
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.3"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertFalse("pre-release tags are not offered", UpdateChecker.isNewer("0.3.0-beta1", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("nonsense", "0.1.0"))
    }

    @Test
    fun readsTheApkFromTheLatestRelease() {
        val json = """
            {"tag_name":"v0.2.0","name":"Tiviate 0.2.0","body":"What's new\n- Backup","draft":false,"prerelease":false,
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
                       {"name":"Tiviate-0.2.0.apk","browser_download_url":"https://x/Tiviate-0.2.0.apk","size":9500000}]}
        """.trimIndent()
        val r = UpdateChecker.parseLatest(json)!!
        assertEquals("0.2.0", r.version)
        assertEquals("https://x/Tiviate-0.2.0.apk", r.apkUrl)
        assertEquals(9_500_000L, r.apkSize)
        assertEquals("What's new\n- Backup", r.notes)
    }

    @Test
    fun releasesWithoutAnApkOrDraftsAreIgnored() {
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"v0.2.0","assets":[]}"""))
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"v0.2.0","draft":true,"assets":[{"name":"a.apk","browser_download_url":"u","size":1}]}"""))
        assertNull(UpdateChecker.parseLatest("not json"))
    }

    @Test
    fun aDailyCheckAndLaterSkipsThatVersion() {
        val day = 24 * 3_600_000L
        assertTrue(UpdateChecker.dueForCheck(enabled = true, lastCheckAt = 0, nowMs = day))
        assertFalse(UpdateChecker.dueForCheck(enabled = true, lastCheckAt = day, nowMs = day + 3_600_000L))
        assertFalse(UpdateChecker.dueForCheck(enabled = false, lastCheckAt = 0, nowMs = day))
        assertFalse(UpdateChecker.shouldOffer("0.2.0", current = "0.1.0", skipped = "0.2.0"))
        assertTrue(UpdateChecker.shouldOffer("0.3.0", current = "0.1.0", skipped = "0.2.0"))
    }

    @Test
    fun releaseNotesShowAsPlainText() {
        val md = "Made by **Amit** ([@Amitk2108](https://github.com/Amitk2108)).\n\n> **Player only.** No channels.\n\n## Install\n- `adb install`"
        assertEquals("Made by Amit (@Amitk2108).\n\nPlayer only. No channels.\n\nInstall\n- adb install", app.tiviate.ui.components.plainNotes(md))
    }
}
