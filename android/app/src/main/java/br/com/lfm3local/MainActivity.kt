package br.com.lfm3local

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private data class ChatEntry(val id: Int, val mine: Boolean, val text: String)

class MainActivity : ComponentActivity() {
    private lateinit var engine: InferenceEngine
    private val messages = mutableStateListOf<ChatEntry>()
    private val status = mutableStateOf("Inicializando motor local…")
    private val ready = mutableStateOf(false)
    private val busy = mutableStateOf(true)
    private val draft = mutableStateOf("")
    private var generation: Job? = null
    private var messageId = 0

    private val pickGguf = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importModel(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(12, 19, 32)
        window.navigationBarColor = android.graphics.Color.rgb(12, 19, 32)
        setContent { AppScreen() }

        lifecycleScope.launch {
            try {
                engine = withContext(Dispatchers.IO) {
                    AiChat.getInferenceEngine(applicationContext)
                }
                val saved = getPreferences(MODE_PRIVATE).getString(PREF_MODEL, null)
                if (saved != null && File(saved).isFile) {
                    loadModel(File(saved))
                } else {
                    busy.value = false
                    status.value = "Selecione o LFM3-1.7B Q4_K_M para começar."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                busy.value = false
                status.value = "Erro inicial: " + (e.message ?: "desconhecido")
            } catch (e: UnsatisfiedLinkError) {
                busy.value = false
                status.value = "Biblioteca nativa indisponível: " + e.message
            }
        }
    }

    @Composable
    private fun AppScreen() {
        val navy = Color(0xFF0C1320)
        val cardColor = Color(0xFF182337)
        val accent = Color(0xFF88B9FF)
        val state = rememberLazyListState()
        LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
            if (messages.isNotEmpty()) state.animateScrollToItem(messages.lastIndex)
        }
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = navy, surface = navy,
                primary = accent, onPrimary = Color(0xFF001B36),
                onSurface = Color(0xFFF0F3FC)
            )
        ) {
            Column(
                Modifier.fillMaxSize().background(navy).statusBarsPadding().navigationBarsPadding().imePadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("LFM3Local", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("IA local • LFM3-1.7B Q4", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA2B1C7))
                    }
                    if (ready.value && !busy.value) {
                        TextButton(onClick = ::resetChat) { Text("Novo chat") }
                    }
                }
                Surface(color = cardColor) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (busy.value) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Box(Modifier.size(8.dp).background(
                                if (ready.value) Color(0xFF65D9A1) else Color(0xFFFFC078),
                                RoundedCornerShape(8.dp)
                            ))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(status.value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (messages.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Converse diretamente com o modelo",
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Respostas locais em streaming, histórico durante a sessão e controle de interrupção.",
                                color = Color(0xFFA2B1C7),
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(20.dp))
                            if (!ready.value && !busy.value) {
                                Button(onClick = { pickGguf.launch(arrayOf("*/*")) }) {
                                    Text("Importar arquivo GGUF")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = state,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(messages, key = { _, item -> item.id }) { _, entry ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (entry.mine) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = if (entry.mine) Color(0xFF294A73) else cardColor,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.fillMaxWidth(0.91f)
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(if (entry.mine) "Você" else "LFM3",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.labelSmall, color = accent)
                                        Spacer(Modifier.height(5.dp))
                                        SelectionContainer {
                                            Text(entry.text.ifBlank { "Gerando resposta…" },
                                                style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF29384D))
                if (!ready.value && !busy.value) {
                    OutlinedButton(
                        onClick = { pickGguf.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("Selecionar outro GGUF") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        enabled = ready.value && !busy.value,
                        label = { Text("Mensagem") },
                        placeholder = { Text("Escreva sua pergunta…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!busy.value) send() })
                    )
                    Spacer(Modifier.width(8.dp))
                    if (busy.value && generation != null) {
                        Button(onClick = ::stopGeneration, modifier = Modifier.height(56.dp)) {
                            Text("Parar")
                        }
                    } else {
                        Button(
                            onClick = ::send,
                            enabled = ready.value && !busy.value && draft.value.isNotBlank(),
                            modifier = Modifier.height(56.dp)
                        ) { Text("Enviar") }
                    }
                }
            }
        }
    }

    private fun importModel(uri: Uri) {
        if (busy.value) return
        busy.value = true
        ready.value = false
        status.value = "Copiando modelo para o armazenamento privado…"
        lifecycleScope.launch {
            var temp: File? = null
            try {
                val outputFile = File(filesDir, "models/lfm3-" + System.currentTimeMillis() + ".gguf")
                withContext(Dispatchers.IO) {
                    outputFile.parentFile!!.mkdirs()
                    val tempFile = File(outputFile.parentFile, outputFile.name + ".partial")
                    temp = tempFile
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                total += count
                                if (total % (16 * 1024 * 1024) < buffer.size) {
                                    withContext(Dispatchers.Main) {
                                        status.value = "Copiando GGUF: " + (total / (1024 * 1024)) + " MiB"
                                    }
                                }
                            }
                        }
                    } ?: error("Não foi possível abrir o arquivo selecionado.")
                    if (tempFile.length() < 600_000_000L) {
                        error("Arquivo GGUF incompleto ou incompatível (muito pequeno).")
                    }
                    FileInputStream(tempFile).use { stream ->
                        val magic = ByteArray(4)
                        if (stream.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                            error("Arquivo inválido: cabeçalho GGUF ausente.")
                        }
                    }
                    check(tempFile.renameTo(outputFile)) { "Não foi possível finalizar a importação." }
                }
                loadModel(outputFile)
                if (ready.value) {
                    getPreferences(MODE_PRIVATE).edit().putString(PREF_MODEL, outputFile.absolutePath).apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failure", e)
                status.value = "Falha na importação: " + (e.message ?: "erro desconhecido")
                busy.value = false
                ready.value = false
            } finally {
                withContext(Dispatchers.IO) { temp?.delete() }
            }
        }
    }

    private suspend fun loadModel(file: File) {
        ready.value = false
        busy.value = true
        status.value = "Carregando pesos do LFM3…"
        try {
            withContext(Dispatchers.IO) {
                if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
                engine.loadModel(file.absolutePath)
            }
            status.value = "Preparando o contexto de conversa…"
            withContext(Dispatchers.IO) { engine.setSystemPrompt(SYSTEM_PROMPT) }
            ready.value = true
            status.value = "Modelo pronto • processamento 100% local"
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
            ready.value = false
            status.value = "Falha ao carregar modelo: " + (e.message ?: "erro desconhecido")
        } finally {
            busy.value = false
        }
    }

    private fun send() {
        val text = draft.value.trim()
        if (!ready.value || busy.value || text.isEmpty()) return
        draft.value = ""
        messages.add(ChatEntry(++messageId, true, text))
        val responseId = ++messageId
        messages.add(ChatEntry(responseId, false, ""))
        busy.value = true
        status.value = "Processando entrada…"
        val tokens = StringBuilder()
        generation = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    engine.sendUserPrompt(text, predictLength = 512).collect { piece ->
                        tokens.append(piece)
                        withContext(Dispatchers.Main) {
                            val index = messages.indexOfFirst { it.id == responseId }
                            if (index >= 0) messages[index] = messages[index].copy(
                                text = visibleResponse(tokens.toString())
                            )
                            status.value = "Gerando resposta…"
                        }
                    }
                }
                status.value = "Modelo pronto • resposta concluída"
                if (tokens.isEmpty()) {
                    val index = messages.indexOfFirst { it.id == responseId }
                    if (index >= 0) messages[index] =
                        messages[index].copy(text = "O modelo não gerou texto. Consulte o Logcat.")
                }
            } catch (e: CancellationException) {
                status.value = "Geração interrompida"
            } catch (e: Exception) {
                Log.e(TAG, "Generation failure", e)
                status.value = "Falha de inferência: " + (e.message ?: "erro desconhecido")
                val index = messages.indexOfFirst { it.id == responseId }
                if (index >= 0 && messages[index].text.isBlank()) {
                    messages[index] = messages[index].copy(text = "Erro ao gerar resposta.")
                }
            } finally {
                generation = null
                busy.value = false
            }
        }
    }

    private fun stopGeneration() {
        status.value = "Interrompendo geração…"
        generation?.cancel()
    }

    private fun resetChat() {
        val model = getPreferences(MODE_PRIVATE).getString(PREF_MODEL, null)
        if (!ready.value || busy.value || model == null) return
        messages.clear()
        lifecycleScope.launch {
            status.value = "Reiniciando sessão de conversa…"
            busy.value = true
            withContext(Dispatchers.IO) { engine.cleanUp() }
            loadModel(File(model))
        }
    }

    override fun onDestroy() {
        generation?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LFM3Local"
        private const val PREF_MODEL = "model_path"
        private const val SYSTEM_PROMPT =
            "Você é um assistente técnico útil, direto e coerente. Converse naturalmente em português " +
            "quando o usuário escrever em português. Preserve o contexto da conversa e não repita " +
            "perguntas já respondidas. Não invente execução de ferramentas nem acesso à internet. " +
            "Se desconhecer algo, explique a limitação de forma breve."
    }
}

private fun visibleResponse(value: String): String {
    val withoutFinishedThoughts = Regex("<think>[\\s\\S]*?</think>").replace(value, "")
    val openThought = withoutFinishedThoughts.indexOf("<think>")
    return (if (openThought < 0) withoutFinishedThoughts else
        withoutFinishedThoughts.substring(0, openThought)).trimStart()
}
