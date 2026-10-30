package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.backup.FlashDataBackupManager
import com.example.data.AppDatabase
import com.example.data.Flashcard
import com.example.data.FlashcardStatus
import com.example.data.FlashcardTypes
import com.example.data.SpacedRepetitionUtils
import com.example.data.Subject
import com.example.data.SubjectImageAttachment
import com.example.data.SubjectImagesCodec
import com.example.network.AvalAiClient
import com.example.network.ChatMessage
import com.example.network.GeminiRepository
import com.example.notepad.data.model.MediaType
import com.example.notepad.data.repository.NotesRepository
import com.example.notepad.ui.viewmodel.NotepadViewModel
import com.example.settings.AppSettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubjectCentreTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `creates empty subject by name and updates textbook and extra knowledge`() = runBlocking {
        val dao = db.flashcardDao()
        val id = dao.insertSubject(Subject(name = "Cell Biology"))

        val created = dao.getSubjectById(id)!!
        assertEquals("Cell Biology", created.name)
        assertEquals("", created.textbook)
        assertEquals("", created.extraKnowledge)

        dao.updateSubjectTextbook(id, "ATP is synthesized by ATP synthase across the inner mitochondrial membrane.")
        dao.updateSubjectExtraKnowledge(id, "Peter Mitchell proposed the chemiosmotic hypothesis in 1961.")

        val updated = dao.getSubjectById(id)!!
        assertEquals(
            "ATP is synthesized by ATP synthase across the inner mitochondrial membrane.",
            updated.textbook
        )
        assertEquals(
            "Peter Mitchell proposed the chemiosmotic hypothesis in 1961.",
            updated.extraKnowledge
        )
    }

    @Test
    fun `flashdata backup preserves subject textbook, extra knowledge, and dumb or learned card statuses`() = runBlocking {
        val dao = db.flashcardDao()
        val settingsRepo = AppSettingsRepository(context)
        val notesRepo = NotesRepository.getInstance(context)
        val backupManager = FlashDataBackupManager(context, dao, settingsRepo, notesRepo)

        val subjectId = dao.insertSubject(
            Subject(
                name = "Operating Systems",
                textbook = "A page fault occurs when a process accesses a virtual page not mapped in RAM.",
                extraKnowledge = "Demand paging was pioneered on the Atlas computer."
            )
        )
        dao.insertAll(
            listOf(
                Flashcard(
                    type = FlashcardTypes.EXAM,
                    word = "What triggers a page fault?",
                    backText = "Accessing an unmapped virtual page.",
                    subjectId = subjectId,
                    status = FlashcardStatus.LEARNED
                ),
                Flashcard(
                    type = FlashcardTypes.EXAM,
                    word = "What color is RAM?",
                    backText = "Irrelevant.",
                    subjectId = subjectId,
                    status = FlashcardStatus.DUMB
                )
            )
        )

        val out = ByteArrayOutputStream()
        backupManager.exportToStream(out)

        dao.deleteAllCards()
        dao.deleteAllSubjects()
        assertTrue(dao.getAllSubjectsSnapshot().isEmpty())

        backupManager.importFromStream(ByteArrayInputStream(out.toByteArray()))
        val restored = dao.getSubjectById(subjectId)!!
        assertEquals("Operating Systems", restored.name)
        assertEquals(
            "A page fault occurs when a process accesses a virtual page not mapped in RAM.",
            restored.textbook
        )
        assertEquals(
            "Demand paging was pioneered on the Atlas computer.",
            restored.extraKnowledge
        )

        val restoredCards = dao.getCardsBySubjectSnapshot(subjectId)
        assertEquals(2, restoredCards.size)
        assertEquals(
            FlashcardStatus.LEARNED,
            restoredCards.first { it.word == "What triggers a page fault?" }.status
        )
        assertEquals(
            FlashcardStatus.DUMB,
            restoredCards.first { it.word == "What color is RAM?" }.status
        )
    }

    @Test
    fun `dumb and learned cards are excluded from due checks and upcoming review forecast`() {
        val now = 1_700_000_000_000L
        val activeDue = Flashcard(
            id = 1L,
            type = FlashcardTypes.EXAM,
            word = "Active question",
            nextReviewTimeMillis = now - 10_000L,
            status = FlashcardStatus.ACTIVE
        )
        val dumbOverdue = activeDue.copy(
            id = 2L,
            word = "Dumb question",
            status = FlashcardStatus.DUMB
        )
        val learnedOverdue = activeDue.copy(
            id = 3L,
            word = "Learned question",
            stageIndex = 7,
            status = FlashcardStatus.LEARNED
        )

        assertTrue(SpacedRepetitionUtils.isCardDue(activeDue, now))
        assertFalse(SpacedRepetitionUtils.isCardDue(dumbOverdue, now))
        assertFalse(SpacedRepetitionUtils.isCardDue(learnedOverdue, now))

        val forecast = SpacedRepetitionUtils.getUpcomingSixDays(
            listOf(activeDue, dumbOverdue, learnedOverdue)
        )
        assertEquals(1, forecast.sumOf { it.count })

        val inactiveOnlyForecast = SpacedRepetitionUtils.getUpcomingSixDays(
            listOf(dumbOverdue, learnedOverdue)
        )
        assertEquals(0, inactiveOnlyForecast.sumOf { it.count })
    }

    @Test
    fun `avalai payload uses high reasoning effort without artificial 8k max_tokens bottleneck`() {
        val msgs = listOf(AvalAiClient.Message(role = "user", content = "Generate questions"))

        val defaultJson = JSONObject(
            AvalAiClient.buildChatCompletionsPayload(
                model = AppSettingsRepository.HAIKU_TTT_MODEL,
                messages = msgs,
                temperature = 0.7,
                stream = true
            )
        )
        assertEquals(AppSettingsRepository.HAIKU_TTT_MODEL, defaultJson.getString("model"))
        assertEquals("low", defaultJson.getString("reasoning_effort"))

        val highJson = JSONObject(
            AvalAiClient.buildChatCompletionsPayload(
                model = AppSettingsRepository.DEEPSEEK_REASONER_MODEL,
                messages = msgs,
                temperature = null,
                stream = true,
                reasoningEffort = AvalAiClient.HIGH_REASONING_EFFORT,
                maxTokens = null
            )
        )
        assertEquals(AppSettingsRepository.DEEPSEEK_REASONER_MODEL, highJson.getString("model"))
        assertEquals("high", highJson.getString("reasoning_effort"))
        assertFalse(highJson.has("max_tokens"))
        assertFalse(highJson.has("temperature"))
    }

    @Test
    fun `avalai stream helpers extract reasoning_content, finish_reason, and in-stream errors`() {
        val reasoningChunk = """{"choices":[{"delta":{"reasoning_content":"بررسی پاراگراف اول زیست‌شناسی..."},"finish_reason":null}]}"""
        assertEquals("بررسی پاراگراف اول زیست‌شناسی...", AvalAiClient.extractStreamDeltaReasoning(reasoningChunk))
        assertEquals("", AvalAiClient.extractStreamDeltaContent(reasoningChunk))

        val finishChunk = """{"choices":[{"delta":{},"finish_reason":"length"}]}"""
        assertEquals("length", AvalAiClient.extractStreamFinishReason(finishChunk))

        val errChunk = """{"error":{"message":"Upstream stream timeout","code":"timeout"}}"""
        assertEquals("Upstream stream timeout", AvalAiClient.extractStreamErrorMessage(errChunk))
    }

    @Test
    fun `buildSubjectQuestionGenerationPrompt includes active, learned, and dumb questions and q_item framing`() {
        val firstPass = GeminiRepository.buildSubjectQuestionGenerationPrompt(
            subjectName = "Neuroscience",
            textbook = "Action potentials propagate via voltage-gated Na+ channels.",
            extraKnowledge = "Hodgkin and Huxley studied squid giant axons.",
            existingQuestions = emptyList()
        )
        assertTrue(firstPass.contains("FIRST PASS"))
        assertTrue(firstPass.contains("100+"))
        assertTrue(firstPass.contains("[q_item]"))

        val connectedPass = GeminiRepository.buildSubjectQuestionGenerationPrompt(
            subjectName = "Neuroscience",
            textbook = "Action potentials propagate via voltage-gated Na+ channels.",
            extraKnowledge = "",
            existingQuestions = listOf(
                GeminiRepository.ExistingSubjectQuestion(
                    question = "Which channels drive depolarization?",
                    status = FlashcardStatus.ACTIVE
                ),
                GeminiRepository.ExistingSubjectQuestion(
                    question = "What is the resting potential?",
                    status = FlashcardStatus.LEARNED
                ),
                GeminiRepository.ExistingSubjectQuestion(
                    question = "How many letters are in Na+?",
                    status = FlashcardStatus.DUMB
                )
            )
        )
        assertTrue(connectedPass.contains("[A1] Which channels drive depolarization?"))
        assertTrue(connectedPass.contains("[L1] What is the resting potential?"))
        assertTrue(connectedPass.contains("[D1] How many letters are in Na+?"))
        assertTrue(connectedPass.contains(GeminiRepository.NO_MORE_QUESTIONS_TAG))
    }

    @Test
    fun `buildSubjectAddQuestionSystemInstruction embeds textbook content and existing questions`() {
        val subject = Subject(
            id = 7L,
            name = "Thermodynamics",
            textbook = "Entropy of an isolated system never decreases (Second Law).",
            extraKnowledge = "Clausius coined the word entropy in 1865."
        )
        val system = GeminiRepository.buildSubjectAddQuestionSystemInstruction(
            subject = subject,
            existingQuestions = listOf(
                GeminiRepository.ExistingSubjectQuestion(
                    question = "State the First Law of Thermodynamics.",
                    status = FlashcardStatus.LEARNED
                )
            )
        )

        assertTrue(system.contains("Entropy of an isolated system never decreases"))
        assertTrue(system.contains("TEXTBOOK (AUTHORITATIVE ANSWER REFERENCE)"))
        assertTrue(system.contains("[LEARNED] State the First Law of Thermodynamics."))
    }

    @Test
    fun `buildSubjectReferenceInstructions distinguishes textbook answer reference from optional extra knowledge`() {
        val subject = Subject(
            id = 1L,
            name = "Physiology",
            textbook = "Sodium-potassium pump exports 3 Na+ and imports 2 K+ per ATP.",
            extraKnowledge = "Jens Christian Skou discovered Na+/K+-ATPase in crab nerves."
        )

        val instructions = GeminiRepository.buildSubjectReferenceInstructions(
            subject = subject,
            forReviewGrading = true
        )

        assertTrue(instructions.contains("TEXTBOOK (AUTHORITATIVE ANSWER REFERENCE)"))
        assertTrue(instructions.contains("Sodium-potassium pump exports 3 Na+"))
        assertTrue(instructions.contains("EXTRA KNOWLEDGE (BACKING CONTEXT ONLY — NOT REQUIRED IN STUDENT ANSWERS)"))
        assertTrue(instructions.contains("NEVER mark anything missing or wrong in [red] based on Extra Knowledge"))
        assertTrue(instructions.contains("quote"))
    }

    @Test
    fun `IncrementalQuestionCollector emits each q_item live during streaming and salvages interrupted stream`() {
        val streamedLive = mutableListOf<String>()
        val collector = GeminiRepository.IncrementalQuestionCollector(
            existingQuestions = listOf("نقش صفرا در گوارش چیست؟"),
            onNewQuestion = { streamedLive.add(it.question) }
        )

        val chunk1 = "[q_item]\n[q] نقش صفرا در گوارش چیست؟ [/q]\n[a] امولسیون کردن چربی‌ها. [/a]\n[/q_item]\n[q_item]\n[q] آنزیم پپسین در چه محیطی "
        collector.onPartialStream(chunk1)
        assertTrue(streamedLive.isEmpty())

        val chunk2 = chunk1 + "فعال می‌شود؟ [/q]\n[a] در محیط اسیدی معده (با کمک کلریدریک اسید). [/a]\n[/q_item]\n"
        collector.onPartialStream(chunk2)
        assertEquals(listOf("آنزیم پپسین در چه محیطی فعال می‌شود؟"), streamedLive)

        val chunk3Interrupted = chunk2 +
            "[q_item]\n[q] واحد جذب در روده باریک چیست؟ [/q]\n[a] پرزها و ریزپرزهای دیواره روده باریک. [/a]\n" +
            "[q_item]\n[q] هورمون سکرتین از کجا ترشح"
        collector.onPartialStream(chunk3Interrupted)
        assertEquals(1, streamedLive.size)

        collector.finish(chunk3Interrupted)
        assertEquals(
            listOf(
                "آنزیم پپسین در چه محیطی فعال می‌شود؟",
                "واحد جذب در روده باریک چیست؟"
            ),
            streamedLive
        )
        assertEquals(2, collector.emittedQuestions.size)
    }

    @Test
    fun `parseGeneratedQuestions handles q_item frames, no_more_questions, JSON fallback, and deduplication`() {
        assertTrue(GeminiRepository.parseGeneratedQuestions("[]").isEmpty())
        assertTrue(GeminiRepository.parseGeneratedQuestions("[no_more_questions]").isEmpty())
        assertTrue(GeminiRepository.parseGeneratedQuestions("```text\n[no_more_questions]\n```").isEmpty())

        val framedRaw = """
            [q_item]
            [q] What is osmosis? [/q]
            [a] Movement of water across a semipermeable membrane. [/a]
            [/q_item]
            [q_item]
            [q] چه گازهایی در فتوسنتز مصرف می‌شوند؟ [/q]
            [a] کربن دی‌اکسید مصرف و اکسیژن آزاد می‌شود. [/a]
            [/q_item]
        """.trimIndent()

        val parsedFramed = GeminiRepository.parseGeneratedQuestions(
            raw = framedRaw,
            existingQuestions = listOf("what is osmosis")
        )
        assertEquals(1, parsedFramed.size)
        assertEquals("چه گازهایی در فتوسنتز مصرف می‌شوند؟", parsedFramed[0].question)
        assertEquals("کربن دی‌اکسید مصرف و اکسیژن آزاد می‌شود.", parsedFramed[0].answer)

        val truncatedJson = """
            [
              {"question": "First new question?", "answer": "First answer."},
              {"question": "Second new question?", "answer": "Second answer."},
              {"question": "Cut off question...", "answ
        """.trimIndent()
        val salvaged = GeminiRepository.parseGeneratedQuestions(truncatedJson)
        assertEquals(2, salvaged.size)
        assertEquals("First new question?", salvaged[0].question)
        assertEquals("Second new question?", salvaged[1].question)
    }

    @Test
    fun `subject image OCR attachments power effectiveTextbook and effectiveExtraKnowledge even without typed text`() {
        val textbookPages = listOf(
            SubjectImageAttachment(
                id = "tb-1",
                filePath = "/tmp/page1.jpg",
                ocrText = "Mitochondria generate ATP via oxidative phosphorylation."
            ),
            SubjectImageAttachment(
                id = "tb-2",
                filePath = "/tmp/page2.jpg",
                ocrText = "Cytochrome c oxidase is Complex IV of the electron transport chain."
            )
        )
        val extraPages = listOf(
            SubjectImageAttachment(
                id = "ek-1",
                filePath = "/tmp/extra1.jpg",
                ocrText = "Lynn Margulis popularized the endosymbiotic theory in 1967."
            )
        )

        val imageOnlySubject = Subject(
            id = 42L,
            name = "Bioenergetics",
            textbook = "",
            extraKnowledge = "",
            textbookImagesJson = SubjectImagesCodec.encode(textbookPages),
            extraKnowledgeImagesJson = SubjectImagesCodec.encode(extraPages)
        )

        assertTrue(imageOnlySubject.hasTextbookContent())
        assertTrue(imageOnlySubject.hasExtraKnowledgeContent())
        assertTrue(imageOnlySubject.effectiveTextbook().contains("[Image 1]"))
        assertTrue(imageOnlySubject.effectiveTextbook().contains("Mitochondria generate ATP"))
        assertTrue(imageOnlySubject.effectiveTextbook().contains("[Image 2]"))
        assertTrue(imageOnlySubject.effectiveTextbook().contains("Cytochrome c oxidase"))
        assertTrue(imageOnlySubject.effectiveExtraKnowledge().contains("Lynn Margulis"))

        val instructions = GeminiRepository.buildSubjectReferenceInstructions(
            subject = imageOnlySubject,
            forReviewGrading = true
        )
        assertTrue(instructions.contains("TEXTBOOK (AUTHORITATIVE ANSWER REFERENCE)"))
        assertTrue(instructions.contains("Mitochondria generate ATP"))
        assertTrue(instructions.contains("EXTRA KNOWLEDGE (BACKING CONTEXT ONLY — NOT REQUIRED IN STUDENT ANSWERS)"))
        assertTrue(instructions.contains("Lynn Margulis"))
    }

    @Test
    fun `flashdata backup preserves subject textbookImages and extraKnowledgeImages files and OCR text`() = runBlocking {
        val dao = db.flashcardDao()
        val settingsRepo = AppSettingsRepository(context)
        val notesRepo = NotesRepository.getInstance(context)
        val backupManager = FlashDataBackupManager(context, dao, settingsRepo, notesRepo)

        val srcDir = File(context.cacheDir, "test_subject_src").apply { mkdirs() }
        val tbFile = File(srcDir, "tb_scan.jpg")
        val ekFile = File(srcDir, "ek_scan.jpg")
        val sampleBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FileOutputStream(tbFile).use { sampleBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        FileOutputStream(ekFile).use { sampleBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }

        val subjectId = dao.insertSubject(
            Subject(
                name = "Histology",
                textbook = "",
                extraKnowledge = "",
                textbookImagesJson = SubjectImagesCodec.encode(
                    listOf(
                        SubjectImageAttachment(
                            id = "tb-img-1",
                            filePath = tbFile.absolutePath,
                            ocrText = "Simple squamous epithelium lines alveoli."
                        )
                    )
                ),
                extraKnowledgeImagesJson = SubjectImagesCodec.encode(
                    listOf(
                        SubjectImageAttachment(
                            id = "ek-img-1",
                            filePath = ekFile.absolutePath,
                            ocrText = "Alveolar type I cells cover 95% of the alveolar surface."
                        )
                    )
                )
            )
        )

        val out = ByteArrayOutputStream()
        backupManager.exportToStream(out)

        tbFile.delete()
        ekFile.delete()
        dao.deleteAllSubjects()

        backupManager.importFromStream(ByteArrayInputStream(out.toByteArray()))
        val restored = dao.getSubjectById(subjectId)!!
        val restoredTbImages = restored.textbookImages()
        val restoredEkImages = restored.extraKnowledgeImages()

        assertEquals(1, restoredTbImages.size)
        assertEquals("Simple squamous epithelium lines alveoli.", restoredTbImages[0].ocrText)
        assertTrue(File(restoredTbImages[0].filePath).exists())

        assertEquals(1, restoredEkImages.size)
        assertEquals("Alveolar type I cells cover 95% of the alveolar surface.", restoredEkImages[0].ocrText)
        assertTrue(File(restoredEkImages[0].filePath).exists())
    }

    @Test
    fun `avalai payload formats multimodal image_url blocks for multiple images and chat serialization preserves them`() {
        val multiImageMsg = AvalAiClient.Message(
            role = "user",
            content = "Explain both diagrams",
            imageBase64List = listOf("AAAA", "BBBB")
        )
        val payload = JSONObject(
            AvalAiClient.buildChatCompletionsPayload(
                model = AppSettingsRepository.DEEPSEEK_CHAT_MODEL,
                messages = listOf(multiImageMsg),
                temperature = 0.4,
                stream = false
            )
        )
        val msgObj = payload.getJSONArray("messages").getJSONObject(0)
        val contentArray = msgObj.getJSONArray("content")
        assertEquals(3, contentArray.length())
        assertEquals("text", contentArray.getJSONObject(0).getString("type"))
        assertEquals("Explain both diagrams", contentArray.getJSONObject(0).getString("text"))
        assertEquals("image_url", contentArray.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            contentArray.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
        assertEquals(
            "data:image/jpeg;base64,BBBB",
            contentArray.getJSONObject(2).getJSONObject("image_url").getString("url")
        )

        val repo = GeminiRepository(AppSettingsRepository(context))
        val originalChat = listOf(
            ChatMessage(
                sender = "user",
                text = "Compare these two pages",
                imageBase64 = "AAAA",
                imageBase64List = listOf("AAAA", "BBBB")
            )
        )
        val restoredChat = repo.deserializeChat(repo.serializeChat(originalChat))
        assertEquals(1, restoredChat.size)
        assertEquals(listOf("AAAA", "BBBB"), restoredChat[0].allImagesBase64())
    }

    @Test
    fun `notepad addImageUris appends all selected gallery files without overwriting`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notesRepo = NotesRepository.getInstance(app)
        val vm = NotepadViewModel(app)
        val tempDir = File(context.cacheDir, "notepad_multi_pick").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        val uris = (1..3).map { idx ->
            val f = File(tempDir, "pick_$idx.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            Uri.fromFile(f)
        }

        val blockId = notesRepo.tabs.value.first().blocks.first().id
        val startingCount = notesRepo.tabs.value.first().blocks.first { it.id == blockId }.mediaItems.size
        vm.addImageUris(blockId, uris)

        var mediaItems = emptyList<com.example.notepad.data.model.MediaItem>()
        for (attempt in 0 until 40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            mediaItems = notesRepo.tabs.value.first().blocks.first { it.id == blockId }.mediaItems
            if (mediaItems.size >= startingCount + 3) break
            delay(50)
        }

        val addedItems = mediaItems.takeLast(3)
        assertEquals(3, addedItems.size)
        assertTrue(addedItems.all { it.type == MediaType.IMAGE && File(it.filePath).exists() })
        assertEquals(3, addedItems.map { it.filePath }.toSet().size)
    }

    @Test
    fun `buildSingleSubjectQuestionFromConversationPrompt embeds textbook, conversation, and q_item answer format`() {
        val prompt = GeminiRepository.buildSingleSubjectQuestionFromConversationPrompt(
            subjectName = "زیست‌شناسی",
            textbook = "آنزیم پپسین در محیط اسیدی معده پروتئین‌ها را به پلی‌پپتیدهای کوچک‌تر تبدیل می‌کند.",
            extraKnowledge = "پپسین در سال ۱۸۳۶ توسط تئودور شوان کشف شد.",
            existingQuestions = listOf(
                GeminiRepository.ExistingSubjectQuestion(
                    question = "کلریدریک اسید از کدام سلول‌ها ترشح می‌شود؟",
                    status = FlashcardStatus.ACTIVE
                )
            ),
            history = listOf(
                ChatMessage(sender = "user", text = "بیا یک سوال دقیق درباره نقش پپسین بسازیم."),
                ChatMessage(sender = "model", text = "[answer]پپسین در محیط اسیدی معده فعال شده و پروتئین‌ها را تجزیه می‌کند.[/answer]")
            )
        )

        assertTrue(prompt.contains("TEXTBOOK (MAIN AUTHORITATIVE ANSWER REFERENCE)"))
        assertTrue(prompt.contains("آنزیم پپسین در محیط اسیدی معده"))
        assertTrue(prompt.contains("EXTRA KNOWLEDGE (BACKING CONTEXT ONLY"))
        assertTrue(prompt.contains("Do NOT require Extra Knowledge details in `[a]`"))
        assertTrue(prompt.contains("User: بیا یک سوال دقیق درباره نقش پپسین بسازیم."))
        assertTrue(prompt.contains("Tutor: پپسین در محیط اسیدی معده فعال شده"))
        assertTrue(prompt.contains("[q_item]"))

        val parsed = GeminiRepository.parseGeneratedQuestions(
            """
            [q_item]
            [q] نقش آنزیم پپسین در معده چیست؟ [/q]
            [a] در محیط اسیدی معده پروتئین‌ها را به پلی‌پپتیدهای کوچک‌تر تبدیل می‌کند. [/a]
            [/q_item]
            """.trimIndent(),
            existingQuestions = emptyList()
        ).firstOrNull()

        assertEquals("نقش آنزیم پپسین در معده چیست؟", parsed?.question)
        assertEquals(
            "در محیط اسیدی معده پروتئین‌ها را به پلی‌پپتیدهای کوچک‌تر تبدیل می‌کند.",
            parsed?.answer
        )
    }

    @Test
    fun `Extract Question builds language-matched opener and injects review conversation, textbook, and extra knowledge`() {
        val subject = Subject(
            id = 7L,
            name = "زیست‌شناسی ۱۰",
            textbook = "صفرا در کبد ساخته شده و در کیسه صفرا ذخیره می‌شود تا چربی‌ها را امولسیون کند.",
            extraKnowledge = "نمک‌های صفراوی ساختاری آمفی‌پاتیک (دوگانه‌دوست) دارند که کشش سطحی قطره‌های چربی را می‌شکند."
        )
        val sourceCard = Flashcard(
            id = 99L,
            type = FlashcardTypes.EXAM,
            word = "صفرا کجا تولید می‌شود و چه نقشی دارد؟",
            backText = "در کبد تولید می‌شود و چربی‌ها را امولسیون می‌کند.",
            subjectId = subject.id
        )
        val reviewHistory = listOf(
            ChatMessage(sender = "model", text = sourceCard.word),
            ChatMessage(sender = "user", text = "در کبد تولید می‌شود."),
            ChatMessage(
                sender = "model",
                text = "[answer][green]بخش محل تولید کاملاً درست است.[/green]\n[red]نقش امولسیون کردن چربی‌ها را نگفتید.[/red]\n[blue]نمک‌های صفراوی دوگانه‌دوست هستند و چربی را به قطره‌های ریز می‌شکنند.[/blue][/answer]"
            )
        )

        val persianOpener = GeminiRepository.buildExtractQuestionOpeningMessage(
            sourceCard = sourceCard,
            reviewConversation = reviewHistory,
            subject = subject
        )
        assertTrue(persianOpener.contains("فهرست سؤالات"))

        val englishCard = Flashcard(
            id = 100L,
            type = FlashcardTypes.EXAM,
            word = "What does ATP synthase do?",
            backText = "Synthesizes ATP using the proton gradient."
        )
        val englishOpener = GeminiRepository.buildExtractQuestionOpeningMessage(
            sourceCard = englishCard,
            reviewConversation = emptyList(),
            subject = null
        )
        assertEquals(
            "What part of this review would you like to add to your list of questions?",
            englishOpener
        )

        val systemInstruction = GeminiRepository.buildExtractQuestionFromReviewSystemInstruction(
            sourceCard = sourceCard,
            reviewConversation = reviewHistory,
            subject = subject,
            existingQuestions = listOf(
                GeminiRepository.ExistingSubjectQuestion(
                    question = sourceCard.word,
                    status = FlashcardStatus.ACTIVE
                )
            )
        )
        assertTrue(systemInstruction.contains("CONVERSATION OF THIS REVIEW ITEM"))
        assertTrue(systemInstruction.contains("نمک‌های صفراوی دوگانه‌دوست هستند"))
        assertTrue(systemInstruction.contains("TEXTBOOK (AUTHORITATIVE ANSWER REFERENCE)"))
        assertTrue(systemInstruction.contains("EXTRA KNOWLEDGE (BACKING CONTEXT ONLY"))

        val extractionChat = listOf(
            ChatMessage(sender = "model", text = persianOpener),
            ChatMessage(sender = "user", text = "اون بخش دوگانه‌دوست بودن نمک‌های صفراوی برام خیلی جالب بود."),
            ChatMessage(sender = "model", text = "[answer]دقیقاً؛ چون یک سر آب‌دوست و یک سر چربی‌دوست دارند، چربی‌ها را به قطره‌های ریز تقسیم می‌کنند.[/answer]"),
            ChatMessage(sender = "user", text = "دقیقاً همین رو می‌خوام به لیست سؤالاتم اضافه کنم.")
        )

        val generationPrompt = GeminiRepository.buildExtractQuestionFromReviewGenerationPrompt(
            sourceCard = sourceCard,
            reviewConversation = reviewHistory,
            subject = subject,
            existingQuestions = listOf(
                GeminiRepository.ExistingSubjectQuestion(sourceCard.word, FlashcardStatus.ACTIVE)
            ),
            extractionConversation = extractionChat
        )
        assertTrue(generationPrompt.contains("SOURCE REVIEW ITEM"))
        assertTrue(generationPrompt.contains("CONVERSATION OF THE SOURCE REVIEW ITEM"))
        assertTrue(generationPrompt.contains("EXTRACT QUESTION CONVERSATION"))
        assertTrue(generationPrompt.contains("اون بخش دوگانه‌دوست بودن نمک‌های صفراوی برام خیلی جالب بود."))
        assertTrue(generationPrompt.contains("[q_item]"))
    }

    @Test
    fun `single and extracted question generation analyze complete existing list and flag repetitive questions`() {
        val fullExistingList = (1..75).map { idx ->
            val status = when {
                idx == 5 -> FlashcardStatus.DUMB
                idx == 42 -> FlashcardStatus.LEARNED
                else -> FlashcardStatus.ACTIVE
            }
            GeminiRepository.ExistingSubjectQuestion(
                question = "سؤال شماره $idx درباره گوارش چیست؟",
                status = status,
                answer = "پاسخ مرجع شماره $idx برای بررسی معنایی تکراری بودن."
            )
        }

        val addQuestionPrompt = GeminiRepository.buildSingleSubjectQuestionFromConversationPrompt(
            subjectName = "زیست ۱۰",
            textbook = "متن کتاب درسی گوارش",
            extraKnowledge = "",
            existingQuestions = fullExistingList,
            history = listOf(
                ChatMessage(sender = "user", text = "می‌خوام درباره سؤال شماره ۱ صحبت کنیم.")
            )
        )

        // Must include the very first AND very last items without any 60-item truncation, plus answers and statuses
        assertTrue(addQuestionPrompt.contains("TOTAL: 75"))
        assertTrue(addQuestionPrompt.contains("1. [ACTIVE] Q: سؤال شماره 1 درباره گوارش چیست؟ | A: پاسخ مرجع شماره 1"))
        assertTrue(addQuestionPrompt.contains("5. [DUMB] Q: سؤال شماره 5 درباره گوارش چیست؟"))
        assertTrue(addQuestionPrompt.contains("42. [LEARNED] Q: سؤال شماره 42 درباره گوارش چیست؟"))
        assertTrue(addQuestionPrompt.contains("75. [ACTIVE] Q: سؤال شماره 75 درباره گوارش چیست؟"))
        assertTrue(addQuestionPrompt.contains("[question_already_exists]"))

        // 1) Model detects semantic duplicate and emits [question_already_exists] + [matched_q]
        val semanticDuplicateOutcome = GeminiRepository.parseSingleQuestionGenerationOutcome(
            raw = """
                [question_already_exists]
                [matched_q] سؤال شماره 42 درباره گوارش چیست؟ [/matched_q]
            """.trimIndent(),
            existingQuestions = fullExistingList
        )
        assertTrue(semanticDuplicateOutcome is GeminiRepository.SingleQuestionGenerationOutcome.AlreadyExists)
        val matchedSemantic = semanticDuplicateOutcome as GeminiRepository.SingleQuestionGenerationOutcome.AlreadyExists
        assertEquals("سؤال شماره 42 درباره گوارش چیست؟", matchedSemantic.matchedQuestion)
        assertEquals(FlashcardStatus.LEARNED, matchedSemantic.matchedStatus)

        // 2) Deterministic fallback catches exact/punctuation-normalized duplicate even if model emitted [q_item]
        val exactFallbackOutcome = GeminiRepository.parseSingleQuestionGenerationOutcome(
            raw = """
                [q_item]
                [q] سؤال شماره 5 درباره گوارش چیست [/q]
                [a] پاسخ جدید [/a]
                [/q_item]
            """.trimIndent(),
            existingQuestions = fullExistingList
        )
        assertTrue(exactFallbackOutcome is GeminiRepository.SingleQuestionGenerationOutcome.AlreadyExists)
        val matchedExact = exactFallbackOutcome as GeminiRepository.SingleQuestionGenerationOutcome.AlreadyExists
        assertEquals("سؤال شماره 5 درباره گوارش چیست؟", matchedExact.matchedQuestion)
        assertEquals(FlashcardStatus.DUMB, matchedExact.matchedStatus)

        // 3) Genuinely new question returns Generated
        val newOutcome = GeminiRepository.parseSingleQuestionGenerationOutcome(
            raw = """
                [q_item]
                [q] نقش فاکتور داخلی معده در جذب ویتامین B12 چیست؟ [/q]
                [a] فاکتور داخلی برای جذب ویتامین B12 در روده باریک ضروری است. [/a]
                [/q_item]
            """.trimIndent(),
            existingQuestions = fullExistingList
        )
        assertTrue(newOutcome is GeminiRepository.SingleQuestionGenerationOutcome.Generated)
        val generated = (newOutcome as GeminiRepository.SingleQuestionGenerationOutcome.Generated).parsed
        assertEquals("نقش فاکتور داخلی معده در جذب ویتامین B12 چیست؟", generated.question)
    }
}



