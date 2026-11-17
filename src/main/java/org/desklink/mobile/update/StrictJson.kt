/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The strict JSON profile the signed manifest is read with (docs/UPDATES.md 2.1; the same profile the desk and the Python
 * verifier use). org.json is not used because it coerces types, accepts duplicate keys and recurses without a limit.
 *
 * Accepted: UTF-8 without a BOM, RFC 8259 grammar only (no NaN, Infinity, comments, trailing commas or raw control
 * characters), no duplicate object keys, no lone surrogate (raw or `\u` escaped), integers within the i64 range, nesting
 * depth at most [MAX_DEPTH], nothing after the value. Values: Map, List, String, Long, Double, Boolean, null. A document that
 * breaks any rule is `null` (never an exception).
 */
internal object StrictJson {
    const val MAX_DEPTH = 32

    private class Bad : Exception(null, null, false, false)

    fun parse(bytes: ByteArray): Any? {
        val r = try { parseDocument(bytes) } catch (_: Throwable) { NONE }
        return if (r === NONE) null else r
    }

    private val NONE = Any()

    private fun parseDocument(bytes: ByteArray): Any? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) throw Bad()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return P(text).document()
    }

    private class P(val s: String) {
        var i = 0

        fun document(): Any? {
            ws()
            val v = value(0)
            ws()
            if (i != s.length) throw Bad()
            return v
        }

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

        fun value(depth: Int): Any? {
            if (i >= s.length) throw Bad()
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Bad()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw Bad()
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            if (depth > MAX_DEPTH) throw Bad()
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws()
                if (peek() != '"') throw Bad()
                val k = str()
                ws()
                if (peek() != ':') throw Bad()
                i++
                ws()
                if (m.containsKey(k)) throw Bad() // duplicate key
                m[k] = value(depth)
                ws()
                when (peek()) { ',' -> i++; '}' -> { i++; return m }; else -> throw Bad() }
            }
        }

        fun arr(depth: Int): List<Any?> {
            if (depth > MAX_DEPTH) throw Bad()
            i++
            val l = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                ws()
                l += value(depth)
                ws()
                when (peek()) { ',' -> i++; ']' -> { i++; return l }; else -> throw Bad() }
            }
        }

        fun peek(): Char = if (i < s.length) s[i] else throw Bad()

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when {
                    c == '"' -> break
                    c < ' ' -> throw Bad()
                    c == '\\' -> {
                        when (val e = peek().also { i++ }) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { if (i + 4 > s.length) throw Bad(); val h = s.substring(i, i + 4); if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw Bad(); sb.append(h.toInt(16).toChar()); i += 4 }
                            else -> throw Bad()
                        }
                    }
                    else -> sb.append(c)
                }
            }
            val out = sb.toString()
            // No lone surrogate, however it was written.
            var k = 0
            while (k < out.length) {
                val c = out[k]
                if (Character.isHighSurrogate(c)) { if (k + 1 >= out.length || !Character.isLowSurrogate(out[k + 1])) throw Bad(); k += 2 }
                else if (Character.isLowSurrogate(c)) throw Bad() else k++
            }
            return out
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) throw Bad()
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else throw Bad()
            var integer = true
            if (i < s.length && s[i] == '.') { integer = false; i++; val d = i; while (i < s.length && s[i] in '0'..'9') i++; if (i == d) throw Bad() }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integer = false; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i; while (i < s.length && s[i] in '0'..'9') i++; if (i == d) throw Bad()
            }
            val t = s.substring(start, i)
            return if (integer) (t.toLongOrNull() ?: throw Bad()) else t.toDouble().also { if (it.isNaN() || it.isInfinite()) throw Bad() }
        }
    }
}
