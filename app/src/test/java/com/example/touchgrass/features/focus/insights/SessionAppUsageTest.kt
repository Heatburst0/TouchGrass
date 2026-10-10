package com.example.touchgrass.features.focus.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAppUsageTest {
    @Test
    fun roundTrip() {
        val usage = SessionAppUsage(
            apps = mapOf("com.reader" to 41),
            offTask = mapOf("com.video" to 12)
        )

        assertEquals(usage, SessionAppUsage.fromJson(usage.toJson()))
    }

    @Test
    fun parsesLaptopShapeAndTruncatesFloatSeconds() {
        val json = """
            {
              "apps": {"com.editor": 12.9},
              "offTask": {"com.social": 7.8},
              "allowed": ["com.editor"]
            }
        """.trimIndent()

        assertEquals(
            SessionAppUsage(
                apps = mapOf("com.editor" to 12),
                offTask = mapOf("com.social" to 7)
            ),
            SessionAppUsage.fromJson(json)
        )
    }

    @Test
    fun malformedAndBlankReturnEmpty() {
        assertSame(SessionAppUsage.EMPTY, SessionAppUsage.fromJson(""))
        assertSame(SessionAppUsage.EMPTY, SessionAppUsage.fromJson("{broken"))
    }

    @Test
    fun invalidValuesAreSkipped() {
        val usage = SessionAppUsage.fromJson(
            """{
                "apps": {
                    "negative": -2,
                    "zero": 0,
                    "text": "12",
                    "nested": {"seconds": 4},
                    "null": null,
                    "good": 3
                },
                "offTask": []
            }""".trimIndent()
        )

        assertEquals(mapOf("good" to 3), usage.apps)
        assertTrue(usage.offTask.isEmpty())
    }

    @Test
    fun totalsAndEmptyState() {
        val usage = SessionAppUsage(
            apps = mapOf("one" to 2, "two" to 3),
            offTask = mapOf("three" to 7)
        )

        assertEquals(5, usage.appsSec)
        assertEquals(7, usage.offTaskSec)
        assertTrue(SessionAppUsage.EMPTY.isEmpty)
    }
}
