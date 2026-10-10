package com.example.touchgrass.features.focus.insights

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistractionClassifierTest {
    @Test
    fun knownPackagesAreDistractingWithoutCategory() {
        assertTrue(DistractionClassifier.isDistracting("com.google.android.youtube", null))
        assertTrue(DistractionClassifier.isDistracting("com.instagram.android", null))
        assertTrue(DistractionClassifier.isDistracting("com.pinterest", null))
    }

    @Test
    fun distractingCategoriesAreRecognized() {
        listOf(0, 2, 4, 5).forEach { category ->
            assertTrue(DistractionClassifier.isDistracting("example.package", category))
        }
    }

    @Test
    fun nonDistractingCategoriesAreRejected() {
        listOf(1, 3, 6, 7, -1).forEach { category ->
            assertFalse(DistractionClassifier.isDistracting("example.package", category))
        }
    }

    @Test
    fun messagingAndMusicAppsAreNotKnownDistractions() {
        assertFalse(DistractionClassifier.isDistracting("com.whatsapp", null))
        assertFalse(DistractionClassifier.isDistracting("org.telegram.messenger", null))
        assertFalse(DistractionClassifier.isDistracting("org.thoughtcrime.securesms", null))
        assertFalse(
            DistractionClassifier.isDistracting("com.google.android.apps.youtube.music", null)
        )
    }

    @Test
    fun nullCategoryFallsBackToKnownList() {
        assertFalse(DistractionClassifier.isDistracting("com.example.unknown", null))
        assertTrue(DistractionClassifier.isDistracting("com.reddit.frontpage", null))
    }
}
