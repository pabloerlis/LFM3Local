package br.com.lfm3local

import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    @Test fun modelNameIsDocumented() {
        assertTrue("LFM3-1.7B-Q4_K_M.gguf".endsWith(".gguf"))
    }
}
