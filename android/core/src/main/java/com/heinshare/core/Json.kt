package com.heinshare.core

/**
 * 极简 JSON 解析（只读对象/数组/字符串/数字）。
 *
 * 发现包和通道帧里的 JSON 只需要取值，不需要写回，所以不引第三方库。
 */
class Json private constructor(private val value: Any?) {

    fun str(key: String): String? = (value as? Map<*, *>)?.get(key) as? String

    fun num(key: String): Long? {
        val v = (value as? Map<*, *>)?.get(key) ?: return null
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    fun array(key: String): List<Long>? {
        val v = (value as? Map<*, *>)?.get(key) as? List<*> ?: return null
        return v.mapNotNull {
            when (it) {
                is Number -> it.toLong()
                is String -> it.toLongOrNull()
                else -> null
            }
        }
    }

    fun obj(key: String): Json? {
        val v = (value as? Map<*, *>)?.get(key) ?: return null
        return Json(v)
    }

    /** 原始键值表（测试与调试用）。 */
    fun asMap(): Map<String, Any?> {
        val m = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>(m.size)
        for ((k, v) in m) out[k.toString()] = v
        return out
    }

    companion object {
        fun parseObject(text: String): Json? {
            val p = Parser(text)
            return try {
                p.skipWs()
                val v = p.parseValue()
                p.skipWs()
                if (v is Map<*, *>) Json(v) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) throw IllegalArgumentException("JSON 提前结束")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(word: String) {
            if (!text.startsWith(word, pos)) throw IllegalArgumentException("JSON 字面量错误")
            pos += word.length
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') throw IllegalArgumentException("JSON 缺 ':'")
                pos++
                map[key] = parseValue()
                skipWs()
                if (pos >= text.length) throw IllegalArgumentException("JSON 提前结束")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("JSON 对象格式错误")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (pos >= text.length) throw IllegalArgumentException("JSON 提前结束")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("JSON 数组格式错误")
                }
            }
        }

        private fun parseString(): String {
            if (pos >= text.length || text[pos] != '"') throw IllegalArgumentException("JSON 字符串错误")
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == '"') { pos++; return sb.toString() }
                if (c == '\\') {
                    pos++
                    if (pos >= text.length) break
                    when (text[pos]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            if (pos + 4 >= text.length) throw IllegalArgumentException("JSON \\u 错误")
                            sb.append(text.substring(pos + 1, pos + 5).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(text[pos])
                    }
                    pos++
                } else {
                    sb.append(c)
                    pos++
                }
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "-+.eE")) pos++
            if (start == pos) throw IllegalArgumentException("JSON 数字错误")
            return text.substring(start, pos).toDouble()
        }
    }
}
