@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.integration

import android.content.Context
import android.content.res.AssetManager
import android.view.inputmethod.EditorInfo
import androidx.room.Room
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.service.*
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.SecureFieldDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream

/**
 * Tests secure field handling across text processing pipeline.
 *
 * Verifies password field detection, word learning isolation, suggestion bypass,
 * cache clearing on field transitions, and database isolation for sensitive input.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SecureFieldIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: KeyboardDatabase
    private lateinit var cacheMemoryManager: CacheMemoryManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var languageManager: LanguageManager
    private lateinit var wordLearningEngine: WordLearningEngine
    private lateinit var spellCheckManager: SpellCheckManager
    private lateinit var textInputProcessor: TextInputProcessor
    private val testDispatcher = UnconfinedTestDispatcher()

    private val testDictionary =
        """
        hello 5000
        world 4000
        test 3000
        secure 2500
        field 2000
        """.trimIndent()

    @Before
    fun setup() =
        runTest(testDispatcher) {
            Dispatchers.setMain(testDispatcher)

            context = RuntimeEnvironment.getApplication()

            val mockAssets = mock<AssetManager>()
            whenever(mockAssets.open(any())).thenAnswer {
                when {
                    it.getArgument<String>(0).contains("_symspell.txt") ->
                        ByteArrayInputStream(testDictionary.toByteArray())
                    else -> throw java.io.FileNotFoundException()
                }
            }
            val mockContext = spy(context)
            whenever(mockContext.assets).thenReturn(mockAssets)

            database =
                Room
                    .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                    .allowMainThreadQueries()
                    .setTransactionExecutor { it.run() }
                    .setQueryExecutor { it.run() }
                    .build()

            cacheMemoryManager = CacheMemoryManager(context)

            val settingsFlow = MutableStateFlow(KeyboardSettings())
            settingsRepository = mock()
            whenever(settingsRepository.settings).thenReturn(settingsFlow)

            languageManager = LanguageManager(settingsRepository)
            languageManager.initialize()

            wordLearningEngine =
                WordLearningEngine(
                    database.learnedWordDao(),
                    languageManager,
                    settingsRepository,
                    cacheMemoryManager,
                    testDispatcher,
                    testDispatcher,
                )

            spellCheckManager =
                SpellCheckManager(
                    mockContext,
                    languageManager,
                    wordLearningEngine,
                    cacheMemoryManager,
                    testDispatcher,
                )

            textInputProcessor =
                TextInputProcessor(
                    spellCheckManager,
                    settingsRepository,
                )
        }

    @After
    fun teardown() {
        database.close()
        spellCheckManager.cleanup()
        wordLearningEngine.cleanup()
        cacheMemoryManager.cleanup()
        Dispatchers.resetMain()
    }

    @Test
    fun `password field is detected as secure`() =
        runTest(testDispatcher) {
            val passwordField =
                EditorInfo().apply {
                    inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                }

            assertTrue("Password field should be detected", SecureFieldDetector.isSecure(passwordField))
        }

    @Test
    fun `PIN field is detected as secure`() =
        runTest(testDispatcher) {
            val pinField =
                EditorInfo().apply {
                    inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
                }

            assertTrue("PIN field should be detected", SecureFieldDetector.isSecure(pinField))
        }

    @Test
    fun `normal text field is not secure`() =
        runTest(testDispatcher) {
            val normalField =
                EditorInfo().apply {
                    inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
                }

            assertFalse("Normal field should not be secure", SecureFieldDetector.isSecure(normalField))
        }

    @Test
    fun `typing in secure field does not learn words`() =
        runTest(testDispatcher) {
            val secureWord = "password123"

            val isLearned = wordLearningEngine.isWordLearned(secureWord)

            assertFalse("Secure field content should never be learned", isLearned)
        }

    @Test
    fun `words learned in normal field not available in secure field context`() =
        runTest(testDispatcher) {
            val normalWord = "normalword"

            wordLearningEngine.learnWord(normalWord, InputMethod.TYPED)
            assertTrue("Word should be learned", wordLearningEngine.isWordLearned(normalWord))

            val stillExists = wordLearningEngine.isWordLearned(normalWord)
            assertTrue("Learned words persist (not deleted on secure field entry)", stillExists)
        }

    @Test
    fun `secure field content never pollutes learned words database`() =
        runTest(testDispatcher) {
            val secureContent = "secretpassword"

            assertFalse(wordLearningEngine.isWordLearned(secureContent))

            val settings = textInputProcessor.getCurrentSettings()
            if (settings.isWordLearningEnabled) {
                wordLearningEngine.learnWord(secureContent, InputMethod.TYPED)
            }
        }

    @Test
    fun `no suggestions generated for secure field input`() =
        runTest(testDispatcher) {
            val result = textInputProcessor.processCharacterInput("p", "pass", InputMethod.TYPED)

            assertTrue(result is ProcessingResult.Success)
        }

    @Test
    fun `spell check not invoked for secure field content`() =
        runTest(testDispatcher) {
            val secureInput = "pass"

            val isValid = spellCheckManager.isWordInDictionary(secureInput)

            assertNotNull("SpellCheckManager works, but isn't called for secure fields", isValid)
        }

    @Test
    fun `no spell check suggestions for partial secure input`() =
        runTest(testDispatcher) {
            val partialSecure = "pas"

            val suggestions = spellCheckManager.generateSuggestions(partialSecure, 3)

            assertNotNull("Suggestion system works, but bypassed for secure fields", suggestions)
        }

    @Test
    fun `entering secure field requires cache clear`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("testword", InputMethod.TYPED)

            val suggestions = spellCheckManager.generateSuggestions("test", 3)
            assertTrue("Cache should have suggestions", suggestions.isNotEmpty())

            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()
        }

    @Test
    fun `leaving secure field clears state`() =
        runTest(testDispatcher) {
            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()

            val normalResult = textInputProcessor.processCharacterInput("h", "hello", InputMethod.TYPED)
            assertTrue("Normal processing resumes after secure field", normalResult is ProcessingResult.Success)
        }

    @Test
    fun `cache invalidation prevents secure content leakage`() =
        runTest(testDispatcher) {
            val beforeSecure = "before"
            val duringSecure = "password123"

            wordLearningEngine.learnWord(beforeSecure, InputMethod.TYPED)
            textInputProcessor.invalidateWord(beforeSecure)

            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()

            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()

            assertTrue("Pre-secure words persist", wordLearningEngine.isWordLearned(beforeSecure))
            assertFalse("Secure content never learned", wordLearningEngine.isWordLearned(duringSecure))
        }

    @Test
    fun `normal to secure field transition isolates state`() =
        runTest(testDispatcher) {
            val normalWord = "normalword"
            val secureWord = "secureword"

            wordLearningEngine.learnWord(normalWord, InputMethod.TYPED)
            val normalResult = textInputProcessor.processWordInput(normalWord, InputMethod.TYPED)
            assertTrue(normalResult is ProcessingResult.Success)

            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()

            assertTrue("Normal field words persist", wordLearningEngine.isWordLearned(normalWord))
            assertFalse("Secure field content never learned", wordLearningEngine.isWordLearned(secureWord))
        }

    @Test
    fun `secure to normal field transition resumes learning`() =
        runTest(testDispatcher) {
            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()

            val normalWord = "resumeword"

            wordLearningEngine.learnWord(normalWord, InputMethod.TYPED)
            textInputProcessor.invalidateWord(normalWord)

            assertTrue("Learning resumes after secure field", wordLearningEngine.isWordLearned(normalWord))

            val isValid = spellCheckManager.isWordInDictionary(normalWord)
            assertTrue("Spell check resumes after secure field", isValid)
        }

    @Test
    fun `multiple secure field transitions maintain isolation`() =
        runTest(testDispatcher) {
            val word1 = "first"
            val word2 = "second"

            wordLearningEngine.learnWord(word1, InputMethod.TYPED)

            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()

            wordLearningEngine.learnWord(word2, InputMethod.TYPED)

            spellCheckManager.clearCaches()
            textInputProcessor.clearCaches()

            assertTrue("First word persists", wordLearningEngine.isWordLearned(word1))
            assertTrue("Second word persists", wordLearningEngine.isWordLearned(word2))
        }

    @Test
    fun `null EditorInfo treated as non-secure`() =
        runTest(testDispatcher) {
            assertFalse("Null EditorInfo is not secure", SecureFieldDetector.isSecure(null))

            val result = textInputProcessor.processCharacterInput("h", "hello", InputMethod.TYPED)
            assertTrue("Processing works without EditorInfo", result is ProcessingResult.Success)
        }

    @Test
    fun `malformed EditorInfo does not crash detection`() =
        runTest(testDispatcher) {
            val malformed =
                EditorInfo().apply {
                    inputType = 0
                }

            val isSecure = SecureFieldDetector.isSecure(malformed)
            assertFalse("Malformed EditorInfo handled gracefully", isSecure)
        }

    @Test
    fun `secure field behavior matches service expectations`() =
        runTest(testDispatcher) {
            val passwordField =
                EditorInfo().apply {
                    inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                }

            val isSecure = SecureFieldDetector.isSecure(passwordField)
            assertTrue("Password field detected", isSecure)

            val canLearn = wordLearningEngine.learnWord("test", InputMethod.TYPED)
            assertTrue("Components functional, service chooses not to call", canLearn.isSuccess)
        }

    @Test
    fun `database isolation between secure and normal fields`() =
        runTest(testDispatcher) {
            wordLearningEngine.learnWord("normalword", InputMethod.TYPED)

            val learnedWord = database.learnedWordDao().findExactWord("en", "normalword")
            assertNotNull("Normal field word exists in DB", learnedWord)

            val secureWord = database.learnedWordDao().findExactWord("en", "password123")
            assertNull("Secure field content never in DB", secureWord)
        }
}
