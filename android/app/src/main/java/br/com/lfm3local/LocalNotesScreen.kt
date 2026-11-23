package br.com.lfm3local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.lfm3local.harness.NoteRecord

private val headerGreen = Color(0xFF075E54)
private val secondaryText = Color(0xFF667781)

@Composable
fun LocalNotesScreen(
    notes: List<NoteRecord>,
    enabled: Boolean,
    canChange: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSave: (Long?, String, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    var editOpen by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<NoteRecord?>(null) }

    fun edit(note: NoteRecord?) {
        currentId = note?.id
        title = note?.title.orEmpty()
        body = note?.body.orEmpty()
        editOpen = true
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().background(headerGreen)
            .padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Conversas", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text("Skills", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notas locais", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Spacer(Modifier.height(5.dp))
                Text("Permite que a IA consulte notas criadas neste aparelho. Apenas leitura; " +
                    "nenhuma nota é enviada à internet.", fontSize = 13.sp,
                    color = secondaryText, lineHeight = 19.sp)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onEnabledChange, enabled = canChange)
        }
        HorizontalDivider(color = Color(0xFFEAEDEF))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Minhas notas", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                modifier = Modifier.weight(1f))
            Text(notes.size.toString() + "/250", color = secondaryText, fontSize = 12.sp)
            Spacer(Modifier.width(9.dp))
            Button(onClick = { edit(null) }, enabled = notes.size < 250) { Text("＋ Nova nota") }
        }
        if (errorMessage.isNotBlank()) {
            Text(errorMessage, color = Color(0xFFB42318), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        }
        if (notes.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(26.dp),
                contentAlignment = Alignment.Center) {
                Text("Nenhuma nota salva. Crie uma nota e ative a skill para testá-la no chat.",
                    color = secondaryText, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(notes, key = { it.id }) { note ->
                    Surface(color = Color(0xFFF3F6F5), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(note.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(note.body, color = secondaryText,
                                fontSize = 13.sp, maxLines = 4,
                                overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { edit(note) }) { Text("Editar") }
                                TextButton(onClick = { deleteTarget = note }) {
                                    Text("Excluir", color = Color(0xFFB42318))
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("Desativada por padrão. Desativar impede novas consultas, mas não apaga notas.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = secondaryText, fontSize = 11.sp)
    }

    if (editOpen) {
        AlertDialog(
            onDismissRequest = { editOpen = false },
            title = { Text(if (currentId == null) "Nova nota" else "Editar nota") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it.take(80) },
                        singleLine = true, label = { Text("Título") })
                    OutlinedTextField(value = body, onValueChange = { body = it.take(2000) },
                        minLines = 3, maxLines = 7, label = { Text("Conteúdo") })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank() && body.isNotBlank(),
                    onClick = { onSave(currentId, title, body); editOpen = false }
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { editOpen = false }) { Text("Cancelar") }
            }
        )
    }
    deleteTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir nota?") },
            text = { Text("A nota será apagada somente deste aparelho.") },
            confirmButton = {
                TextButton(onClick = { onDelete(note.id); deleteTarget = null }) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }
}
