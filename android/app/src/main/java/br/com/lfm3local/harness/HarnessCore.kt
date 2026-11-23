package br.com.lfm3local.harness

import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Pure Kotlin; tools have no filesystem, network or shell access. */
data class ToolInvocation(val name: String, val arguments: Map<String, String>)
data class ToolDefinition(val name: String, val description: String, val schema: String)
data class ToolOutcome(val success: Boolean, val message: String)
sealed class CallParse {
    data object None : CallParse()
    data class Valid(val call: ToolInvocation) : CallParse()
    data class Invalid(val reason: String) : CallParse()
}

object Lfm3ToolParser {
    private val block = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val function = Regex("^<function=([a-z][a-z0-9_]*)>\\s*(.*?)\\s*</function>$", RegexOption.DOT_MATCHES_ALL)
    private val parameter = Regex("<parameter=([a-z][a-z0-9_]*)>\\s*(.*?)\\s*</parameter>", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): CallParse {
        if (!text.contains("<tool_call")) return CallParse.None
        if (text.length > 16384) return CallParse.Invalid("Resposta excede o limite do parser")
        val blocks = block.findAll(text).toList()
        if (blocks.size != 1) return CallParse.Invalid("Esperada uma chamada completa por geração")
        val remaining = text.removeRange(blocks[0].range)
        if (remaining.contains("<tool_call") || remaining.contains("</tool_call>"))
            return CallParse.Invalid("Chamada de ferramenta incompleta ou adicional")
        val fn = function.matchEntire(blocks[0].groupValues[1].trim())
            ?: return CallParse.Invalid("Tag function inválida")
        val rest = fn.groupValues[2].trim()
        if (rest.length > 4096) return CallParse.Invalid("Argumentos muito longos")
        val arguments = linkedMapOf<String, String>()
        var cursor = 0
        for (p in parameter.findAll(rest)) {
            if (rest.substring(cursor, p.range.first).isNotBlank())
                return CallParse.Invalid("Conteúdo inesperado entre parâmetros")
            val name = p.groupValues[1]
            val value = p.groupValues[2].trim()
            if (value.length > 512) return CallParse.Invalid("Parâmetro extenso demais")
            if (name in arguments) return CallParse.Invalid("Parâmetro duplicado: " + name)
            arguments[name] = value
            cursor = p.range.last + 1
        }
        if (rest.substring(cursor).isNotBlank()) return CallParse.Invalid("XML incompleto")
        return CallParse.Valid(ToolInvocation(fn.groupValues[1], arguments))
    }

    /** Hides tool XML, unfinished calls and internal thinking from the chat stream. */
    fun visibleText(raw: String): String {
        var text = Regex("<think>[\\s\\S]*?</think>").replace(raw, "")
        val thinking = text.indexOf("<think>")
        if (thinking >= 0) text = text.substring(0, thinking)
        text = block.replace(text, "")
        val prefixes = listOf("<tool_call", "<tool_cal", "<tool_ca", "<tool_c",
            "<tool_", "<tool", "<too", "<to", "<t")
        val unfinished = prefixes.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (unfinished != null) text = text.substring(0, unfinished)
        return text.trimStart()
    }
}

class LocalToolRegistry(private val clock: Clock = Clock.systemDefaultZone()) {
    val definitions = listOf(
        ToolDefinition("calculator",
            "Avalia uma expressão matemática com +, -, *, / e parênteses.",
            """{"type":"object","properties":{"expression":{"type":"string","description":"Expressão aritmética, ex.: (17*3)+2"}},"required":["expression"],"additionalProperties":false}"""
        ),
        ToolDefinition("get_current_datetime",
            "Retorna a data e hora reais do dispositivo com fuso horário.",
            """{"type":"object","properties":{},"additionalProperties":false}"""
        )
    )

    fun execute(call: ToolInvocation): ToolOutcome = try {
        when (call.name) {
            "calculator" -> {
                require(call.arguments.keys == setOf("expression")) {
                    "calculator requer somente o parâmetro expression"
                }
                ToolOutcome(true, ArithmeticEvaluator.evaluate(call.arguments.getValue("expression")))
            }
            "get_current_datetime" -> {
                require(call.arguments.isEmpty()) { "get_current_datetime não aceita parâmetros" }
                ToolOutcome(true, ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
            }
            else -> ToolOutcome(false, "Ferramenta não registrada: " + call.name)
        }
    } catch (e: IllegalArgumentException) {
        ToolOutcome(false, e.message ?: "Argumentos inválidos")
    } catch (e: ArithmeticException) {
        ToolOutcome(false, e.message ?: "Erro matemático")
    }
}

/** Bounded recursive-descent arithmetic; no eval, scripts or code execution. */
object ArithmeticEvaluator {
    private val ctx = MathContext.DECIMAL64
    private val maxValue = BigDecimal("1000000000000000000")

    fun evaluate(text: String): String {
        require(text.length in 1..160) { "Expressão deve ter de 1 a 160 caracteres" }
        require(text.all { it.isDigit() || it in " .,+-*/()\t\n" }) {
            "Use somente números e + - * / ( )"
        }
        val parser = Parser(text)
        val result = parser.expression()
        parser.skipSpaces()
        require(parser.atEnd()) { "Token inesperado na posição " + parser.position() }
        require(result.abs() <= maxValue) { "Resultado excede o limite" }
        return result.stripTrailingZeros().toPlainString()
    }

    private class Parser(private val source: String) {
        var i = 0
        var operations = 0
        var nesting = 0
        fun position() = i
        fun atEnd() = i >= source.length
        fun skipSpaces() { while (!atEnd() && source[i].isWhitespace()) i++ }

        private fun take(ch: Char): Boolean {
            skipSpaces()
            if (!atEnd() && source[i] == ch) { i++; return true }
            return false
        }
        private fun bound(n: BigDecimal): BigDecimal {
            require(++operations <= 80) { "Operações em excesso" }
            require(n.abs() <= maxValue) { "Valor excede o limite" }
            return n
        }
        fun expression(): BigDecimal {
            var left = term()
            while (true) {
                left = when {
                    take('+') -> bound(left.add(term(), ctx))
                    take('-') -> bound(left.subtract(term(), ctx))
                    else -> return left
                }
            }
        }
        private fun term(): BigDecimal {
            var left = factor()
            while (true) {
                left = when {
                    take('*') -> bound(left.multiply(factor(), ctx))
                    take('/') -> {
                        val denominator = factor()
                        require(denominator.signum() != 0) { "Divisão por zero" }
                        bound(left.divide(denominator, ctx))
                    }
                    else -> return left
                }
            }
        }
        private fun factor(): BigDecimal {
            skipSpaces()
            if (take('+')) return factor()
            if (take('-')) return bound(factor().negate())
            if (take('(')) {
                require(++nesting <= 16) { "Parênteses aninhados em excesso" }
                val value = expression()
                require(take(')')) { "Parêntese de fechamento ausente" }
                nesting--
                return value
            }
            skipSpaces()
            val start = i
            var decimal = false
            while (!atEnd()) {
                when {
                    source[i].isDigit() -> i++
                    (source[i] == '.' || source[i] == ',') && !decimal -> { decimal = true; i++ }
                    else -> break
                }
            }
            require(i > start) { "Esperado número na posição " + i }
            val raw = source.substring(start, i).replace(',', '.')
            require(raw.any { it.isDigit() }) { "Número inválido" }
            return bound(BigDecimal(raw, ctx))
        }
    }
}

class ToolTurnPolicy(
    private val tools: LocalToolRegistry,
    private val maxCalls: Int = 2,
    private val skills: SkillCatalog = SkillCatalog()
) {
    private var attempts = 0
    private val seen = mutableSetOf<String>()
    fun checkAndRun(call: ToolInvocation): ToolOutcome {
        if (attempts >= maxCalls) return ToolOutcome(false, "Limite de ferramentas por turno")
        attempts++
        val isCore = tools.definitions.any { it.name == call.name }
        val isSkill = skills.definitions().any { it.name == call.name }
        if (!isCore && !isSkill) {
            return ToolOutcome(false, "Ferramenta não autorizada: " + call.name)
        }
        val signature = call.name + ":" + call.arguments.entries.sortedBy { it.key }.toString()
        if (!seen.add(signature)) return ToolOutcome(false, "Chamada repetida detectada")
        return if (isCore) tools.execute(call) else skills.run(call)
    }
    fun attempts() = attempts
}
