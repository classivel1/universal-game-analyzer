package com.estilodocampo.gameanalyzer

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.sqrt

class Analyzer {
    data class Result(
        val latest: Double,
        val rounds: Int,
        val under2Pct: Int,
        val over2Pct: Int,
        val over3Pct: Int,
        val over5Pct: Int,
        val over10Pct: Int,
        val lowStreak: Int,
        val volatility: String,
        val last20: List<Double>
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val history = mutableListOf<Double>()
    private var busy = false
    private var lastSnapshot = ""

    fun process(bitmap: Bitmap, onResult: (Result?) -> Unit) {
        if (busy) {
            onResult(null)
            return
        }

        busy = true
        val image = InputImage.fromBitmap(bitmap, 0)
        val minY = (bitmap.height * 0.075).toInt()
        val maxY = (bitmap.height * 0.155).toInt()

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val values = mutableListOf<Pair<Int, Double>>()
                val regex = Regex("""(\d{1,3}[\.,]\d{1,2})\s*[xX]""")

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox ?: continue
                            val cy = box.centerY()
                            if (cy !in minY..maxY) continue

                            val match = regex.find(element.text) ?: continue
                            val raw = match.groupValues[1].replace(',', '.')
                            val value = raw.toDoubleOrNull() ?: continue
                            if (value < 1.0 || value > 1000.0) continue
                            values.add(box.left to value)
                        }
                    }
                }

                val ordered = values
                    .sortedBy { it.first }
                    .map { it.second }

                if (ordered.isEmpty()) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                val snapshot = ordered.joinToString("|") { "%.2f".format(it) }
                if (snapshot == lastSnapshot) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                if (lastSnapshot.isNotEmpty()) {
                    val newestReadable = ordered.first()
                    history.add(newestReadable)
                    if (history.size > 500) history.removeAt(0)
                }

                lastSnapshot = snapshot
                onResult(if (history.isEmpty()) null else buildResult())
            }
            .addOnFailureListener {
                onResult(null)
            }
            .addOnCompleteListener {
                busy = false
            }
    }

    private fun buildResult(): Result {
        val sample = history.takeLast(20)
        val rounds = history.size
        val under2 = percent(sample) { it < 2.0 }
        val over2 = percent(sample) { it >= 2.0 }
        val over3 = percent(sample) { it >= 3.0 }
        val over5 = percent(sample) { it >= 5.0 }
        val over10 = percent(sample) { it >= 10.0 }

        var lowStreak = 0
        for (v in history.asReversed()) {
            if (v < 2.0) lowStreak++ else break
        }

        val clipped = sample.map { minOf(it, 20.0) }
        val mean = clipped.average()
        val variance = if (clipped.size < 2) 0.0 else
            clipped.sumOf { (it - mean) * (it - mean) } / clipped.size
        val sd = sqrt(variance)

        val volatility = when {
            sample.size < 5 -> "COLETANDO"
            sd >= 5.0 -> "ALTA"
            sd >= 2.0 -> "MÉDIA"
            else -> "BAIXA"
        }

        return Result(
            latest = history.last(),
            rounds = rounds,
            under2Pct = under2,
            over2Pct = over2,
            over3Pct = over3,
            over5Pct = over5,
            over10Pct = over10,
            lowStreak = lowStreak,
            volatility = volatility,
            last20 = sample
        )
    }

    private fun percent(values: List<Double>, predicate: (Double) -> Boolean): Int {
        if (values.isEmpty()) return 0
        return ((values.count(predicate) * 100.0) / values.size).toInt()
    }

    fun close() {
        recognizer.close()
    }
}
