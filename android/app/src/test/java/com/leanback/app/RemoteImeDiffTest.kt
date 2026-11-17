package com.leanback.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteImeDiffTest {

    @Test
    fun `incremental typing appends only new characters`() {
        assertEquals(TextDiff(0, "h"), TextDiffCalculator.computeDiff("", "h"))
        assertEquals(TextDiff(0, "e"), TextDiffCalculator.computeDiff("h", "he"))
        assertEquals(TextDiff(0, " "), TextDiffCalculator.computeDiff("hello", "hello "))
    }

    @Test
    fun `autocorrect replaces differing suffix cleanly`() {
        assertEquals(TextDiff(1, "lo"), TextDiffCalculator.computeDiff("helo", "hello"))
        assertEquals(TextDiff(4, "WORLD"), TextDiffCalculator.computeDiff("hell", "WORLD"))
    }

    @Test
    fun `deleting text emits accurate backspace count`() {
        assertEquals(TextDiff(2, ""), TextDiffCalculator.computeDiff("hello", "hel"))
        assertEquals(TextDiff(0, ""), TextDiffCalculator.computeDiff("same", "same"))
    }

    @Test
    fun `emoji surrogate pairs are counted as single codepoint`() {
        val emoji = "\uD83D\uDE00" // 😀
        val smileAlt = "\uD83D\uDE04" // 😄 (shares same high surrogate \uD83D!)
        assertEquals(TextDiff(0, emoji), TextDiffCalculator.computeDiff("", emoji))
        assertEquals(TextDiff(1, ""), TextDiffCalculator.computeDiff(emoji, ""))
        // Must back up across high surrogate so 1 full emoji is replaced cleanly.
        assertEquals(TextDiff(1, smileAlt), TextDiffCalculator.computeDiff(emoji, smileAlt))
    }
}
