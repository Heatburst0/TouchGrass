package com.example.touchgrass.features.reading.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LocalQuizGeneratorTest {

    private val page = """
        The lighthouse keeper climbed the spiral staircase every evening before sunset.
        His daughter Margaret polished the enormous brass lantern until it gleamed brightly.
        During the winter storms, fishermen depended on the beacon to avoid the jagged rocks.
        One night the generator failed, and Margaret lit seventeen candles around the lens.
        By morning the harbor master arrived with a replacement engine from the mainland.
        The villagers later named their schooner after the brave young woman.
    """.trimIndent()

    @Test
    fun `builds the requested number of questions from page text`() {
        val quiz = LocalQuizGenerator.generate(page, 3, Random(1))
        assertEquals(3, quiz.size)
    }

    @Test
    fun `each question blanks a word from the text and offers it among four options`() {
        LocalQuizGenerator.generate(page, 3, Random(2)).forEach { q ->
            assertTrue(q.question.contains(LocalQuizGenerator.BLANK))
            assertEquals(4, q.options.size)
            assertEquals(4, q.options.toSet().size)
            val answer = q.options[q.correctIndex]
            val filled = q.question.substringAfter("\"").substringBeforeLast("\"")
                .replace(LocalQuizGenerator.BLANK, answer)
            assertTrue("'$filled' should appear in the page", page.replace("\n", " ").contains(filled))
        }
    }

    @Test
    fun `questions come from different sentences`() {
        val quiz = LocalQuizGenerator.generate(page, 3, Random(3))
        assertEquals(3, quiz.map { it.question }.toSet().size)
    }

    @Test
    fun `correct answer position varies`() {
        val positions = (1..20).flatMap { seed ->
            LocalQuizGenerator.generate(page, 3, Random(seed)).map { it.correctIndex }
        }.toSet()
        assertTrue(positions.size > 1)
    }

    @Test
    fun `distractors are never the answer itself in another case`() {
        LocalQuizGenerator.generate(page, 3, Random(4)).forEach { q ->
            val answer = q.options[q.correctIndex].lowercase()
            q.options.filterIndexed { i, _ -> i != q.correctIndex }
                .forEach { assertNotEquals(answer, it.lowercase()) }
        }
    }

    @Test(expected = QuizGenerationException::class)
    fun `too little text throws`() {
        LocalQuizGenerator.generate("Short page. Nothing here.", 3, Random(5))
    }
}
