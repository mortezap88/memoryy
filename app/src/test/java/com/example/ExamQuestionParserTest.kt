package com.example

import com.example.data.ExamQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamQuestionParserTest {

    @Test
    fun `parses lettered markers with multi-line answers`() {
        val raw = """
            Q: What is osmosis?
            A: The movement of water across a
            partially permeable membrane.

            Q: What is diffusion?
            A: Particles spreading from high to low concentration.
        """.trimIndent()

        val pairs = ExamQuestionParser.parse(raw)
        assertEquals(2, pairs.size)
        assertEquals("What is osmosis?", pairs[0].question)
        assertEquals(
            "The movement of water across a\npartially permeable membrane.",
            pairs[0].answer
        )
        assertEquals("What is diffusion?", pairs[1].question)
        assertEquals("Particles spreading from high to low concentration.", pairs[1].answer)
    }

    @Test
    fun `parses numbered questions when answers are labelled`() {
        val raw = """
            1. Name the powerhouse of the cell.
            Answer: The mitochondrion.
            2. What gas do plants absorb?
            Answer: Carbon dioxide.
        """.trimIndent()

        val pairs = ExamQuestionParser.parse(raw)
        assertEquals(2, pairs.size)
        assertEquals("Name the powerhouse of the cell.", pairs[0].question)
        assertEquals("The mitochondrion.", pairs[0].answer)
        assertEquals("What gas do plants absorb?", pairs[1].question)
    }

    @Test
    fun `parses one pair per line`() {
        val raw = """
            Osmosis | Water moving across a membrane
            Diffusion	Particles spreading out
        """.trimIndent()

        val pairs = ExamQuestionParser.parse(raw)
        assertEquals(2, pairs.size)
        assertEquals("Osmosis", pairs[0].question)
        assertEquals("Water moving across a membrane", pairs[0].answer)
        assertEquals("Diffusion", pairs[1].question)
    }

    @Test
    fun `parses blank line separated blocks`() {
        val raw = """
            What is mitosis?
            Cell division producing two identical cells.

            What is meiosis?
            Cell division producing gametes.
        """.trimIndent()

        val pairs = ExamQuestionParser.parse(raw)
        assertEquals(2, pairs.size)
        assertEquals("What is mitosis?", pairs[0].question)
        assertEquals("Cell division producing two identical cells.", pairs[0].answer)
    }

    @Test
    fun `parses a single question and answer on two lines`() {
        val pairs = ExamQuestionParser.parse("What is ATP?\nThe energy currency of the cell.")
        assertEquals(1, pairs.size)
        assertEquals("What is ATP?", pairs[0].question)
        assertEquals("The energy currency of the cell.", pairs[0].answer)
    }

    @Test
    fun `handles windows line endings`() {
        val raw = "Q: A?\r\nA: B.\r\nQ: C?\r\nA: D."
        val pairs = ExamQuestionParser.parse(raw)
        assertEquals(2, pairs.size)
        assertEquals("A?", pairs[0].question)
        assertEquals("D.", pairs[1].answer)
    }

    @Test
    fun `returns nothing for unusable text`() {
        assertTrue(ExamQuestionParser.parse("").isEmpty())
        assertTrue(ExamQuestionParser.parse("   \n\n  ").isEmpty())
        // A single line without a separator cannot form a pair.
        assertTrue(ExamQuestionParser.parse("just a heading").isEmpty())
    }
}
