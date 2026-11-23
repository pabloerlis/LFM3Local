package br.com.lfm3local

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val deepGreen = Color(0xFF075E54)
private val brightGreen = Color(0xFF008069)
private val chatPaper = Color(0xFFEAE6DE)
private val sentGreen = Color(0xFFD9FDD3)
private val textDark = Color(0xFF162A2A)
private val muted = Color(0xFF667781)
private val dividerGrey = Color(0xFFEAEDEF)
private val brazil = Locale("pt", "BR")

class MainActivity : ComponentActivity() {
    private lateinit var engine: InferenceEngine
    private lateinit var store: ChatStore
    private val conversations = mutableStateListOf<LocalConversation>()
    private val entries = mutableStateListOf<LocalMessage>()
    private val selectedId = mutableStateOf<Long?>(null)
    private val selectedTitle = mutableStateOf("")
    private val searchText = mutableStateOf("")
    private val draft = mutableStateOf("")
    private val engineStatus = mutableStateOf("Iniciando mecanismo local…")
    private val modelReady = mutableStateOf(false)
    private val modelBusy = mutableStateOf(true)
    private val contextBusy = mutableStateOf(false)
    private val responding = mutableStateOf(false)
    private var nativeChatId: Long? = null
    private var generation: Job? = null
    private var openingSerial = 0

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 94, 84)
        window.navigationBarColor = android.graphics.Color.rgb(234, 230, 222)
        store = ChatStore(applicationContext)
        setContent { App() }
        lifecycleScope.launch {
            refreshConversations()
            try {
                engine = withContext(Dispatchers.IO) {
                    AiChat.getInferenceEngine(applicationContext)
                }
                val path = getPreferences(MODE_PRIVATE).getString(PREF_MODEL, null)
                if (path != null && File(path).isFile) {
                    loadModel(File(path))
                } else {
                    modelBusy.value = false
                    engineStatus.value = "Importe o GGUF para conversar."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not initialize", e)
                modelBusy.value = false
                engineStatus.value = "Erro ao inicializar: " + (e.message ?: "desconhecido")
            } catch (e: UnsatisfiedLinkError) {
                modelBusy.value = false
                engineStatus.value = "Erro de biblioteca nativa: " + (e.message ?: "indisponível")
            }
        }
    }

    @Composable
    private fun App() {
        MaterialTheme(colorScheme = lightColorScheme(
            primary = brightGreen, secondary = deepGreen,
            background = Color.White, surface = Color.White,
            onSurface = textDark, onPrimary = Color.White
        )) {
            val chatId = selectedId.value
            BackHandler(enabled = chatId != null) {
                if (responding.value) stopGeneration() else goHome()
            }
            if (chatId == null) HomeScreen() else ConversationScreen(chatId)
        }
    }

    @Composable
    private fun HomeScreen() {
        var deleting by remember { mutableStateOf<LocalConversation?>(null) }
        val filtered = conversations.filter {
            it.title.contains(searchText.value, ignoreCase = true) ||
                it.preview.contains(searchText.value, ignoreCase = true)
        }
        Scaffold(
            containerColor = Color.White,
            floatingActionButton = {
                FloatingActionButton(
                    onClick = ::createConversation,
                    containerColor = brightGreen, contentColor = Color.White
                ) {
                    Text("＋", style = MaterialTheme.typography.headlineMedium)
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxWidth().background(deepGreen).padding(
                    start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp
                )) {
                    Text("LFM3Local", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Conversas locais", color = Color(0xFFD4E9E5), fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = searchText.value, onValueChange = { searchText.value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    placeholder = { Text("Pesquisar conversas", color = muted) },
                    singleLine = true, shape = RoundedCornerShape(24.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(modelReady.value)
                    Spacer(Modifier.width(8.dp))
                    Text(engineStatus.value, Modifier.weight(1f),
                        color = muted, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!modelBusy.value && !responding.value) {
                        TextButton(onClick = { pickModel.launch(arrayOf("*/*")) }) { Text("Modelo") }
                    }
                }
                HorizontalDivider(color = dividerGrey)
                if (conversations.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Suas conversas aparecerão aqui", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(9.dp))
                            Text("O histórico fica salvo neste aparelho. Inicie uma conversa no botão +.",
                                textAlign = TextAlign.Center, color = muted)
                            if (!modelReady.value && !modelBusy.value) {
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                                    Text("Importar modelo GGUF")
                                }
                            }
                        }
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Nenhuma conversa encontrada", color = muted)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                        items(filtered, key = { it.id }) { chat ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !responding.value && !contextBusy.value) {
                                        openConversation(chat.id, chat.title)
                                    }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ChatAvatar()
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(chat.title, Modifier.weight(1f),
                                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(shortDate(chat.updatedAt), fontSize = 11.sp, color = muted)
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Text(chat.preview.ifBlank { "Conversa sem mensagens" },
                                        color = muted, fontSize = 14.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { deleting = chat }) {
                                    Text("⋮", fontSize = 23.sp, color = muted)
                                }
                            }
                            HorizontalDivider(Modifier.padding(start = 84.dp), color = dividerGrey)
                        }
                    }
                }
            }
        }
        deleting?.let { chat ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Excluir conversa?") },
                text = { Text("As mensagens deste chat serão apagadas apenas deste aparelho.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = null
                        deleteConversation(chat.id)
                    }) { Text("Excluir") }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text("Cancelar") }
                }
            )
        }
    }

    @Composable
    private fun ConversationScreen(chatId: Long) {
        var menuExpanded by remember { mutableStateOf(false) }
        var renameOpen by remember { mutableStateOf(false) }
        var deleteOpen by remember { mutableStateOf(false) }
        var renameText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        LaunchedEffect(chatId, entries.size, entries.lastOrNull()?.body?.length) {
            if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
        }
        Column(Modifier.fillMaxSize().background(chatPaper).imePadding()) {
            Row(Modifier.fillMaxWidth().background(deepGreen).padding(
                horizontal = 8.dp, vertical = 8.dp
            ), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = ::goHome,
                    enabled = !responding.value && !contextBusy.value,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(38.dp)
                ) { Text("‹", color = Color.White, fontSize = 35.sp, lineHeight = 35.sp) }
                ChatAvatar(small = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(selectedTitle.value, fontWeight = FontWeight.SemiBold,
                        color = Color.White, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (responding.value) "Gerando resposta…"
                        else if (contextBusy.value) "Restaurando contexto…"
                        else if (modelReady.value) "IA local • disponível" else "Modelo não carregado",
                        fontSize = 12.sp, color = Color(0xFFD6EDE7)
                    )
                }
                Box {
                    TextButton(onClick = { menuExpanded = true }, enabled = !responding.value) {
                        Text("⋮", color = Color.White, fontSize = 24.sp)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Renomear conversa") }, onClick = {
                            menuExpanded = false
                            renameText = selectedTitle.value
                            renameOpen = true
                        })
                        DropdownMenuItem(text = { Text("Excluir conversa") }, onClick = {
                            menuExpanded = false
                            deleteOpen = true
                        })
                    }
                }
            }

            if (modelBusy.value || contextBusy.value || responding.value || !modelReady.value) {
                Surface(color = Color(0xFFFFF8E1)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (modelBusy.value || contextBusy.value || responding.value) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(engineStatus.value, fontSize = 12.sp, color = textDark,
                            modifier = Modifier.weight(1f), maxLines = 3)
                        if (!modelReady.value && !modelBusy.value) {
                            TextButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                                Text("Importar GGUF")
                            }
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF8DE)) {
                        Text("As mensagens são armazenadas somente neste dispositivo.",
                            modifier = Modifier.padding(14.dp),
                            color = muted, textAlign = TextAlign.Center, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(entries, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                if (item.role == "user") Arrangement.End else Arrangement.Start) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.87f),
                                color = if (item.role == "user") sentGreen else Color.White,
                                shape = RoundedCornerShape(
                                    topStart = if (item.role == "assistant") 3.dp else 15.dp,
                                    topEnd = if (item.role == "user") 3.dp else 15.dp,
                                    bottomStart = 15.dp, bottomEnd = 15.dp
                                ),
                                tonalElevation = 1.dp
                            ) {
                                Column(Modifier.padding(start = 12.dp, end = 10.dp,
                                    top = 9.dp, bottom = 7.dp)) {
                                    SelectionContainer {
                                        Text(item.body.ifBlank {
                                            if (item.status == "draft") "Gerando resposta…" else "Sem resposta"
                                        }, fontSize = 15.sp, color = textDark, lineHeight = 21.sp)
                                    }
                                    Row(
                                        Modifier.align(Alignment.End).padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (item.status == "interrupted") {
                                            Text("interrompida  ", fontSize = 10.sp, color = muted)
                                        } else if (item.status == "error") {
                                            Text("falha  ", fontSize = 10.sp, color = muted)
                                        }
                                        Text(clockTime(item.timestamp), fontSize = 10.sp, color = muted)
                                        if (item.role == "user") {
                                            Text("  ✓", fontSize = 10.sp, color = muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().background(chatPaper)
                .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mensagem") },
                    enabled = modelReady.value && !modelBusy.value && !contextBusy.value &&
                        !responding.value && nativeChatId == chatId,
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        disabledContainerColor = Color(0xFFF3F4F4)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() })
                )
                Spacer(Modifier.width(7.dp))
                FloatingActionButton(
                    onClick = { if (responding.value) stopGeneration() else sendMessage() },
                    containerColor = brightGreen, contentColor = Color.White,
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape
                ) {
                    Text(if (responding.value) "■" else "➤", fontSize = 19.sp)
                }
            }
        }
        if (renameOpen) {
            AlertDialog(
                onDismissRequest = { renameOpen = false },
                title = { Text("Renomear conversa") },
                text = {
                    OutlinedTextField(value = renameText,
                        onValueChange = { renameText = it.take(72) },
                        singleLine = true, label = { Text("Título") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        renameOpen = false
                        renameConversation(chatId, renameText)
                    }, enabled = renameText.isNotBlank()) { Text("Salvar") }
                },
                dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancelar") } }
            )
        }
        if (deleteOpen) {
            AlertDialog(
                onDismissRequest = { deleteOpen = false },
                title = { Text("Excluir conversa?") },
                text = { Text("O histórico será removido permanentemente deste aparelho.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteOpen = false
                        deleteConversation(chatId)
                    }) { Text("Excluir") }
                },
                dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancelar") } }
            )
        }
    }

    @Composable
    private fun ChatAvatar(small: Boolean = false) {
        Box(Modifier.size(if (small) 38.dp else 52.dp)
            .background(Color(0xFFBDE9D5), CircleShape), contentAlignment = Alignment.Center) {
            Text("L", fontSize = if (small) 21.sp else 25.sp,
                fontWeight = FontWeight.Bold, color = deepGreen)
        }
    }

    @Composable
    private fun StatusDot(loaded: Boolean) {
        Box(Modifier.size(8.dp).background(
            if (loaded) Color(0xFF21AA72) else Color(0xFFE4A334), CircleShape))
    }

    private fun refreshConversations() {
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.conversations() }
                conversations.clear()
                conversations.addAll(saved)
            } catch (e: Exception) { Log.e(TAG, "History list failed", e) }
        }
    }

    private fun createConversation() {
        if (responding.value || contextBusy.value) return
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { store.create() }
                refreshConversations()
                openConversation(id, "Nova conversa")
            } catch (e: Exception) {
                engineStatus.value = "Falha ao criar conversa: " + e.message
            }
        }
    }

    private fun openConversation(chatId: Long, title: String) {
        if (responding.value || contextBusy.value) return
        val serial = ++openingSerial
        selectedId.value = chatId
        selectedTitle.value = title
        draft.value = ""
        entries.clear()
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.messages(chatId) }
                if (serial != openingSerial || selectedId.value != chatId) return@launch
                entries.addAll(saved)
                if (modelReady.value) restoreContext(chatId, saved)
            } catch (e: Exception) {
                engineStatus.value = "Falha ao abrir conversa: " + e.message
            }
        }
    }

    private suspend fun restoreContext(chatId: Long, history: List<LocalMessage>? = null) {
        if (!modelReady.value || responding.value) return
        if (nativeChatId == chatId) return
        contextBusy.value = true
        engineStatus.value = "Restaurando contexto desta conversa…"
        try {
            val all = history ?: withContext(Dispatchers.IO) { store.messages(chatId) }
            val replay = all.filter {
                (it.status == "complete" ||
                    (it.status == "interrupted" && it.body != "Resposta interrompida.")) &&
                    it.body.isNotBlank()
            }
            engine.restoreConversation(SYSTEM_PROMPT, replay.map { it.role }, replay.map { it.body })
            if (selectedId.value == chatId) {
                nativeChatId = chatId
                engineStatus.value = "Modelo pronto • contexto restaurado"
            } else {
                nativeChatId = null
            }
        } catch (e: Exception) {
            nativeChatId = null
            Log.e(TAG, "Failed to restore conversation", e)
            engineStatus.value = "Falha ao restaurar contexto: " + (e.message ?: "desconhecida")
        } finally {
            contextBusy.value = false
        }
    }

    private fun goHome() {
        if (responding.value || contextBusy.value) return
        ++openingSerial
        selectedId.value = null
        entries.clear()
        draft.value = ""
        refreshConversations()
    }

    private fun renameConversation(id: Long, title: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { store.rename(id, title) }
                if (selectedId.value == id) selectedTitle.value = title.trim()
                refreshConversations()
            } catch (e: Exception) {
                engineStatus.value = "Falha ao renomear: " + e.message
            }
        }
    }

    private fun deleteConversation(id: Long) {
        if (responding.value || contextBusy.value) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { store.delete(id) }
                if (selectedId.value == id) {
                    selectedId.value = null
                    entries.clear()
                    nativeChatId = null
                } else if (nativeChatId == id) {
                    nativeChatId = null
                }
                refreshConversations()
            } catch (e: Exception) {
                engineStatus.value = "Falha ao excluir: " + e.message
            }
        }
    }

    private fun sendMessage() {
        val id = selectedId.value ?: return
        val content = draft.value.trim()
        if (content.isEmpty() || !modelReady.value || modelBusy.value ||
            contextBusy.value || responding.value || nativeChatId != id) return

        draft.value = ""
        responding.value = true
        val autoTitle = if (selectedTitle.value == "Nova conversa") {
            content.lineSequence().first().trim().take(46).ifBlank { "Nova conversa" }
        } else null
        engineStatus.value = "Preparando resposta…"
        generation = lifecycleScope.launch {
            var assistantId: Long? = null
            val raw = StringBuilder()
            var latest = ""
            var checkpointLength = 0
            try {
                val result = withContext(Dispatchers.IO) {
                    val userId = store.append(id, "user", content)
                    val replyId = store.append(id, "assistant", "", "draft")
                    if (autoTitle != null) store.rename(id, autoTitle)
                    Pair(userId, replyId)
                }
                if (autoTitle != null) selectedTitle.value = autoTitle
                assistantId = result.second
                entries.add(LocalMessage(result.first, id, "user", content, "complete", System.currentTimeMillis()))
                entries.add(LocalMessage(result.second, id, "assistant", "", "draft", System.currentTimeMillis()))

                withContext(Dispatchers.Default) {
                    engine.sendUserPrompt(content, predictLength = 512).collect { token ->
                        raw.append(token)
                        val visible = visibleAnswer(raw.toString())
                        if (visible != latest) {
                            latest = visible
                            withContext(Dispatchers.Main) {
                                replaceEntry(result.second, visible, "draft")
                                engineStatus.value = "Gerando resposta…"
                            }
                            if (visible.length - checkpointLength >= 180) {
                                withContext(Dispatchers.IO) {
                                    store.updateMessage(result.second, id, visible, "draft")
                                }
                                checkpointLength = visible.length
                            }
                        }
                    }
                }
                val complete = if (latest.isNotBlank()) "complete" else "error"
                if (latest.isBlank()) latest = "O modelo não produziu texto visível."
                replaceEntry(result.second, latest, complete)
                withContext(Dispatchers.IO) {
                    store.updateMessage(result.second, id, latest, complete)
                }
                engineStatus.value = "Modelo pronto • resposta concluída"
            } catch (cancel: CancellationException) {
                engineStatus.value = "Geração interrompida"
                val reply = assistantId
                if (reply != null) {
                    val body = latest.ifBlank { "Resposta interrompida." }
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        store.updateMessage(reply, id, body, "interrupted")
                    }
                    replaceEntry(reply, body, "interrupted")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Generation failure", e)
                engineStatus.value = "Falha ao gerar: " + (e.message ?: "desconhecida")
                assistantId?.let { reply ->
                    val body = latest.ifBlank { "Falha ao gerar resposta." }
                    withContext(Dispatchers.IO) { store.updateMessage(reply, id, body, "error") }
                    replaceEntry(reply, body, "error")
                }
                nativeChatId = null // Require explicit recovery rather than silently continuing broken KV.
            } finally {
                generation = null
                responding.value = false
                refreshConversations()
            }
        }
    }

    private fun replaceEntry(messageId: Long, content: String, state: String) {
        val index = entries.indexOfFirst { it.id == messageId }
        if (index >= 0) entries[index] = entries[index].copy(body = content, status = state)
    }

    private fun stopGeneration() {
        engineStatus.value = "Interrompendo geração…"
        generation?.cancel()
    }

    private fun importModel(uri: Uri) {
        if (modelBusy.value || responding.value || contextBusy.value) return
        modelBusy.value = true
        modelReady.value = false
        engineStatus.value = "Copiando modelo para o aparelho…"
        lifecycleScope.launch {
            var partial: File? = null
            try {
                val target = File(filesDir, "models/lfm3-" + System.currentTimeMillis() + ".gguf")
                withContext(Dispatchers.IO) {
                    check(target.parentFile!!.exists() || target.parentFile!!.mkdirs())
                    val temp = File(target.parentFile, target.name + ".partial")
                    partial = temp
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { out ->
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                total += read
                                if (total % (16 * 1024 * 1024) < buffer.size) {
                                    withContext(Dispatchers.Main) {
                                        engineStatus.value = "Copiando GGUF: " +
                                            (total / (1024 * 1024)) + " MiB"
                                    }
                                }
                            }
                        }
                    } ?: error("Não foi possível ler o arquivo.")
                    check(temp.length() > 600_000_000L) { "Arquivo GGUF muito pequeno ou truncado." }
                    FileInputStream(temp).use { stream ->
                        val magic = ByteArray(4)
                        check(stream.read(magic) == 4 &&
                            String(magic, Charsets.US_ASCII) == "GGUF") { "Cabeçalho GGUF inválido." }
                    }
                    check(temp.renameTo(target)) { "Não foi possível finalizar a cópia." }
                }
                // Keep history untouched even when replacing the model.
                loadModel(target)
                if (modelReady.value) {
                    getPreferences(MODE_PRIVATE).edit().putString(PREF_MODEL, target.path).apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "GGUF import failed", e)
                engineStatus.value = "Falha de importação: " + (e.message ?: "desconhecida")
            } finally {
                withContext(Dispatchers.IO) { partial?.delete() }
                modelBusy.value = false
            }
        }
    }

    private suspend fun loadModel(file: File) {
        modelBusy.value = true
        modelReady.value = false
        nativeChatId = null
        engineStatus.value = "Carregando pesos do modelo…"
        try {
            withContext(Dispatchers.IO) {
                if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
                engine.loadModel(file.path)
            }
            engineStatus.value = "Inicializando conversa…"
            withContext(Dispatchers.IO) { engine.setSystemPrompt(SYSTEM_PROMPT) }
            modelReady.value = true
            engineStatus.value = "Modelo pronto • offline"
            selectedId.value?.let { restoreContext(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Model loading failed", e)
            engineStatus.value = "Falha ao carregar GGUF: " + (e.message ?: "desconhecida")
            modelReady.value = false
        } finally {
            modelBusy.value = false
        }
    }

    override fun onDestroy() {
        generation?.cancel()
        store.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LFM3Local"
        private const val PREF_MODEL = "model_path"
        private const val SYSTEM_PROMPT =
            "Você é o LFM3Local, assistente profissional offline. Responda de forma natural e direta " +
            "em português quando a conversa estiver em português. Preserve o contexto anterior, " +
            "não repita perguntas já respondidas e não afirme ter usado ferramentas ou internet. " +
            "Declare incertezas com clareza. Você não é um personagem nem um assistente de roleplay."
    }
}

private fun visibleAnswer(raw: String): String {
    // Suppress unfinished thinking blocks; never display a truncated internal segment.
    return Regex("<think>[\\s\\S]*?</think>").replace(raw, "")
        .let { text ->
            val open = text.indexOf("<think>")
            if (open >= 0) text.substring(0, open) else text
        }.trimStart()
}

private fun clockTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", brazil).format(Date(timestamp))

private fun shortDate(timestamp: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", brazil).format(Date())
    val itemDay = SimpleDateFormat("yyyyMMdd", brazil).format(Date(timestamp))
    return if (today == itemDay) clockTime(timestamp)
    else SimpleDateFormat("dd/MM/yy", brazil).format(Date(timestamp))
}
