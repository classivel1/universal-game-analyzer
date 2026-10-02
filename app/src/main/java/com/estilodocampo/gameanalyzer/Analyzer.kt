package com.estilodocampo.gameanalyzer

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min
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
        val last20: List<Double>,
        val historyComplete: Boolean
    )

    private data class HistoryLine(
        val y: Int,
        val x: Int,
        val height: Int,
        val values: List<Double>
    )

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val firstCellRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var busy = false
    private var visibleHistory: List<Double> = emptyList()
    private var lastSnapshotKey = ""
    private var currentRiskTarget = 1.30
    private var nextEntrySignal = false
    private var historyComplete = false

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
        val regex = Regex("""(\d{1,3}[\.,]\d{1,2})\s*[xX]""")

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val historyLines = mutableListOf<HistoryLine>()
                val liveCandidates = mutableListOf<Pair<Int, Double>>()

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val cy = box.centerY()
                        val cx = box.centerX()

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
                                        x = box.left,
                                        height = box.height(),
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

                val sortedLines = historyLines.sortedBy { it.y }
                val live = liveCandidates.maxByOrNull { it.first }?.second

                if (sortedLines.isEmpty()) {
                    finish(onResult, if (visibleHistory.isEmpty() && live == null) null else buildResult(live))
                    return@addOnSuccessListener
                }

                val firstLine = sortedLines.first()
                val orderedRecognized = sortedLines.flatMap { it.values }

                // O botão "Lobby" cobre parcialmente o primeiro multiplicador.
                // Se a primeira linha começar longe demais da borda esquerda,
                // tentamos recuperar somente a primeira célula em alta resolução.
                val firstLooksHidden =
                    firstLine.x > (bitmap.width * 0.10).toInt()

                if (firstLooksHidden) {
                    recoverFirstCell(
                        bitmap = bitmap,
                        rowY = firstLine.y,
                        rowHeight = firstLine.height,
                        regex = regex
                    ) { recovered ->
                        val corrected =
                            if (recovered != null) listOf(recovered) + orderedRecognized
                            else orderedRecognized

                        applyHistory(
                            corrected,
                            complete = recovered != null
                        )
                        finish(onResult, buildResult(live))
                    }
                } else {
                    applyHistory(orderedRecognized, complete = true)
                    finish(onResult, buildResult(live))
                }
            }
            .addOnFailureListener {
                finish(onResult, null)
            }
    }

    private fun recoverFirstCell(
        bitmap: Bitmap,
        rowY: Int,
        rowHeight: Int,
        regex: Regex,
        callback: (Double?) -> Unit
    ) {
        val x = 0
        val y = max(0, rowY - rowHeight)
        val w = min((bitmap.width * 0.18).toInt().coerceAtLeast(120), bitmap.width)
        val h = min((rowHeight * 3).coerceAtLeast(80), bitmap.height - y)

        if (w <= 0 || h <= 0) {
            callback(null)
            return
        }

        val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
        val scaled = Bitmap.createScaledBitmap(
            crop,
            crop.width * 4,
            crop.height * 4,
            true
        )
        crop.recycle()

        firstCellRecognizer.process(InputImage.fromBitmap(scaled, 0))
            .addOnSuccessListener { text ->
                val values = regex.findAll(text.text)
                    .mapNotNull { match ->
                        match.groupValues[1]
                            .replace(',', '.')
                            .toDoubleOrNull()
                    }
                    .filter { it in 1.0..1000.0 }
                    .toList()

                // Nessa pequena região deve existir no máximo o primeiro multiplicador.
                callback(values.firstOrNull())
            }
            .addOnFailureListener {
                callback(null)
            }
            .addOnCompleteListener {
                scaled.recycle()
            }
    }

    private fun applyHistory(values: List<Double>, complete: Boolean) {
        if (values.isEmpty()) return

        historyComplete = complete
        val snapshotKey = values
            .take(64)
            .joinToString("|") { "%.2f".format(it) }

        if (snapshotKey == lastSnapshotKey) return

        visibleHistory = values
        lastSnapshotKey = snapshotKey
        currentRiskTarget = calculateRiskTarget(visibleHistory)
        nextEntrySignal =
            historyComplete && calculateNextEntrySignal(visibleHistory)
    }

    private fun finish(
        onResult: (Result?) -> Unit,
        result: Result?
    ) {
        busy = false
        onResult(result)
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
            !historyComplete -> "LEITURA PARCIAL — AGUARDE"
            live != null && live >= currentRiskTarget -> "SAIR AGORA"
            live != null && live >= currentRiskTarget - 0.10 -> "PREPARE-SE PARA SAIR"
            live != null -> "MANTER"
            rounds < 10 -> "COLETANDO"
            nextEntrySignal -> "ENTRAR NA PRÓXIMA RODADA"
            else -> "NÃO ENTRAR NA PRÓXIMA"
        }

        return Result(
            latest = if (historyComplete) visibleHistory.firstOrNull() else null,
            liveMultiplier = live,
            riskTarget = currentRiskTarget,
            action = action,
            rounds = rounds + if (historyComplete) 0 else 1,
            under2Pct = under2,
            over2Pct = over2,
            over3Pct = over3,
            over5Pct = over5,
            over10Pct = over10,
            lowStreak = lowStreak,
            volatility = volatility,
            last20 = sample,
            historyComplete = historyComplete
        )
    }

    private fun percent(values: List<Double>, predicate: (Double) -> Boolean): Int {
        if (values.isEmpty()) return 0
        return ((values.count(predicate) * 100.0) / values.size).toInt()
    }

    fun close() {
        recognizer.close()
        firstCellRecognizer.close()
    }
}
