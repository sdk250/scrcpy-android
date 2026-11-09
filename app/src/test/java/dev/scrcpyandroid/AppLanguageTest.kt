package dev.scrcpyandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun defaultsToEnglish() {
        assertEquals(AppLanguage.EN, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.EN, AppLanguage.fromTag("fr"))
    }

    @Test
    fun parsesSavedTags() {
        assertEquals(AppLanguage.ZH, AppLanguage.fromTag("zh"))
        assertEquals(AppLanguage.EN, AppLanguage.fromTag("en"))
    }
}
