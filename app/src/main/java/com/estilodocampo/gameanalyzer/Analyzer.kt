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

    private data class HistoryLine(
        val y: Int,
        val values: List<Double>
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy = false

    // Snapshot atual da grade expandida, em ordem: mais recente -> mais antigo.
    private var visibleHistory: List<Double> = emptyList()
    private var lastSnapshotKey = ""
    private var currentRiskTarget = 1.30
    private var nextEntrySignal = false

    fun process(bitmap: Bitmap, onResult: (Result?) -> Unit) {
        if (busy) {
            onResult(null)
            return
        }

        busy = true
        val image = InputImage.fromBitmap(bitmap, 0)

        val historyMinY = (bitmap.height * 0.085).toInt()
        val historyMaxY = (bitmap.height * 0.40).toInt()
        val liveMinY = (bitmap.height * 0.40).toInt()
        val liveMaxY = (bitmap.height * 0.72).toInt()
        val minX = (bitmap.width * 0.02).toInt()
        val maxX = (bitmap.width * 0.98).toInt()
        val minLiveHeight = (bitmap.height * 0.035).toInt().coerceAtLeast(24)

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val historyLines = mutableListOf<HistoryLine>()
                val liveCandidates = mutableListOf<Pair<Int, Double>>()
                val regex = Regex("""(\d{1,3}[\.,]\d{1,2})\s*[xX]""")

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val cy = box.centerY()
                        val cx = box.centerX()

                        // Importante: analisar line.text inteiro. Isso recupera melhor
                        // o primeiro valor quando o botão "Lobby" cobre parte do texto.
                        if (cy in historyMinY..historyMaxY && cx in minX..maxX) {
                            val values = regex.findAll(line.text)
                                .mapNotNull { match ->
                                    match.groupValues[1]
                                        .replace(',', '.')
                                        .toDoubleOrNull()
                                }
                                .filter { it in 1.0..1000.0 }
                                .toList()

                            if (values.isNotEmpty()) {
                                historyLines.add(
                                    HistoryLine(
                                        y = box.top,
                                        values = values
                                    )
                                )
                            }
                        }

                        if (cy in liveMinY..liveMaxY && box.height() >= minLiveHeight) {
                            val liveValue = regex.find(line.text)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.replace(',', '.')
                                ?.toDoubleOrNull()

                            if (liveValue != null && liveValue in 1.0..1000.0) {
                                liveCandidates.add(box.height() to liveValue)
                            }
                        }
                    }
                }

                val orderedHistory = historyLines
                    .sortedBy { it.y }
                    .flatMap { it.values }

                var historyChanged = false

                if (orderedHistory.isNotEmpty()) {
                    val snapshotKey = orderedHistory
                        .take(64)
                        .joinToString("|") { "%.2f".format(it) }

                    if (lastSnapshotKey.isEmpty()) {
                        visibleHistory = orderedHistory
                        lastSnapshotKey = snapshotKey
                        currentRiskTarget = calculateRiskTarget(visibleHistory)
                        nextEntrySignal = calculateNextEntrySignal(visibleHistory)
                    } else if (snapshotKey != lastSnapshotKey) {
                        visibleHistory = orderedHistory
                        lastSnapshotKey = snapshotKey
                        currentRiskTarget = calculateRiskTarget(visibleHistory)
                        nextEntrySignal = calculateNextEntrySignal(visibleHistory)
                        historyChanged = true
                    }
                }

                val live = liveCandidates
                    .maxByOrNull { it.first }
                    ?.second

                if (visibleHistory.isEmpty() && live == null && !historyChanged) {
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

    private fun calculateRiskTarget(historyNewestFirst: List<Double>): Double {
        if (historyNewestFirst.size < 10) return 1.30

        val sample = historyNewestFirst.take(20)
        val under2 = percent(sample) { it < 2.0 }
        val lowStreak = sample.takeWhile { it < 2.0 }.size

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

    private fun calculateNextEntrySignal(historyNewestFirst: List<Double>): Boolean {
        if (historyNewestFirst.size < 10) return false

        val sample = historyNewestFirst.take(20)
        val under2 = percent(sample) { it < 2.0 }
        val over3 = percent(sample) { it >= 3.0 }
        val lowStreak = sample.takeWhile { it < 2.0 }.size

        val clipped = sample.map { minOf(it, 20.0) }
        val mean = clipped.average()
        val variance = if (clipped.size < 2) {
            0.0
        } else {
            clipped.sumOf { (it - mean) * (it - mean) } / clipped.size
        }
        val sd = sqrt(variance)

        // Apenas filtro experimental para decidir se o app destaca a próxima rodada.
        // Não é previsão do multiplicador nem garantia de resultado.
        return under2 <= 50 &&
            over3 >= 20 &&
            lowStreak <= 1 &&
            sd < 5.0
    }

    private fun buildResult(live: Double?): Result {
        val sample = visibleHistory.take(20)
        val rounds = visibleHistory.size

        val under2 = percent(sample) { it < 2.0 }
        val over2 = percent(sample) { it >= 2.0 }
        val over3 = percent(sample) { it >= 3.0 }
        val over5 = percent(sample) { it >= 5.0 }
        val over10 = percent(sample) { it >= 10.0 }
        val lowStreak = sample.takeWhile { it < 2.0 }.size

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
            live != null && live >= currentRiskTarget -> "SAIR AGORA"
            live != null && live >= currentRiskTarget - 0.10 -> "PREPARE-SE PARA SAIR"
            live != null -> "MANTER"
            rounds < 10 -> "COLETANDO"
            nextEntrySignal -> "ENTRAR NA PRÓXIMA RODADA"
            else -> "NÃO ENTRAR NA PRÓXIMA"
        }

        return Result(
            latest = visibleHistory.firstOrNull(),
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
