package com.estilodocampo.gameanalyzer

import android.graphics.Bitmap
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.min

class Analyzer(profileIndex: Int) {
    data class Result(
        val label: String,
        val score: Int,
        val matches: Int,
        val rounds: Int
    )

    data class Profile(
        val rows: Int,
        val cols: Int,
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double
    )

    private val profile = when (profileIndex) {
        2 -> Profile(5, 6, .03, .34, .94, .48)
        1 -> Profile(3, 5, .03, .43, .94, .36)
        else -> Profile(3, 5, .013, .519, .974, .272)
    }

    private val history = mutableListOf<List<String>>()
    private var previous: List<String>? = null
    private var wasMoving = false
    private var stableFrames = 0

    fun process(bitmap: Bitmap): Result? {
        val sig = signature(bitmap)
        val prev = previous

        if (prev == null) {
            previous = sig
            return null
        }

        val distance = sigDistance(sig, prev)
        val moving = distance > 850

        if (moving) {
            wasMoving = true
            stableFrames = 0
        } else {
            stableFrames++
        }

        var result: Result? = null

        if (wasMoving && stableFrames >= 2) {
            history.add(sig)
            if (history.size > 500) history.removeAt(0)

            val comparisons = history.dropLast(1)
                .map { sigDistance(sig, it) }
                .sorted()

            val near = comparisons.take(10).count { it <= 1500 }

            // O estado exibido vale para o PRÓXIMO giro:
            // - COLETANDO: ainda juntando contexto.
            // - SEM EVIDÊNCIA: histórico suficiente, mas sem repetição útil agora.
            // - SINAL EM FORMAÇÃO: contexto atual começa a se repetir.
            // - SINAL DE TESTE — PRÓXIMO GIRO: repetição forte o bastante para destacar
            //   o próximo giro como teste experimental. Não representa garantia de ganho.
            val score = min(100, 20 + near * 10 + min(history.size, 20))

            val label = when {
                history.size < 5 -> "COLETANDO"
                near >= 6 -> "SINAL DE TESTE — PRÓXIMO GIRO"
                near >= 3 -> "SINAL EM FORMAÇÃO"
                else -> "SEM EVIDÊNCIA"
            }

            result = Result(label, score, near, history.size)
            wasMoving = false
            stableFrames = 0
        }

        previous = sig
        return result
    }

    private fun signature(bitmap: Bitmap): List<String> {
        val gx = (bitmap.width * profile.x).toInt()
        val gy = (bitmap.height * profile.y).toInt()
        val gw = (bitmap.width * profile.w).toInt()
        val gh = (bitmap.height * profile.h).toInt()

        val out = mutableListOf<String>()
        val cw = gw.toDouble() / profile.cols
        val ch = gh.toDouble() / profile.rows

        for (r in 0 until profile.rows) {
            for (c in 0 until profile.cols) {
                val x = (gx + c * cw + cw * .08).toInt()
                val y = (gy + r * ch + ch * .08).toInt()
                val w = max(8, (cw * .84).toInt())
                val h = max(8, (ch * .84).toInt())
                out.add(aHash(bitmap, x, y, w, h))
            }
        }

        return out
    }

    private fun aHash(bitmap: Bitmap, x0: Int, y0: Int, w0: Int, h0: Int): String {
        val x = max(0, min(x0, bitmap.width - 1))
        val y = max(0, min(y0, bitmap.height - 1))
        val w = max(1, min(w0, bitmap.width - x))
        val h = max(1, min(h0, bitmap.height - y))

        val values = IntArray(256)
        var sum = 0

        for (yy in 0 until 16) {
            for (xx in 0 until 16) {
                val px = x + min(w - 1, xx * w / 16)
                val py = y + min(h - 1, yy * h / 16)
                val color = bitmap.getPixel(px, py)
                val lum = (
                    (color shr 16 and 255) * 299 +
                    (color shr 8 and 255) * 587 +
                    (color and 255) * 114
                ) / 1000

                values[yy * 16 + xx] = lum
                sum += lum
            }
        }

        val avg = sum / 256.0
        var number = BigInteger.ZERO

        for (v in values) {
            number = number.shiftLeft(1)
            if (v >= avg) number = number.or(BigInteger.ONE)
        }

        return number.toString(16).padStart(64, '0')
    }

    private fun sigDistance(a: List<String>, b: List<String>): Int {
        var total = 0
        val n = min(a.size, b.size)

        for (i in 0 until n) {
            total += BigInteger(a[i], 16)
                .xor(BigInteger(b[i], 16))
                .bitCount()
        }

        return total
    }
}
