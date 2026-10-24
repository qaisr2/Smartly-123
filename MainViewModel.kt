package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiExplanationResult
import com.example.data.ai.AiTutorService
import com.example.data.dictionary.OfflineDictionary
import com.example.data.dictionary.OnlineDictionary
import com.example.data.local.AppDatabase
import com.example.data.pdf.PdfDocumentManager
import com.example.model.DictionaryEntry
import com.example.model.SavedWord
import com.example.model.SearchHistoryItem
import com.example.service.SpeechService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    READER,
    SAVED_WORDS,
    PRACTICE,
    HISTORY
}

enum class ReadingViewMode {
    SPLIT,
    FULL_PAGE,
    TEXT_READER
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val savedWordDao = db.savedWordDao()
    private val searchHistoryDao = db.searchHistoryDao()
    private val pdfManager = PdfDocumentManager(application)
    private val speechService = SpeechService(application)
    private val aiService = AiTutorService()

    // Navigation
    private val _currentScreen = MutableStateFlow(AppScreen.READER)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    // PDF Reading State
    private val _pdfTitle = MutableStateFlow("Smart English Tutor Guide.pdf")
    val pdfTitle: StateFlow<String> = _pdfTitle.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _currentPageBitmap = MutableStateFlow<Bitmap?>(null)
    val currentPageBitmap: StateFlow<Bitmap?> = _currentPageBitmap.asStateFlow()

    private val _currentPageText = MutableStateFlow("")
    val currentPageText: StateFlow<String> = _currentPageText.asStateFlow()

    private val _isPdfLoading = MutableStateFlow(false)
    val isPdfLoading: StateFlow<Boolean> = _isPdfLoading.asStateFlow()

    private val _readingMode = MutableStateFlow(ReadingViewMode.SPLIT)
    val readingMode: StateFlow<ReadingViewMode> = _readingMode.asStateFlow()

    // Dictionary & Selection State
    private val _selectedWord = MutableStateFlow("diligent")
    val selectedWord: StateFlow<String> = _selectedWord.asStateFlow()

    private val _surroundingSentence = MutableStateFlow("A diligent reader does not hesitate when encountering difficult words.")
    val surroundingSentence: StateFlow<String> = _surroundingSentence.asStateFlow()

    private val _currentEntry = MutableStateFlow<DictionaryEntry?>(null)
    val currentEntry: StateFlow<DictionaryEntry?> = _currentEntry.asStateFlow()

    private val _sentenceTranslation = MutableStateFlow("")
    val sentenceTranslation: StateFlow<String> = _sentenceTranslation.asStateFlow()

    private val _isWordSaved = MutableStateFlow(false)
    val isWordSaved: StateFlow<Boolean> = _isWordSaved.asStateFlow()

    private val _isPanelExpanded = MutableStateFlow(true)
    val isPanelExpanded: StateFlow<Boolean> = _isPanelExpanded.asStateFlow()

    // AI Contextual Explanation State
    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _aiResult = MutableStateFlow<AiExplanationResult?>(null)
    val aiResult: StateFlow<AiExplanationResult?> = _aiResult.asStateFlow()

    private val _aiErrorMessage = MutableStateFlow<String?>(null)
    val aiErrorMessage: StateFlow<String?> = _aiErrorMessage.asStateFlow()

    private val _isAiEnabled = MutableStateFlow(false)
    val isAiEnabled: StateFlow<Boolean> = _isAiEnabled.asStateFlow()

    val isAiKeyPresent: Boolean = aiService.isAiConfigured()

    // Saved Words & History Flow
    val savedWords: StateFlow<List<SavedWord>> = savedWordDao.getAllSavedWords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchHistory: StateFlow<List<SearchHistoryItem>> = searchHistoryDao.getRecentHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Practice / Quiz State
    private val _practiceCardIndex = MutableStateFlow(0)
    val practiceCardIndex: StateFlow<Int> = _practiceCardIndex.asStateFlow()

    private val _isCardFlipped = MutableStateFlow(false)
    val isCardFlipped: StateFlow<Boolean> = _isCardFlipped.asStateFlow()

    init {
        loadInitialPdf()
        lookupWord("diligent", "A diligent reader does not hesitate when encountering difficult words.")
    }

    private fun loadInitialPdf() {
        viewModelScope.launch {
            _isPdfLoading.value = true
            val res = pdfManager.loadBundledSamplePdf()
            res.onSuccess { count ->
                _totalPages.value = count.coerceAtLeast(1)
                _pdfTitle.value = pdfManager.currentTitle
                _currentPage.value = 0
                loadCurrentPageContent(0)
            }
            _isPdfLoading.value = false
        }
    }

    fun openPdfUri(uri: Uri, fileName: String) {
        viewModelScope.launch {
            _isPdfLoading.value = true
            val res = pdfManager.loadFromUri(uri, fileName)
            res.onSuccess { count ->
                _totalPages.value = count.coerceAtLeast(1)
                _pdfTitle.value = pdfManager.currentTitle
                _currentPage.value = 0
                loadCurrentPageContent(0)
            }
            _isPdfLoading.value = false
        }
    }

    fun goToPage(page: Int) {
        if (page in 0 until _totalPages.value) {
            _currentPage.value = page
            loadCurrentPageContent(page)
        }
    }

    fun nextPage() {
        if (_currentPage.value + 1 < _totalPages.value) {
            goToPage(_currentPage.value + 1)
        }
    }

    fun previousPage() {
        if (_currentPage.value - 1 >= 0) {
            goToPage(_currentPage.value - 1)
        }
    }

    private fun loadCurrentPageContent(pageIndex: Int) {
        viewModelScope.launch {
            _currentPageBitmap.value = pdfManager.renderPageBitmap(pageIndex)
            val extractedText = pdfManager.extractPageText(pageIndex)
            _currentPageText.value = extractedText
        }
    }

    fun setReadingMode(mode: ReadingViewMode) {
        _readingMode.value = mode
    }

    fun setScreen(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun togglePanelExpanded() {
        _isPanelExpanded.value = !_isPanelExpanded.value
    }

    fun setAiEnabled(enabled: Boolean) {
        _isAiEnabled.value = enabled
        if (enabled && _aiResult.value == null) {
            fetchAiExplanation()
        }
    }

    fun lookupWord(word: String, sentence: String = "") {
        val clean = word.trim().replace(Regex("[^a-zA-Z]"), "")
        if (clean.isBlank()) return

        _selectedWord.value = clean
        val activeSentence = if (sentence.isNotBlank()) sentence else _surroundingSentence.value
        _surroundingSentence.value = activeSentence

        // Pehle offline entry foran dikha do
        val offlineEntry = OfflineDictionary.lookup(clean, activeSentence)
        _currentEntry.value = offlineEntry
        _sentenceTranslation.value = OfflineDictionary.translateSentenceLocally(activeSentence)
        _aiResult.value = null
        _aiErrorMessage.value = null

        viewModelScope.launch {
            // Jumle ka poora Urdu translation (internet se)
            val sentTr = try {
                OnlineDictionary.translateSentence(activeSentence)
            } catch (e: Exception) {
                ""
            }
            if (sentTr.isNotBlank() && _surroundingSentence.value == activeSentence) {
                _sentenceTranslation.value = sentTr
            }

            // Word ki Urdu aur Roman Urdu meaning (offline na mile to internet se)
            val entry = try {
                OnlineDictionary.lookup(clean, activeSentence)
            } catch (e: Exception) {
                offlineEntry
            }
            if (_selectedWord.value == clean) {
                _currentEntry.value = entry
            }

            val count = savedWordDao.isWordSaved(clean)
            _isWordSaved.value = count > 0

            searchHistoryDao.insert(
                SearchHistoryItem(
                    query = clean,
                    urduPreview = entry.urduMeaning.split("/").first().trim()
                )
            )
        }

        if (_isAiEnabled.value && isAiKeyPresent) {
            fetchAiExplanation()
        }
    }

    fun translateCustomSentence(sentence: String) {
        _surroundingSentence.value = sentence
        _sentenceTranslation.value = OfflineDictionary.translateSentenceLocally(sentence)
        viewModelScope.launch {
            val translated = try {
                OnlineDictionary.translateSentence(sentence)
            } catch (e: Exception) {
                ""
            }
            if (translated.isNotBlank() && _surroundingSentence.value == sentence) {
                _sentenceTranslation.value = translated
            }
        }
        if (_isAiEnabled.value && isAiKeyPresent) {
            fetchAiExplanation()
        }
    }

    fun fetchAiExplanation() {
        val word = _selectedWord.value
        val sentence = _surroundingSentence.value
        if (word.isBlank()) return

        viewModelScope.launch {
            _isAiLoading.value = true
            _aiErrorMessage.value = null
            val result = aiService.getContextualExplanation(word, sentence)
            result.onSuccess { explanation ->
                _aiResult.value = explanation
                if (explanation.sentenceTranslation.isNotBlank()) {
                    _sentenceTranslation.value = explanation.sentenceTranslation
                }
            }.onFailure { err ->
                _aiErrorMessage.value = err.message ?: "Failed to load AI explanation."
            }
            _isAiLoading.value = false
        }
    }

    fun toggleSaveCurrentWord() {
        val entry = _currentEntry.value ?: return
        viewModelScope.launch {
            if (_isWordSaved.value) {
                savedWordDao.deleteByWord(entry.word)
                _isWordSaved.value = false
            } else {
                savedWordDao.insert(
                    SavedWord(
                        word = entry.word,
                        urduMeaning = entry.urduMeaning,
                        romanUrdu = entry.romanUrdu,
                        partOfSpeech = entry.partOfSpeech,
                        simpleEnglish = entry.simpleEnglish,
                        simpleUrdu = entry.simpleUrdu,
                        synonyms = entry.synonyms.joinToString(", "),
                        contextSentence = _surroundingSentence.value
                    )
                )
                _isWordSaved.value = true
            }
        }
    }

    fun deleteSavedWord(savedWord: SavedWord) {
        viewModelScope.launch {
            savedWordDao.delete(savedWord)
            if (savedWord.word.equals(_selectedWord.value, ignoreCase = true)) {
                _isWordSaved.value = false
            }
        }
    }

    fun updateWordMastery(savedWord: SavedWord, level: Int) {
        viewModelScope.launch {
            savedWordDao.updateMastery(savedWord.id, level)
        }
    }

    fun speakCurrentWord() {
        speechService.speak(_selectedWord.value)
    }

    fun speakText(text: String) {
        speechService.speak(text)
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            searchHistoryDao.clearAll()
        }
    }

    // Practice / Flashcards
    fun nextPracticeCard(totalCards: Int) {
        if (totalCards > 0) {
            _practiceCardIndex.value = (_practiceCardIndex.value + 1) % totalCards
            _isCardFlipped.value = false
        }
    }

    fun flipPracticeCard() {
        _isCardFlipped.value = !_isCardFlipped.value
    }

    override fun onCleared() {
        super.onCleared()
        pdfManager.close()
        speechService.shutdown()
    }
}
