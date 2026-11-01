package com.example.touchgrass.features.reading.quiz

import kotlin.random.Random

/**
 * Offline fill-in-the-blank quiz built straight from OCR'd page text: blank a
 * meaningful word in a sentence, mix it with words from elsewhere on the page.
 * Instant and needs no network — the fallback when the AI is slow or unreachable.
 */
object LocalQuizGenerator {
    const val BLANK = "_____"

    private const val OPTION_COUNT = 4
    private const val MIN_WORD_LEN = 5
    private const val MIN_SENTENCE_LEN = 30
    private const val MAX_SENTENCE_LEN = 240
    private const val MAX_LEN_DIFF = 3

    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
    private val WORD = Regex("[A-Za-z][A-Za-z'-]*[A-Za-z]")
    private val STOPWORDS = setOf(
        "about", "above", "after", "again", "against", "among", "because", "before", "being",
        "below", "between", "could", "during", "every", "first", "might", "never", "other",
        "should", "since", "their", "there", "these", "those", "through", "under", "until",
        "where", "which", "while", "would", "always", "around", "later", "often", "still"
    )

    /** @throws QuizGenerationException when the text is too thin for [count] questions. */
    fun generate(text: String, count: Int, random: Random = Random.Default): List<QuizQuestion> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val sentences = clean.split(SENTENCE_SPLIT)
            .filter { it.length in MIN_SENTENCE_LEN..MAX_SENTENCE_LEN }
        val pool = WORD.findAll(clean).map { it.value }.filter(::isKeyword)
            .distinctBy { it.lowercase() }.toList()

        val questions = sentences.shuffled(random).mapNotNull { question(it, pool, random) }.take(count)
        if (questions.size < count) {
            throw QuizGenerationException("Not enough readable text on these pages for a quiz.")
        }
        return questions
    }

    private fun isKeyword(word: String) = word.length >= MIN_WORD_LEN && word.lowercase() !in STOPWORDS

    private fun question(sentence: String, pool: List<String>, random: Random): QuizQuestion? {
        val inSentence = WORD.findAll(sentence).map { it.value }.filter(::isKeyword).toList()
        if (inSentence.isEmpty()) return null
        val sentenceWords = inSentence.map { it.lowercase() }.toSet()
        // Longer words carry more meaning; pick among the longest few for variety.
        val answer = inSentence.sortedByDescending { it.length }.take(3).random(random)

        // Similar-length words first, shuffled within a length (keys drawn once so the sort is stable).
        val distractors = pool.filter { it.lowercase() !in sentenceWords }
            .filter { kotlin.math.abs(it.length - answer.length) <= MAX_LEN_DIFF }
            .map { it to kotlin.math.abs(it.length - answer.length) + random.nextDouble() }
            .sortedBy { it.second }
            .map { it.first }
            .map { matchCase(it, answer) }
            .distinctBy { it.lowercase() }
            .take(OPTION_COUNT - 1)
        if (distractors.size < OPTION_COUNT - 1) return null

        val options = (distractors + answer).shuffled(random)
        val blanked = sentence.replaceFirst(Regex("\\b${Regex.escape(answer)}\\b"), BLANK)
        return QuizQuestion(
            question = "Fill in the blank: \"$blanked\"",
            options = options,
            correctIndex = options.indexOf(answer)
        )
    }

    /** Give distractors the answer's capitalization so case doesn't give it away. */
    private fun matchCase(word: String, like: String): String =
        if (like.first().isUpperCase()) word.replaceFirstChar { it.uppercase() }
        else word.lowercase()
}
