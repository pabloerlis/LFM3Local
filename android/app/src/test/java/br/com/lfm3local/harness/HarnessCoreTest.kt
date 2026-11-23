package br.com.lfm3local.harness

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class HarnessCoreTest {
    @Test fun parsesOfficialLiquidAiXml() {
        val xml = """<think>calcular</think><tool_call>
<function=calculator>
<parameter=expression>
(17*3)+2
</parameter>
</function>
</tool_call>"""
        val parsed = Lfm3ToolParser.parse(xml)
        assertTrue(parsed is CallParse.Valid)
        assertEquals("(17*3)+2", (parsed as CallParse.Valid).call.arguments["expression"])
        assertEquals("", Lfm3ToolParser.visibleText(xml))
    }

    @Test fun noCallInNormalChat() {
        assertEquals(CallParse.None, Lfm3ToolParser.parse("Tudo certo."))
        assertEquals("Tudo certo.", Lfm3ToolParser.visibleText("Tudo certo."))
    }

    @Test fun malformedAndDuplicatedXmlAreRejected() {
        assertTrue(Lfm3ToolParser.parse("<tool_call><function=calculator>") is CallParse.Invalid)
        val duplicated = "<tool_call><function=calculator><parameter=expression>2</parameter><parameter=expression>3</parameter></function></tool_call>"
        assertTrue(Lfm3ToolParser.parse(duplicated) is CallParse.Invalid)
        val double = "<tool_call><function=calculator></function></tool_call><tool_call><function=calculator></function></tool_call>"
        assertTrue(Lfm3ToolParser.parse(double) is CallParse.Invalid)
    }

    @Test fun partialToolCallIsHiddenInStream() {
        assertEquals("Resposta: ", Lfm3ToolParser.visibleText("Resposta: <tool_call><function=cal"))
        assertEquals("", Lfm3ToolParser.visibleText("<think>oculto"))
        assertEquals("Olá", Lfm3ToolParser.visibleText("<think>oculto</think>Olá"))
    }

    @Test fun arithmeticPrecedenceUnaryAndDecimal() {
        assertEquals("53", ArithmeticEvaluator.evaluate("(17*3)+2"))
        assertEquals("7", ArithmeticEvaluator.evaluate("1+2*3"))
        assertEquals("-2.5", ArithmeticEvaluator.evaluate("-(5/2)"))
        assertEquals("0.3", ArithmeticEvaluator.evaluate("0,1+0,2"))
    }

    @Test fun divisionByZeroAndInjectionRejected() {
        for (expr in listOf("1/0", "2+3)", "2+(", "Math.sqrt(4)", "2;rm -rf /", "3**2")) {
            try {
                ArithmeticEvaluator.evaluate(expr)
                fail("Should reject " + expr)
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun dateTimeIsClockBased() {
        val fixed = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneId.of("America/Sao_Paulo"))
        val result = LocalToolRegistry(fixed).execute(ToolInvocation("get_current_datetime", emptyMap()))
        assertTrue(result.success)
        assertTrue(result.message.contains("2026-11-23T09:00:00-03:00"))
    }

    @Test fun unsafeUnknownDuplicateAndLimitAreRejected() {
        val policy = ToolTurnPolicy(LocalToolRegistry())
        assertFalse(policy.checkAndRun(ToolInvocation("shell", mapOf("command" to "pwd"))).success)
        val c = ToolInvocation("calculator", mapOf("expression" to "2+2"))
        assertTrue(policy.checkAndRun(c).success)
        assertFalse(policy.checkAndRun(c).success)
        assertFalse(policy.checkAndRun(ToolInvocation("get_current_datetime", emptyMap())).success)
        assertEquals(2, policy.attempts())
    }

    @Test fun strictArguments() {
        val tools = LocalToolRegistry()
        assertFalse(tools.execute(ToolInvocation("get_current_datetime", mapOf("bad" to "x"))).success)
        assertFalse(tools.execute(ToolInvocation("calculator", mapOf("expression" to "2+2", "extra" to "x"))).success)
    }
    @Test fun nativePromptDoesNotDuplicateJsonToolSchemas() {
        val compat = harnessSystemPrompt()
        val native = harnessSystemPrompt(nativeToolSchemas = true)
        assertTrue(compat.contains("additionalProperties"))
        assertFalse(native.contains("additionalProperties"))
        assertTrue(native.contains("Template") || native.contains("template"))
        assertTrue(native.length < compat.length)
    }

    @Test fun nativePromptListsEnabledSkillWithoutEmbeddingJson() {
        val source = object : NoteSource {
            override fun allNotes() = listOf(NoteRecord(1, "Oficina", "Revisar multímetro.", 1L))
        }
        val skills = SkillCatalog(listOf(NotesSkill(source)))
        val enabled = harnessSystemPrompt(skills, nativeToolSchemas = true)
        val disabled = harnessSystemPrompt(SkillCatalog(), nativeToolSchemas = true)
        assertTrue(enabled.contains("search_local_notes"))
        assertFalse(disabled.contains("search_local_notes"))
        assertFalse(enabled.contains("\"properties\""))
    }

}
