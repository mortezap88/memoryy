package com.example

import com.example.network.TutorMessageFormatter
import com.example.network.TutorMessageFormatter.FeedbackTone
import com.example.network.TutorMessageFormatter.InlineStyle
import com.example.network.TutorMessageFormatter.MessageSegment
import com.example.network.TutorMessageFormatter.ReplyOrientation
import com.example.network.TutorMessageFormatter.RichBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorMessageFormatterTest {

    @Test
    fun `parses initial evaluation with green and red blocks and explicit English orientation`() {
        val raw = """
            [answer]
            [lang:en]
            [green]
            You accurately used the inverted conditional ==Were it not for== to frame the negotiation stake.
            [/green]
            [red]
            Follow it with **would not have** + past participle (`closed`) instead of simple past.
            [/red]
            [linguistic_correction]
            []
        """.trimIndent()

        val parsed = TutorMessageFormatter.parseMessage(raw)
        assertEquals(ReplyOrientation.ENGLISH_LTR, parsed.orientation)
        assertEquals(2, parsed.segments.size)

        val green = parsed.segments[0] as MessageSegment.EvaluationBlock
        assertEquals(FeedbackTone.GREEN, green.tone)
        val greenParagraph = green.blocks.single() as RichBlock.Paragraph
        assertTrue(greenParagraph.spans.any { it.style == InlineStyle.HIGHLIGHT && it.text == "Were it not for" })

        val red = parsed.segments[1] as MessageSegment.EvaluationBlock
        assertEquals(FeedbackTone.RED, red.tone)
        val redParagraph = red.blocks.single() as RichBlock.Paragraph
        assertTrue(redParagraph.spans.any { it.style == InlineStyle.BOLD && it.text == "would not have" })
        assertTrue(redParagraph.spans.any { it.style == InlineStyle.CODE && it.text == "closed" })

        val plain = TutorMessageFormatter.toPlainSpeakableText(raw)
        assertFalse(plain.contains("[lang:en]"))
        assertFalse(plain.contains("[green]"))
        assertFalse(plain.contains("[red]"))
        assertFalse(plain.contains("=="))
        assertFalse(plain.contains("**"))
        assertTrue(plain.contains("Were it not for"))
    }

    @Test
    fun `parses Persian-oriented reply even when starting with English terms and strips boilerplate prefixes`() {
        val raw = """
            [answer]
            [lang:fa]
            [red]
            "پاسخ کامل: ATP حامل اصلی ==انرژی== در یاخته است و با شکستن پیوند فسفات انرژی آزاد می‌کند."
            [/red]
            [blue]
            برای حفظ: در مقایسه با گلوکز، ATP مثل پول خرد آماده خرج شدن است در حالی که گلوکز حساب پس‌انداز است.
            [/blue]
        """.trimIndent()

        val parsed = TutorMessageFormatter.parseMessage(raw)
        assertEquals(ReplyOrientation.PERSIAN_RTL, parsed.orientation)
        assertEquals(2, parsed.segments.size)

        val red = parsed.segments[0] as MessageSegment.EvaluationBlock
        assertEquals(FeedbackTone.RED, red.tone)

        val plain = TutorMessageFormatter.toPlainSpeakableText(raw)
        assertFalse(plain.contains("[lang:fa]"))
        assertFalse(plain.contains("پاسخ کامل:"))
        assertFalse(plain.contains("برای حفظ:"))
        assertFalse(plain.startsWith("\""))
        assertTrue(plain.startsWith("ATP حامل اصلی انرژی"))
    }

    @Test
    fun `detects majority script orientation automatically when lang tag is omitted`() {
        val persianMajorityStartingWithEnglish =
            "[green]ATP در میتوکندری ساخته می‌شود و منبع مستقیم انرژی فرایندهای سلولی است.[/green]"
        val parsedFa = TutorMessageFormatter.parseMessage(persianMajorityStartingWithEnglish)
        assertEquals(ReplyOrientation.PERSIAN_RTL, parsedFa.orientation)

        val englishMajorityWithPersianWord =
            "[green]You translated «غشا» accurately as ==cell membrane== in an academic register.[/green]"
        val parsedEn = TutorMessageFormatter.parseMessage(englishMajorityWithPersianWord)
        assertEquals(ReplyOrientation.ENGLISH_LTR, parsedEn.orientation)
    }

    @Test
    fun `holds back incomplete secret tags including lang tags during streaming`() {
        assertTrue(TutorMessageFormatter.parseSegments("[ans").isEmpty())
        assertTrue(TutorMessageFormatter.parseSegments("[answer]\n[lan").isEmpty())
        assertTrue(TutorMessageFormatter.parseSegments("[answer]\n[lang:f").isEmpty())
        assertTrue(TutorMessageFormatter.parseSegments("[answer]\n[lang:fa]\n[gre").isEmpty())

        val midGreen = "[answer]\n[lang:en]\n[green]\nYou nailed ==on the off chance==[/gr"
        val midParsed = TutorMessageFormatter.parseMessage(midGreen)
        assertEquals(ReplyOrientation.ENGLISH_LTR, midParsed.orientation)
        assertEquals(1, midParsed.segments.size)
        val block = midParsed.segments.single() as MessageSegment.EvaluationBlock
        assertEquals(FeedbackTone.GREEN, block.tone)
        assertEquals("You nailed on the off chance", TutorMessageFormatter.toPlainSpeakableText(midGreen))
    }

    @Test
    fun `parses freestyle follow-up response with bullets and emphasis without evaluation blocks`() {
        val raw = """
            [answer]
            [lang:en]
            Both expressions introduce a counterfactual condition, but they differ in register:
            - ==Were it not for== works for **present or general** situations.
            - ==Had it not been for== refers strictly to **past** events.
            [linguistic_correction]
            []
        """.trimIndent()

        val parsed = TutorMessageFormatter.parseMessage(raw)
        assertEquals(ReplyOrientation.ENGLISH_LTR, parsed.orientation)
        assertEquals(3, parsed.segments.size)
        assertTrue(parsed.segments.all { it is MessageSegment.FreestyleBlock })
        assertTrue((parsed.segments[0] as MessageSegment.FreestyleBlock).block is RichBlock.Paragraph)
        assertTrue((parsed.segments[1] as MessageSegment.FreestyleBlock).block is RichBlock.BulletItem)
        assertTrue((parsed.segments[2] as MessageSegment.FreestyleBlock).block is RichBlock.BulletItem)
    }
}
