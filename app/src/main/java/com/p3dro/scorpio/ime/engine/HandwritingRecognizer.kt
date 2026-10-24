package com.p3dro.scorpio.ime.engine

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 手寫辨識（Google ML Kit Digital Ink，離線）：繁體中文模型，第一次使用時下載
 */
class HandwritingRecognizer {

    companion object {
        private const val TAG = "ScorpioHandwriting"

        // 依序嘗試的語言標籤（ML Kit 的繁中模型）
        private val LANGUAGE_TAGS = listOf("zh-TW", "zh-Hant-TW", "zh-Hani-TW", "zh-HK")
    }

    private var model: DigitalInkRecognitionModel? = null
    private var recognizer: DigitalInkRecognizer? = null

    private fun identifier(): DigitalInkRecognitionModelIdentifier? {
        for (tag in LANGUAGE_TAGS) {
            try {
                DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)?.let {
                    Log.i(TAG, "手寫模型：$tag")
                    return it
                }
            } catch (e: Exception) {
                // 標籤格式不合，試下一個
            }
        }
        return null
    }

    /** 確認模型已下載（必要時下載，開始下載前呼叫 onDownloading）；回傳是否可用 */
    suspend fun ensureModel(onDownloading: () -> Unit = {}): Boolean {
        recognizer?.let { return true }
        val id = identifier() ?: return false
        val m = DigitalInkRecognitionModel.builder(id).build()
        val manager = RemoteModelManager.getInstance()
        val downloaded = suspendCancellableCoroutine { cont ->
            manager.isModelDownloaded(m)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(false) }
        }
        if (!downloaded) {
            onDownloading()
            val ok = suspendCancellableCoroutine { cont ->
                manager.download(m, DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "手寫模型下載失敗", e)
                        cont.resume(false)
                    }
            }
            if (!ok) return false
        }
        model = m
        recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
        return true
    }

    /** 辨識整塊筆跡；preContext 為游標前的文字（幫助判斷常見搭配） */
    suspend fun recognize(ink: Ink, width: Float, height: Float, preContext: String): List<String> {
        val r = recognizer ?: return emptyList()
        val ctx = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(20))
            .setWritingArea(WritingArea(width, height))
            .build()
        return suspendCancellableCoroutine { cont ->
            r.recognize(ink, ctx)
                .addOnSuccessListener { result -> cont.resume(result.candidates.map { it.text }.filter { it.isNotBlank() }.distinct()) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "手寫辨識失敗", e)
                    cont.resume(emptyList())
                }
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
