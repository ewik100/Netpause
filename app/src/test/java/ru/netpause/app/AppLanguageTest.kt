package ru.netpause.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun russianIsDefault() {
        assertEquals(AppLanguage.RU, AppLanguage.normalize(null))
        assertEquals("Главный экран", AppLanguage.translate("Главный экран", null))
    }
    @Test fun englishTranslatesKnownLabels() {
        assertEquals("Home", AppLanguage.translate("Главный экран", AppLanguage.EN))
        assertEquals("BLOCKING ACTIVE", AppLanguage.translate("БЛОКИРОВКА АКТИВНА", AppLanguage.EN))
    }
    @Test fun userContentAndUnknownStringsArePreserved() {
        assertEquals("My custom task", AppLanguage.translate("My custom task", AppLanguage.EN))
        assertEquals("Моя задача", AppLanguage.translate("Моя задача", AppLanguage.EN))
    }
    @Test fun languageToggleIsDeterministic() {
        assertEquals(AppLanguage.EN, AppLanguage.toggle(AppLanguage.RU))
        assertEquals(AppLanguage.RU, AppLanguage.toggle(AppLanguage.EN))
        assertEquals(AppLanguage.EN, AppLanguage.toggle(null))
    }
}
