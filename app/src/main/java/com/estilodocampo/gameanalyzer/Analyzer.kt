package com.estilodocampo.gameanalyzer

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.sqrt

class Analyzer {
    data class Result(
        val latest: Double?,
        val liveMultiplier: Double?,
        val riskTarget: Double,
        val action: String,
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
        val height: Int,
        val value: Double
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val history = mutableListOf<Double>()
    private var busy = false
    private var lastNewest: Double? = null
    private var currentRiskTarget = 1.30

    fun process(bitmap: Bitmap, onResult: (Result?) -> Unit) {
        if (busy) {
            onResult(null)
            return
        }

        busy = true
        val image = InputImage.fromBitmap(bitmap, 0)

        val historyMinY = (bitmap.height * 0.085).toInt()
        val historyMaxY = (bitmap.height * 0.40).toInt()
        val liveMinY = (bitmap.height * 0.16).toInt()
        val liveMaxY = (bitmap.height * 0.72).toInt()
        val minX = (bitmap.width * 0.02).toInt()
        val maxX = (bitmap.width * 0.98).toInt()
        val minLiveHeight = (bitmap.height * 0.035).toInt().coerceAtLeast(28)

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val historyCandidates = mutableListOf<Candidate>()
                val liveCandidates = mutableListOf<Candidate>()
                val regex = Regex("""(\d{1,3}[\.,]\d{1,2})\s*[xX]""")

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox ?: continue
                            val match = regex.find(element.text) ?: continue
                            val raw = match.groupValues[1].replace(',', '.')
                            val value = raw.toDoubleOrNull() ?: continue
                            if (value < 1.0 || value > 1000.0) continue

                            val cy = box.centerY()
                            val cx = box.centerX()
                            val candidate = Candidate(
                                y = box.top,
                                x = box.left,
                                height = box.height(),
                                value = value
                            )

                            if (cy in historyMinY..historyMaxY && cx in minX..maxX) {
                                historyCandidates.add(candidate)
                            }

                            if (cy in liveMinY..liveMaxY && box.height() >= minLiveHeight) {
                                liveCandidates.add(candidate)
                            }
                        }
                    }
                }

                var historyChanged = false

                if (historyCandidates.isNotEmpty()) {
                    val ordered = historyCandidates
                        .sortedWith(compareBy<Candidate> { it.y }.thenBy { it.x })

                    val newestReadable = ordered.first().value
                    val previousNewest = lastNewest

                    if (previousNewest == null) {
                        lastNewest = newestReadable
                    } else if (kotlin.math.abs(newestReadable - previousNewest) >= 0.005) {
                        lastNewest = newestReadable
                        history.add(newestReadable)
                        if (history.size > 500) history.removeAt(0)
                        currentRiskTarget = calculateRiskTarget()
                        historyChanged = true
                    }
                }

                val live = liveCandidates
                    .maxByOrNull { it.height }
                    ?.value

                if (history.isEmpty() && live == null && !historyChanged) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                onResult(buildResult(live))
            }
            .addOnFailureListener {
                onResult(null)
            }
            .addOnCompleteListener {
                busy = false
            }
    }

    private fun calculateRiskTarget(): Double {
        if (history.size < 5) return 1.30

        val sample = history.takeLast(20)
        val under2 = percent(sample) { it < 2.0 }

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

        return when {
            lowStreak >= 3 -> 1.30
            under2 >= 65 -> 1.30
            sd >= 5.0 -> 1.30
            under2 >= 55 -> 1.40
            sd >= 2.0 -> 1.40
            else -> 1.50
        }
    }

    private fun buildResult(live: Double?): Result {
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
        val mean = if (clipped.isEmpty()) 0.0 else clipped.average()
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

        val action = when {
            live == null && rounds >= 5 -> "ENTRAR AGORA"
            live == null -> "COLETANDO"
            live >= currentRiskTarget -> "SAIR AGORA"
            live >= currentRiskTarget - 0.10 -> "PREPARE-SE PARA SAIR"
            else -> "MANTER"
        }

        return Result(
            latest = history.lastOrNull(),
            liveMultiplier = live,
            riskTarget = currentRiskTarget,
            action = action,
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
