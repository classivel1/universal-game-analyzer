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

    private data class Candidate(
        val y: Int,
        val x: Int,
        val value: Double
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val history = mutableListOf<Double>()
    private var busy = false
    private var lastNewest: Double? = null

    fun process(bitmap: Bitmap, onResult: (Result?) -> Unit) {
        if (busy) {
            onResult(null)
            return
        }

        busy = true
        val image = InputImage.fromBitmap(bitmap, 0)

        // Faixa do histórico do Aviator vista no vídeo/print.
        // O overlay fica fora desta região para não contaminar o OCR.
        val minY = (bitmap.height * 0.085).toInt()
        val maxY = (bitmap.height * 0.40).toInt()
        val minX = (bitmap.width * 0.02).toInt()
        val maxX = (bitmap.width * 0.98).toInt()

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val candidates = mutableListOf<Candidate>()
                val regex = Regex("""(\d{1,3}[\.,]\d{1,2})\s*[xX]""")

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox ?: continue
                            val cy = box.centerY()
                            val cx = box.centerX()

                            if (cy !in minY..maxY || cx !in minX..maxX) continue

                            val match = regex.find(element.text) ?: continue
                            val raw = match.groupValues[1].replace(',', '.')
                            val value = raw.toDoubleOrNull() ?: continue

                            if (value < 1.0 || value > 1000.0) continue

                            candidates.add(
                                Candidate(
                                    y = box.top,
                                    x = box.left,
                                    value = value
                                )
                            )
                        }
                    }
                }

                if (candidates.isEmpty()) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                // No histórico expandido do Aviator, o resultado mais recente fica
                // no primeiro item da primeira linha: ordenar por Y e depois por X.
                val ordered = candidates
                    .sortedWith(compareBy<Candidate> { it.y }.thenBy { it.x })

                val newestReadable = ordered.first().value
                val previousNewest = lastNewest

                if (previousNewest == null) {
                    lastNewest = newestReadable
                    onResult(null)
                    return@addOnSuccessListener
                }

                // Só registra nova rodada quando o primeiro multiplicador muda.
                if (kotlin.math.abs(newestReadable - previousNewest) < 0.005) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                lastNewest = newestReadable
                history.add(newestReadable)
                if (history.size > 500) history.removeAt(0)

                onResult(buildResult())
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
        val variance = if (clipped.size < 2) {
            0.0
        } else {
            clipped.sumOf { (it - mean) * (it - mean) } / clipped.size
        }
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
