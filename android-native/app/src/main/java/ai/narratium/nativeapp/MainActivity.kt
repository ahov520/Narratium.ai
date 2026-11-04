package ai.narratium.nativeapp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import ai.narratium.nativeapp.data.CharacterCard
import ai.narratium.nativeapp.data.ChatMessage
import ai.narratium.nativeapp.data.ModelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val Ink = Color(0xFF0C1325)
private val Panel = Color(0xFF18243A)
private val WarmGold = Color(0xFFE5C58E)
private val MutedText = Color(0xFFACBCD0)
private val UserBubble = Color(0xFF2A405A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[NativeViewModel::class.java]
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = WarmGold,
                    secondary = MutedText,
                    background = Ink,
                    surface = Panel,
                    onPrimary = Ink,
                    onBackground = Color(0xFFF4EBDB),
                    onSurface = Color(0xFFF4EBDB),
                ),
            ) {
                NativeApp(model)
            }
        }
    }
}

@Composable
private fun NativeApp(vm: NativeViewModel) {
    val state by vm.ui.collectAsState()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val error = state.error
        if (!error.isNullOrBlank()) {
            vm.dismissError()
            snackbars.showSnackbar(error, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("✦ NARRATIUM", fontWeight = FontWeight.Bold, fontSize = 21.sp, color = WarmGold)
                Text("原生 Android · 你的角色与故事", color = MutedText, fontSize = 12.sp)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf(
                    Triple(Screen.LIBRARY, "角色", "✦"),
                    Triple(Screen.CHAT, "聊天", "✎"),
                    Triple(Screen.SETTINGS, "设置", "⚙"),
                ).forEach { (screen, label, symbol) ->
                    NavigationBarItem(
                        selected = state.screen == screen,
                        onClick = { vm.navigate(screen) },
                        enabled = state.ready && (!state.sending || screen == Screen.CHAT),
                        icon = { Text(symbol, fontSize = 20.sp) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.ready) {
                Text("正在读取本地角色与会话…", modifier = Modifier.align(Alignment.Center), color = MutedText)
            } else {
                when (state.screen) {
                    Screen.LIBRARY -> CharacterLibrary(state.characters, vm)
                    Screen.CHAT -> ChatScreen(state, vm)
                    Screen.SETTINGS -> SettingsScreen(state.settings, vm)
                }
            }
        }
    }
}

private suspend fun readCardJson(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2_000_000) {
                    error("角色卡必须小于 2 MB")
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: error("无法打开角色卡文件")
    }

@Composable
private fun CharacterLibrary(cards: List<CharacterCard>, vm: NativeViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CharacterCard?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    vm.importCharacter(readCardJson(context, uri))
                } catch (failure: Exception) {
                    Toast.makeText(context, "导入失败：" + failure.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
        Text("角色图鉴", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("保存的角色卡均保留在此设备", color = MutedText, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { showCreate = true }) { Text("＋ 创建角色") }
            OutlinedButton(onClick = {
                picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }) { Text("导入 JSON 角色卡") }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(cards, key = { it.id }) { card ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(card.name, color = WarmGold, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            card.description.ifBlank { "一个等待与你相遇的新角色。" },
                            color = MutedText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.selectCharacter(card.id) }) { Text("开始对话") }
                            TextButton(onClick = { deleteTarget = card }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateCharacterDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, description, personality, greeting ->
                vm.addCharacter(name, description, personality, greeting)
                showCreate = false
            },
        )
    }

    deleteTarget?.let { card ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除角色？") },
            text = { Text("将永久删除“" + card.name + "”和它的本地对话记录。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCharacter(card.id)
                    deleteTarget = null
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CreateCharacterDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建角色") },
        text = {
            Column(
                modifier = Modifier
                    .height(400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("角色背景") }, minLines = 2)
                OutlinedTextField(personality, { personality = it }, label = { Text("性格设定") }, minLines = 2)
                OutlinedTextField(greeting, { greeting = it }, label = { Text("开场白") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onConfirm(name, description, personality, greeting)
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatScreen(state: NativeUiState, vm: NativeViewModel) {
    val card = state.selectedCharacter
    if (card == null) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("还没有选中角色", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.navigate(Screen.LIBRARY) }) { Text("去角色图鉴选择") }
        }
        return
    }

    var confirmClear by remember(card.id) { mutableStateOf(false) }
    var draft by remember(card.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val messages = state.messages

    LaunchedEffect(card.id, messages.size, state.sending, state.streamingText.length) {
        val count = messages.size + if (state.sending) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(card.name, fontWeight = FontWeight.Bold, color = WarmGold, fontSize = 19.sp)
                Text("每段故事都可以由你续写", color = MutedText, fontSize = 12.sp)
            }
            TextButton(enabled = !state.sending, onClick = { confirmClear = true }) {
                Text("清空记录")
            }
        }
        HorizontalDivider(color = MutedText.copy(alpha = 0.18f))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message.role == "user", message.content)
            }
            if (state.sending) {
                item(key = "streaming") {
                    MessageBubble(false, state.streamingText.ifEmpty { "正在构思下一段故事…" })
                }
            }
        }
        HorizontalDivider(color = MutedText.copy(alpha = 0.18f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 10000) draft = it },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                placeholder = { Text("写下你接下来的行动…") },
                shape = RoundedCornerShape(18.dp),
            )
            if (state.sending) {
                OutlinedButton(onClick = vm::stopGeneration) { Text("停止") }
            } else {
                Button(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        vm.sendMessage(draft)
                        draft = ""
                    },
                ) { Text("发送") }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空当前对话？") },
            text = { Text("这会删除当前角色的所有对话消息，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearConversation()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun MessageBubble(fromUser: Boolean, content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUser) UserBubble else Panel,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(0.91f),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    if (fromUser) "你" else "✦ 角色",
                    color = WarmGold,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                // Plain Compose Text intentionally never executes model-supplied HTML.
                Text(content, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: ModelSettings, vm: NativeViewModel) {
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var temperature by remember(settings.temperature) { mutableStateOf(settings.temperature) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("模型设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("支持兼容 OpenAI /chat/completions 的 HTTPS 模型接口", color = MutedText)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("接口基础地址（例如 https://api.openai.com/v1）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key（保存在 Android Keystore 加密存储中）") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("生成温度：" + String.format("%.1f", temperature), color = WarmGold)
        Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
        Button(
            onClick = {
                vm.saveSettings(
                    ModelSettings(baseUrl.trim(), model.trim(), apiKey.trim(), temperature),
                )
            },
        ) { Text("保存设置") }
        Spacer(Modifier.height(12.dp))
        Text("隐私提示", fontWeight = FontWeight.Bold, color = WarmGold)
        Text(
            "角色和对话保存在设备私有目录中。API Key 使用 Android Keystore 加密。"+
                "发送消息时，选中的模型服务商会接收当前角色设定与最近 30 条消息。"+
                "本版本不会自动读取网页版 IndexedDB，也不会同步角色或会话到云端。",
            color = MutedText,
            fontSize = 13.sp,
        )
    }
}
