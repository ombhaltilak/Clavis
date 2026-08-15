package com.example.mhetranslator

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun everydayEnglishTermsAreRestoredWithTheirOriginalSpelling() {
        val source = "Please call me after the office meeting."
        val protected = OfflineTranslationApi.protectEverydayEnglishTerms(source)

        assertFalse(protected.text.contains("Please"))
        assertFalse(protected.text.contains("meeting"))
        assertEquals(source, protected.restore(protected.text))
    }

    @Test
    fun postProcessingDoesNotTurnHindiBusIntoEnglishBus() {
        assertEquals("बस जल्दी आओ और phone रखो", OfflineTranslationApi.preserveEverydayEnglishScript("बस जल्दी आओ और फोन रखो"))
    }
}
