package com.estilodocampo.gameanalyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Analyzer(context: Context) {
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
        val historyComplete: Boolean,
        val decoderState: String,
        val decoderTarget: Double,
        val decoderMatches: Int,
        val decoderHitRate: Int,
        val decoderBaseline: Int,
        val decoderLift: Int,
        val decoderQuality: Int,
        val previewLow: Double?,
        val previewMedian: Double?,
        val previewHigh: Double?,
        val previewSample: Int
    )

    private data class HistoryLine(
        val y: Int,
        val x: Int,
        val height: Int,
        val values: List<Double>
    )

    private data class Candidate(
        val distance: Double,
        val nextOutcome: Double
    )

    private data class DecoderSignal(
        val state: String,
        val target: Double,
        val matches: Int,
        val hitRate: Int,
        val baseline: Int,
        val lift: Int,
        val quality: Int,
        val previewLow: Double?,
        val previewMedian: Double?,
        val previewHigh: Double?,
        val previewSample: Int
    )

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val firstCellRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val prefs =
        context.getSharedPreferences("aviator_decoder_history", Context.MODE_PRIVATE)

    private var busy = false
    private var visibleHistory: List<Double> = emptyList()
    private val persistentHistory = mutableListOf<Double>()
    private var lastSnapshotKey = ""
    private var currentRiskTarget = 1.30
    private var historyComplete = false
    private var persistentAligned = false
    private var decoderSignal = DecoderSignal(
        state = "DECODIFICANDO",
        target = 1.30,
        matches = 0,
        hitRate = 0,
        baseline = 0,
        lift = 0,
        quality = 0,
        previewLow = null,
        previewMedian = null,
        previewHigh = null,
        previewSample = 0
    )

    init {
        loadPersistentHistory()
    }

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
                    finish(
                        onResult,
                        if (analysisHistory().isEmpty() && live == null) null
                        else buildResult(live)
                    )
                    return@addOnSuccessListener
                }

                val firstLine = sortedLines.first()
                val orderedRecognized = sortedLines.flatMap { it.values }
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
                            if (recovered != null) {
                                listOf(recovered) + orderedRecognized
                            } else {
                                orderedRecognized
                            }

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

    private fun finish(
        onResult: (Result?) -> Unit,
        result: Result?
    ) {
        busy = false
        onResult(result)
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
        val looksLowBlue = containsLowMultiplierBlue(crop)
        val filtered = keepMultiplierColorsOnly(crop)

        val scaled = Bitmap.createScaledBitmap(
            filtered,
            filtered.width * 5,
            filtered.height * 5,
            true
        )

        crop.recycle()
        filtered.recycle()

        firstCellRecognizer.process(InputImage.fromBitmap(scaled, 0))
            .addOnSuccessListener { text ->
                val direct = extractMultiplier(text.text, regex)

                if (direct != null) {
                    callback(direct)
                    return@addOnSuccessListener
                }

                if (looksLowBlue) {
                    val partialRegex =
                        Regex("""(?:[\.,]?)(\d{2})\s*[xX]""")

                    val decimals = partialRegex.find(text.text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                    if (decimals != null) {
                        callback(1.0 + decimals / 100.0)
                        return@addOnSuccessListener
                    }
                }

                callback(null)
            }
            .addOnFailureListener {
                callback(null)
            }
            .addOnCompleteListener {
                scaled.recycle()
            }
    }

    private fun extractMultiplier(
        text: String,
        regex: Regex
    ): Double? {
        return regex.findAll(text)
            .mapNotNull { match ->
                match.groupValues[1]
                    .replace(',', '.')
                    .toDoubleOrNull()
            }
            .firstOrNull { it in 1.0..1000.0 }
    }

    private fun keepMultiplierColorsOnly(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )

        val hsv = FloatArray(3)

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)

                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]

                val isBlue =
                    hue in 175f..230f &&
                        saturation >= 0.25f &&
                        value >= 0.25f

                val isPurple =
                    hue in 250f..345f &&
                        saturation >= 0.25f &&
                        value >= 0.25f

                out.setPixel(
                    x,
                    y,
                    if (isBlue || isPurple) Color.WHITE else Color.BLACK
                )
            }
        }

        return out
    }

    private fun containsLowMultiplierBlue(bitmap: Bitmap): Boolean {
        var bluePixels = 0
        var sampled = 0
        val hsv = FloatArray(3)

        val stepX = max(2, bitmap.width / 80)
        val stepY = max(2, bitmap.height / 40)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)

                if (
                    hsv[0] in 175f..225f &&
                    hsv[1] >= 0.35f &&
                    hsv[2] >= 0.35f
                ) {
                    bluePixels++
                }

                sampled++
                x += stepX
            }
            y += stepY
        }

        if (sampled == 0) return false
        return bluePixels >= 8
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

        if (!complete) {
            persistentAligned = false
            decoderSignal = DecoderSignal(
                state = "LEITURA PARCIAL",
                target = 1.30,
                matches = 0,
                hitRate = 0,
                baseline = 0,
                lift = 0,
                quality = 0,
                previewLow = null,
                previewMedian = null,
                previewHigh = null,
                previewSample = 0
            )
            currentRiskTarget = calculateRiskTarget(visibleHistory)
            return
        }

        mergePersistentHistory(values)

        val history = analysisHistory()
        decoderSignal = decodeSignal(history)

        currentRiskTarget = when {
            decoderSignal.state == "SINAL FORTE" -> decoderSignal.target
            decoderSignal.state == "SINAL MODERADO" -> decoderSignal.target
            else -> calculateRiskTarget(history)
        }
    }

    private fun mergePersistentHistory(snapshot: List<Double>) {
        if (snapshot.isEmpty()) return

        if (persistentHistory.isEmpty()) {
            persistentHistory.addAll(snapshot.take(120))
            persistentAligned = true
            savePersistentHistory()
            return
        }

        val maxSearch = minOf(16, snapshot.size)
        var overlapStart = -1

        for (i in 0 until maxSearch) {
            if (!near(snapshot[i], persistentHistory[0])) continue

            val verifyCount =
                minOf(5, snapshot.size - i, persistentHistory.size)

            if (verifyCount < 3) continue

            var verified = 0
            for (j in 0 until verifyCount) {
                if (near(snapshot[i + j], persistentHistory[j])) {
                    verified++
                }
            }

            if (verified >= 3) {
                overlapStart = i
                break
            }
        }

        when {
            overlapStart == 0 -> {
                persistentAligned = true
            }

            overlapStart > 0 -> {
                val merged =
                    snapshot.take(overlapStart) + persistentHistory

                persistentHistory.clear()
                persistentHistory.addAll(merged.take(1000))
                persistentAligned = true
                savePersistentHistory()
            }

            else -> {
                // O histórico salvo não pertence mais à grade atual (sessão nova,
                // OCR antigo incorreto ou longo intervalo sem observar o jogo).
                // A tela atual passa a ser a fonte de verdade; nunca misturamos
                // estatísticas antigas desalinhadas com a leitura atual.
                persistentHistory.clear()
                persistentHistory.addAll(snapshot.take(1000))
                persistentAligned = true
                savePersistentHistory()
            }
        }
    }

    private fun analysisHistory(): List<Double> {
        if (!historyComplete || !persistentAligned) {
            return visibleHistory
        }

        return if (persistentHistory.isNotEmpty()) {
            persistentHistory
        } else {
            visibleHistory
        }
    }

    private fun loadPersistentHistory() {
        val raw = prefs.getString("history", "") ?: ""
        if (raw.isBlank()) return

        persistentHistory.clear()
        persistentHistory.addAll(
            raw.split(';')
                .mapNotNull { it.toDoubleOrNull() }
                .filter { it in 1.0..1000.0 }
                .take(1000)
        )
    }

    private fun savePersistentHistory() {
        prefs.edit()
            .putString(
                "history",
                persistentHistory
                    .take(1000)
                    .joinToString(";") { it.toString() }
            )
            .apply()
    }

    private fun decodeSignal(historyNewestFirst: List<Double>): DecoderSignal {
        if (historyNewestFirst.size < 30) {
            return DecoderSignal(
                state = "DECODIFICANDO",
                target = 1.30,
                matches = 0,
                hitRate = 0,
                baseline = 0,
                lift = 0,
                quality = minOf(45, historyNewestFirst.size),
                previewLow = null,
                previewMedian = null,
                previewHigh = null,
                previewSample = 0
            )
        }

        val contextSize = 4
        val current = historyNewestFirst.take(contextSize)
        val candidates = mutableListOf<Candidate>()
        val maxOffset = minOf(historyNewestFirst.size - contextSize, 400)

        for (offset in 1 until maxOffset) {
            val pastContext =
                historyNewestFirst.subList(offset, offset + contextSize)

            val distance = contextDistance(current, pastContext)

            if (distance <= 3.20) {
                candidates.add(
                    Candidate(
                        distance = distance,
                        nextOutcome = historyNewestFirst[offset - 1]
                    )
                )
            }
        }

        val selected = candidates
            .sortedBy { it.distance }
            .take(18)

        val previewValues = selected
            .map { minOf(it.nextOutcome, 20.0) }
            .sorted()

        val previewLow =
            if (previewValues.size >= 6) quantile(previewValues, 0.25) else null
        val previewMedian =
            if (previewValues.size >= 6) quantile(previewValues, 0.50) else null
        val previewHigh =
            if (previewValues.size >= 6) quantile(previewValues, 0.75) else null

        if (selected.size < 6) {
            return DecoderSignal(
                state = "SEM SINAL",
                target = calculateRiskTarget(historyNewestFirst),
                matches = selected.size,
                hitRate = 0,
                baseline = 0,
                lift = 0,
                quality = minOf(55, selected.size * 8),
                previewLow = previewLow,
                previewMedian = previewMedian,
                previewHigh = previewHigh,
                previewSample = selected.size
            )
        }

        val baselineSample =
            historyNewestFirst.drop(contextSize).take(250)

        val targets = listOf(1.30, 1.40, 1.50, 1.70, 2.00)
        var best: DecoderSignal? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (target in targets) {
            val hits =
                selected.count { it.nextOutcome >= target }

            val hitRate =
                ((hits * 100.0) / selected.size).toInt()

            val baseline =
                percent(baselineSample) { it >= target }

            val lift = hitRate - baseline
            val lower = wilsonLower(hits, selected.size)
            val breakEven = 1.0 / target

            val similarity =
                selected.map { it.distance }.average()

            val quality = (
                minOf(45.0, selected.size * 3.0) +
                    maxOf(0.0, 28.0 - similarity * 7.0) +
                    minOf(27.0, maxOf(0, lift) * 1.5)
                ).toInt().coerceIn(0, 100)

            val score =
                (lower * target - 1.0) * 100.0 +
                    lift * 0.35 +
                    quality * 0.08

            val state = when {
                selected.size >= 12 &&
                    lift >= 10 &&
                    lower >= breakEven - 0.02 &&
                    quality >= 68 -> "SINAL FORTE"

                selected.size >= 10 &&
                    lift >= 7 &&
                    lower >= breakEven - 0.05 &&
                    quality >= 60 -> "SINAL MODERADO"

                else -> "SEM SINAL"
            }

            val signal = DecoderSignal(
                state = state,
                target = target,
                matches = selected.size,
                hitRate = hitRate,
                baseline = baseline,
                lift = lift,
                quality = quality,
                previewLow = previewLow,
                previewMedian = previewMedian,
                previewHigh = previewHigh,
                previewSample = selected.size
            )

            if (
                score > bestScore ||
                (
                    abs(score - bestScore) < 0.0001 &&
                        (best == null || target < best!!.target)
                    )
            ) {
                bestScore = score
                best = signal
            }
        }

        return best ?: DecoderSignal(
            state = "SEM SINAL",
            target = calculateRiskTarget(historyNewestFirst),
            matches = selected.size,
            hitRate = 0,
            baseline = 0,
            lift = 0,
            quality = 0,
            previewLow = previewLow,
            previewMedian = previewMedian,
            previewHigh = previewHigh,
            previewSample = selected.size
        )
    }

    private fun quantile(
        sortedValues: List<Double>,
        q: Double
    ): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues[0]

        val position = (sortedValues.size - 1) * q
        val lower = position.toInt()
        val upper = minOf(lower + 1, sortedValues.lastIndex)
        val fraction = position - lower

        return sortedValues[lower] * (1.0 - fraction) +
            sortedValues[upper] * fraction
    }

    private fun contextDistance(
        current: List<Double>,
        past: List<Double>
    ): Double {
        val weights = listOf(1.60, 1.30, 1.00, 0.80)
        var total = 0.0

        for (i in current.indices) {
            val a = current[i]
            val b = past[i]

            val bucketGap =
                abs(bucket(a) - bucket(b)).coerceAtMost(2)

            val logGap =
                abs(ln(minOf(a, 20.0)) - ln(minOf(b, 20.0)))

            total +=
                weights[i] * bucketGap * 0.72 +
                    logGap * 0.28
        }

        return total
    }

    private fun bucket(value: Double): Int {
        return when {
            value < 1.30 -> 0
            value < 2.00 -> 1
            value < 3.00 -> 2
            value < 5.00 -> 3
            else -> 4
        }
    }

    private fun wilsonLower(hits: Int, total: Int): Double {
        if (total <= 0) return 0.0

        val z = 1.64
        val n = total.toDouble()
        val p = hits / n
        val z2 = z * z

        val center = p + z2 / (2.0 * n)
        val margin =
            z * sqrt(
                (p * (1.0 - p) + z2 / (4.0 * n)) / n
            )
        val denominator = 1.0 + z2 / n

        return ((center - margin) / denominator)
            .coerceIn(0.0, 1.0)
    }

    private fun near(a: Double, b: Double): Boolean {
        return abs(a - b) < 0.005
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

    private fun buildResult(live: Double?): Result {
        val history = analysisHistory()
        val sample = history.take(20)
        val rounds = history.size

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

            live != null && live >= currentRiskTarget ->
                "SAIR AGORA"

            live != null && live >= currentRiskTarget - 0.10 ->
                "PREPARE-SE PARA SAIR"

            live != null ->
                "MANTER"

            decoderSignal.state == "SINAL FORTE" ->
                "ENTRADA EXPERIMENTAL — PRÓXIMA"

            decoderSignal.state == "SINAL MODERADO" ->
                "AGUARDE — SINAL EM FORMAÇÃO"

            decoderSignal.state == "DECODIFICANDO" ->
                "DECODIFICANDO HISTÓRICO"

            else ->
                "SEM SINAL"
        }

        return Result(
            latest = if (historyComplete) history.firstOrNull() else null,
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
            last20 = sample,
            historyComplete = historyComplete,
            decoderState = decoderSignal.state,
            decoderTarget = decoderSignal.target,
            decoderMatches = decoderSignal.matches,
            decoderHitRate = decoderSignal.hitRate,
            decoderBaseline = decoderSignal.baseline,
            decoderLift = decoderSignal.lift,
            decoderQuality = decoderSignal.quality,
            previewLow = decoderSignal.previewLow,
            previewMedian = decoderSignal.previewMedian,
            previewHigh = decoderSignal.previewHigh,
            previewSample = decoderSignal.previewSample
        )
    }

    private fun percent(
        values: List<Double>,
        predicate: (Double) -> Boolean
    ): Int {
        if (values.isEmpty()) return 0
        return ((values.count(predicate) * 100.0) / values.size).toInt()
    }

    fun close() {
        recognizer.close()
        firstCellRecognizer.close()
    }
}
