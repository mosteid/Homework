package com.homeworktracker.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * محرك قراءة النصوص العربية والإنجليزية من لقطات الشاشة المشاركة باستخدام Tesseract4Android
 * مع تطبيع الأسماء العربية ومطابقتها مع قائمة طلاب الفصل النشط.
 */
class ArabicOcrProcessor(private val context: Context) {

    private var tessApi: TessBaseAPI? = null
    private var initialized = false

    companion object {
        private const val TAG = "ArabicOcrProcessor"
        private const val ARA_TRAINEDDATA = "ara.traineddata"
        private const val ARA_DOWNLOAD_URL =
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata"
    }

    /**
     * تجهيز ملفات اللغة العربية (من أصول التطبيق المدمجة أو تحميلها تلقائياً أول مرة فقط)
     */
    fun ensureInitialized(onStatus: (String) -> Unit): Boolean {
        if (initialized && tessApi != null) return true

        val baseDir = File(context.filesDir, "tesseract")
        val tessDataDir = File(baseDir, "tessdata")
        if (!tessDataDir.exists()) tessDataDir.mkdirs()

        val araFile = File(tessDataDir, ARA_TRAINEDDATA)
        if (!araFile.exists() || araFile.length() < 100_000L) {
            // 1. محاولة النسخ من assets/tessdata إن كان مدمجاً داخل الـ APK
            val copiedFromAssets = copyFromAssetsIfPresent("tessdata/$ARA_TRAINEDDATA", araFile)
            if (!copiedFromAssets) {
                // 2. تحميل تلقائي لمرة واحدة فقط
                onStatus("جارٍ تحميل ملف اللغة العربية للقراءة (أول مرة فقط)...")
                val downloaded = downloadFile(ARA_DOWNLOAD_URL, araFile)
                if (!downloaded) {
                    onStatus("تعذر تحميل ملف اللغة العربية — تأكد من الاتصال بالإنترنت")
                    return false
                }
            }
        }

        return try {
            val api = TessBaseAPI()
            val ok = api.init(baseDir.absolutePath, "ara")
            if (ok) {
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                tessApi = api
                initialized = true
                true
            } else {
                api.recycle()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Tesseract", e)
            false
        }
    }

    /**
     * قراءة النص من إطار الشاشة الملتقط واستخراج أسماء الطلاب المطابقة أو المرسلين
     */
    fun extractMatchingNames(bitmap: Bitmap, classStudents: List<String>): List<String> {
        val api = tessApi ?: return emptyList()
        val rawText = try {
            api.setImage(bitmap)
            api.utF8Text ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "OCR error", e)
            ""
        }

        if (rawText.isBlank()) return emptyList()

        val found = LinkedHashSet<String>()
        val lines = rawText.lines().map { it.trim() }.filter { it.length >= 2 }

        for (line in lines) {
            val normLine = normalizeArabic(line)
            if (normLine.length < 2) continue

            // مطابقة مباشرة مع طلاب الفصل النشط
            for (student in classStudents) {
                if (calculateSimilarity(normLine, normalizeArabic(student)) >= 0.78) {
                    found.add(student)
                }
            }
        }
        return found.toList()
    }

    fun release() {
        try {
            tessApi?.recycle()
        } catch (_: Exception) {
        } finally {
            tessApi = null
            initialized = false
        }
    }

    private fun copyFromAssetsIfPresent(assetPath: String, destFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.length() > 100_000L
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadFile(urlStr: String, destFile: File): Boolean {
        return try {
            val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.length() > 100_000L) {
                tempFile.renameTo(destFile)
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download traineddata failed", e)
            false
        }
    }

    private fun normalizeArabic(text: String): String {
        return text
            .replace(Regex("[\u064B-\u065F\u0670\u0640]"), "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace("ؤ", "و")
            .replace("ئ", "ي")
           .replace(Regex("[^\u0621-\u064A0-9a-zA-Z\\s]"), " ")
         .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    private fun calculateSimilarity(normLine: String, normStudent: String): Double {
        if (normLine.isEmpty() || normStudent.isEmpty()) return 0.0
        if (normLine == normStudent) return 1.0
        if (normStudent.length >= 4 && normLine.contains(normStudent)) return 0.96

        val lineTokens = normLine.split(" ").filter { it.length >= 2 }
        val studentTokens = normStudent.split(" ").filter { it.length >= 2 }
        if (lineTokens.isEmpty() || studentTokens.isEmpty()) return 0.0

        var matchedScore = 0.0
        for (stToken in studentTokens) {
            var best = 0.0
            for (lnToken in lineTokens) {
                if (stToken == lnToken) {
                    best = 1.0
                    break
                }
                val dist = levenshtein(stToken, lnToken)
                val maxLen = maxOf(stToken.length, lnToken.length)
                val sim = 1.0 - (dist.toDouble() / maxLen.toDouble())
                if (sim >= 0.78 && sim > best) best = sim
            }
            matchedScore += best
        }

        val ratio = matchedScore / studentTokens.size.toDouble()
        return if (studentTokens.size >= 2 && ratio >= 0.80) {
            minOf(0.95, ratio)
        } else if (studentTokens.size == 1 && ratio >= 0.85) {
            0.88
        } else {
            0.0
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }
}
