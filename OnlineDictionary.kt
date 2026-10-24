package com.example.data.dictionary

import com.example.model.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OnlineDictionary {

    suspend fun lookup(rawWord: String, sentence: String = ""): DictionaryEntry =
        withContext(Dispatchers.IO) {
            val local = OfflineDictionary.lookup(rawWord, sentence)
            val isFake = local.urduMeaning.startsWith("انگریزی لفظ")
            if (!isFake) return@withContext local

            val word = local.word.lowercase().trim()
            val urduList = fetchUrdu(word)
            if (urduList.isEmpty()) return@withContext local

            val urdu = urduList.joinToString(" / ")
            val roman = urduList.joinToString(" / ") { toRoman(it) }

            var pos = "word"
            var defEn = local.simpleEnglish
            var example = local.exampleSentence
            var synonyms = emptyList<String>()

            try {
                val body = httpGet(
                    "https://api.dictionaryapi.dev/api/v2/entries/en/" +
                        URLEncoder.encode(word, "UTF-8")
                )
                if (body != null) {
                    val m = JSONArray(body).getJSONObject(0).getJSONArray("meanings").getJSONObject(0)
                    pos = m.optString("partOfSpeech", pos)
                    val d = m.getJSONArray("definitions").getJSONObject(0)
                    defEn = d.optString("definition", defEn)
                    val ex = d.optString("example", "")
                    if (ex.isNotBlank()) example = ex
                    val syn = m.optJSONArray("synonyms")
                    if (syn != null) {
                        synonyms = (0 until minOf(syn.length(), 4)).map { syn.getString(it) }
                    }
                }
            } catch (_: Exception) { }

            local.copy(
                urduMeaning = urdu,
                romanUrdu = roman,
                partOfSpeech = pos,
                simpleEnglish = defEn,
                simpleUrdu = urdu,
                synonyms = synonyms,
                exampleSentence = example
            )
        }

    suspend fun translateSentence(sentence: String): String = withContext(Dispatchers.IO) {
        if (sentence.isBlank()) return@withContext ""
        val text = sentence.trim().take(450)
        val body = httpGet(
            "https://api.mymemory.translated.net/get?q=" +
                URLEncoder.encode(text, "UTF-8") + "&langpair=en|ur"
        )
        try {
            JSONObject(body ?: "").getJSONObject("responseData").getString("translatedText")
        } catch (_: Exception) {
            OfflineDictionary.translateSentenceLocally(sentence)
        }
    }

    private fun fetchUrdu(word: String): List<String> {
        val body = httpGet(
            "https://api.mymemory.translated.net/get?q=" +
                URLEncoder.encode(word, "UTF-8") + "&langpair=en|ur"
        ) ?: return emptyList()
        val result = linkedSetOf<String>()
        try {
            val root = JSONObject(body)
            val main = root.getJSONObject("responseData").optString("translatedText", "")
            if (hasUrdu(main)) result.add(main.trim())
            val matches = root.optJSONArray("matches")
            if (matches != null) {
                for (i in 0 until matches.length()) {
                    val t = matches.getJSONObject(i).optString("translation", "").trim()
                    if (hasUrdu(t) && t.length < 30) result.add(t)
                    if (result.size >= 3) break
                }
            }
        } catch (_: Exception) { }
        return result.toList()
    }

    private fun hasUrdu(s: String) = s.any { it in '\u0600'..'\u06FF' }

    private fun httpGet(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) {
        null
    }

    private val map = mapOf(
        'آ' to "aa", 'ا' to "a", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t",
        'ث' to "s", 'ج' to "j", 'چ' to "ch", 'ح' to "h", 'خ' to "kh", 'د' to "d",
        'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r", 'ز' to "z", 'ژ' to "zh",
        'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z",
        'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ک' to "k", 'گ' to "g",
        'ل' to "l", 'م' to "m", 'ن' to "n", 'ں' to "n", 'و' to "o", 'ہ' to "h",
        'ھ' to "h", 'ء' to "", 'ی' to "i", 'ے' to "e", 'ئ' to "i", 'ۂ' to "h",
        'ۃ' to "t"
    )
    private val vowels = setOf('ا', 'آ', 'و', 'ی', 'ے', 'ئ', 'ع')
    private val diacritics = setOf('\u064E', '\u064F', '\u0650', '\u0651', '\u0670', '\u0652')

    fun toRoman(urdu: String): String {
        val sb = StringBuilder()
        var prevConsonant = false
        for (c in urdu) {
            if (c in diacritics) continue
            val m = map[c]
            if (m == null) {
                sb.append(c)
                prevConsonant = false
                continue
            }
            val isVowel = c in vowels
            if (!isVowel && prevConsonant && c != 'ھ' && c != 'ں') sb.append("a")
            sb.append(m)
            prevConsonant = !isVowel
        }
        return sb.toString().replaceFirstChar { it.uppercase() }
    }
}
