package br.com.lfm3local.harness

import org.junit.Assert.*
import org.junit.Test

class ContextCheckpointTest {
    private val messages = listOf(
        ContextTurn(1, "user", "Quero trocar a suspensão"),
        ContextTurn(2, "assistant", "Qual o orçamento?")
    )

    @Test fun sameTranscriptReusesRevisionAcrossCalls() {
        assertEquals(ContextCheckpoint.revision(messages), ContextCheckpoint.revision(messages.toList()))
    }

    @Test fun changesInBodyInvalidateRevisionEvenWithSameDatabaseIds() {
        val changed = messages.toMutableList()
        changed[1] = changed[1].copy(body = "Qual o modelo?")
        assertNotEquals(ContextCheckpoint.revision(messages), ContextCheckpoint.revision(changed))
    }

    @Test fun roleAndOrderingArePartOfRevision() {
        assertNotEquals(ContextCheckpoint.revision(messages), ContextCheckpoint.revision(messages.reversed()))
        val changedRole = messages.toMutableList()
        changedRole[0] = changedRole[0].copy(role = "assistant")
        assertNotEquals(ContextCheckpoint.revision(messages), ContextCheckpoint.revision(changedRole))
    }

    @Test fun configurationFingerprintChangesWithSkillAndNativeMode() {
        val a = ContextCheckpoint.configurationKey("Notas locais desativadas.", true)
        val b = ContextCheckpoint.configurationKey("Notas locais ativadas.", true)
        val c = ContextCheckpoint.configurationKey("Notas locais desativadas.", false)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test fun descriptorRejectsStaleChatOrTranscriptOrPolicy() {
        val revision = ContextCheckpoint.revision(messages)
        val key = ContextCheckpoint.configurationKey("system", false)
        val descriptor = SnapshotDescriptor(123, revision, key)
        assertTrue(descriptor.matches(123, revision, key))
        assertFalse(descriptor.matches(124, revision, key))
        assertFalse(descriptor.matches(123, revision + 1, key))
        assertFalse(descriptor.matches(123, revision, ContextCheckpoint.configurationKey("new policy", false)))
    }

    @Test fun emptyTranscriptHasStableRevision() {
        assertEquals(ContextCheckpoint.revision(emptyList()), ContextCheckpoint.revision(emptyList()))
        assertNotEquals(ContextCheckpoint.revision(emptyList()), ContextCheckpoint.revision(messages))
    }

    @Test fun unicodeAndBoundaryCharactersDoNotCollide() {
        val p1 = listOf(ContextTurn(1, "user", "a|b"), ContextTurn(2, "assistant", "ç"))
        val p2 = listOf(ContextTurn(1, "user", "a"), ContextTurn(2, "assistant", "b|ç"))
        assertNotEquals(ContextCheckpoint.revision(p1), ContextCheckpoint.revision(p2))
    }
}
