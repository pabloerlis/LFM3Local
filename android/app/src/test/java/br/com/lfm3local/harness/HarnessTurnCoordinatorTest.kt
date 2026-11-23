package br.com.lfm3local.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HarnessTurnCoordinatorTest {
    private class Backend(private val reply: String, private val toolReply: String) : TurnBackend {
        var calls = 0
        var lastName = ""
        var lastResult = ""
        override fun sendUser(text: String, maxTokens: Int): Flow<String> {
            calls++
            return flowOf(reply.take(reply.length / 2), reply.drop(reply.length / 2))
        }
        override fun sendToolResult(name: String, result: String, maxTokens: Int): Flow<String> {
            calls++
            lastName = name
            lastResult = result
            return flowOf(toolReply)
        }
    }

    @Test fun calculatorExecutionRoundTrip() = runBlocking {
        val backend = Backend(
            "<tool_call><function=calculator><parameter=expression>17*3</parameter></function></tool_call>",
            "O resultado é 51."
        )
        val events = mutableListOf<HarnessEvent>()
        var shown = ""
        val result = HarnessTurnCoordinator(backend).run(
            user = "Qual é 17*3?",
            onText = { shown = it },
            onStatus = {},
            onEvent = { events.add(it) }
        )
        assertEquals("O resultado é 51.", result.text)
        assertEquals(result.text, shown)
        assertEquals("calculator", backend.lastName)
        assertTrue(backend.lastResult.contains("51"))
        assertEquals(2, backend.calls)
        assertEquals(1, result.toolCalls)
        assertEquals(1, events.size)
        assertTrue(events.first().success)
    }

    @Test fun plainConversationNeedsNoTool() = runBlocking {
        val backend = Backend("Olá, posso ajudar.", "")
        val result = HarnessTurnCoordinator(backend).run(
            "Olá", onText = {}, onStatus = {}, onEvent = {}
        )
        assertEquals("Olá, posso ajudar.", result.text)
        assertEquals(1, backend.calls)
    }

    @Test fun noExecutionOfIncompleteXml() = runBlocking {
        val backend = Backend("<tool_call><function=calculator>", "Não deveria ocorrer")
        var events = 0
        val result = HarnessTurnCoordinator(backend).run(
            "Teste", {}, {}, { events++ }
        )
        assertTrue(result.text.contains("Chamada não executada"))
        assertEquals(0, events)
        assertEquals(1, backend.calls)
    }

    @Test fun unknownToolsNeverReachHost() = runBlocking {
        val backend = Backend(
            "<tool_call><function=shell><parameter=command>rm -rf /</parameter></function></tool_call>",
            "Não foi possível."
        )
        val events = mutableListOf<HarnessEvent>()
        HarnessTurnCoordinator(backend).run("Execute", {}, {}, { events += it })
        assertEquals(1, events.size)
        assertFalse(events.single().success)
        assertTrue(backend.lastResult.startsWith("error:"))
    }

    @Test fun deterministicClockResultPassedBack() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)
        val backend = Backend(
            "<tool_call><function=get_current_datetime></function></tool_call>",
            "Agora são 12:00."
        )
        HarnessTurnCoordinator(backend, LocalToolRegistry(clock)).run("Que horas?", {}, {}, {})
        assertTrue(backend.lastResult.contains("2026-11-23T12:00:00Z"))
    }
}
