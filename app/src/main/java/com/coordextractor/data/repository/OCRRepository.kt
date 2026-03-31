package com.coordextractor.data.repository

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCRRepository — Google ML Kit Text Recognition ka wrapper.
 *
 * - On-device processing (no network needed)
 * - Latin script (supports N/S/E/W directions)
 * - Coroutine-friendly suspend function
 */
class OCRRepository {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Image se text recognize karo.
     * @param image ML Kit InputImage (Bitmap se banaya)
     * @return Recognized text string (empty if nothing found)
     * @throws Exception agar OCR fail ho
     */
    suspend fun recognizeText(image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    if (continuation.isActive) {
                        continuation.resume(visionText.text)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }

            // Cancellation handle
            continuation.invokeOnCancellation {
                // ML Kit task cancel nahi ho sakta directly, but we ignore result
            }
        }

    /**
     * Resources release karo (app close hone par)
     */
    fun close() {
        recognizer.close()
    }
}
