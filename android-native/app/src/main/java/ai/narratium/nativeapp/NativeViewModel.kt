package ai.narratium.nativeapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.narratium.nativeapp.data.CharacterCard
import ai.narratium.nativeapp.data.CharacterCardParser
import ai.narratium.nativeapp.data.ChatMessage
import ai.narratium.nativeapp.data.LocalRepository
import ai.narratium.nativeapp.data.LocalState
import ai.narratium.nativeapp.data.ModelSettings
import ai.narratium.nativeapp.data.SecretSettings
import ai.narratium.nativeapp.network.ChatApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { LIBRARY, CHAT, SETTINGS }

data class NativeUiState(
    val ready: Boolean = false,
    val screen: Screen = Screen.LIBRARY,
    val characters: List<CharacterCard> = emptyList(),
    val conversations: Map<String, List<ChatMessage>> = emptyMap(),
    val selectedId: String? = null,
    val settings: ModelSettings = ModelSettings(),
    val sending: Boolean = false,
    val streamingText: String = "",
    val error: String? = null,
) {
    val selectedCharacter: CharacterCard?
        get() = characters.firstOrNull { it.id == selectedId }
    val messages: List<ChatMessage>
        get() = conversations[selectedId].orEmpty()
}

class NativeViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = LocalRepository(application)
    private val secrets = SecretSettings(application)
    private val api = ChatApi()
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val _ui = MutableStateFlow(NativeUiState())
    val ui: StateFlow<NativeUiState> = _ui
    private var generation: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (request in saves) {
                try {
                    val state = _ui.value
                    storage.save(LocalState(state.characters, state.conversations))
                } catch (failure: Exception) {
                    _ui.update { it.copy(error = "本地保存失败：" + (failure.message ?: "未知错误")) }
                }
            }
        }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { storage.load() }
                val settings = withContext(Dispatchers.IO) { secrets.load() }
                val cards = if (saved.characters.isEmpty()) listOf(
                    CharacterCard(
                        name = "薇尔",
                        description = "一个守护古老图书馆的奇幻世界旅行者。",
                        personality = "温柔、机智、好奇，喜欢通过故事探索未知。",
                        scenario = "月光照亮的旧图书馆，远方有尚未讲完的故事。",
                        greeting = "夜已经深了。欢迎来到月光图书馆，旅人。你想从哪个故事开始？",
                    ),
                ) else saved.characters
                _ui.update {
                    it.copy(
                        ready = true,
                        characters = cards,
                        conversations = saved.conversations,
                        settings = settings,
                    )
                }
                if (saved.characters.isEmpty()) requestSave()
            } catch (failure: Exception) {
                _ui.update { it.copy(ready = true, error = "读取本地数据失败：" + (failure.message ?: "未知错误")) }
            }
        }
    }

    private fun requestSave() {
        saves.trySend(Unit)
    }

    fun navigate(screen: Screen) {
        if (_ui.value.sending && screen != Screen.CHAT) return
        _ui.update { it.copy(screen = screen, error = null) }
    }

    fun selectCharacter(id: String) {
        val state = _ui.value
        if (state.sending) return
        val card = state.characters.firstOrNull { it.id == id } ?: return
        val history = state.conversations[id].orEmpty()
        val updated = if (history.isEmpty() && card.greeting.isNotBlank()) {
            state.conversations + (id to listOf(ChatMessage(role = "assistant", content = card.greeting)))
        } else {
            state.conversations
        }
        _ui.update {
            it.copy(selectedId = id, screen = Screen.CHAT, conversations = updated, error = null)
        }
        if (updated !== state.conversations) requestSave()
    }

    fun addCharacter(name: String, description: String, personality: String, greeting: String) {
        if (name.trim().isBlank()) {
            _ui.update { it.copy(error = "请输入角色名称") }
            return
        }
        val card = CharacterCard(
            name = name.trim().take(120),
            description = description.take(30000),
            personality = personality.take(30000),
            greeting = greeting.take(30000),
        )
        _ui.update { it.copy(characters = it.characters + card, error = null) }
        requestSave()
        selectCharacter(card.id)
    }

    fun importCharacter(raw: String) {
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { CharacterCardParser.parse(raw) }
                _ui.update { it.copy(characters = it.characters + parsed, error = null) }
                requestSave()
                selectCharacter(parsed.id)
            } catch (failure: Exception) {
                _ui.update { it.copy(error = "导入失败：" + (failure.message ?: "无效 JSON")) }
            }
        }
    }

    fun deleteCharacter(id: String) {
        if (_ui.value.sending) return
        _ui.update {
            it.copy(
                characters = it.characters.filterNot { card -> card.id == id },
                conversations = it.conversations - id,
                selectedId = if (it.selectedId == id) null else it.selectedId,
                screen = if (it.selectedId == id) Screen.LIBRARY else it.screen,
            )
        }
        requestSave()
    }

    fun clearConversation() {
        val id = _ui.value.selectedId ?: return
        if (_ui.value.sending) return
        _ui.update {
            it.copy(conversations = it.conversations + (id to emptyList()), error = null)
        }
        requestSave()
    }

    fun saveSettings(settings: ModelSettings) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { secrets.save(settings) }
                _ui.update { it.copy(settings = settings, error = null, screen = if (it.selectedId != null) Screen.CHAT else Screen.LIBRARY) }
            } catch (failure: Exception) {
                _ui.update { it.copy(error = "设置保存失败：" + (failure.message ?: "未知错误")) }
            }
        }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        val state = _ui.value
        val card = state.selectedCharacter ?: return
        if (clean.isBlank() || state.sending) return
        val history = state.messages + ChatMessage(role = "user", content = clean)
        _ui.update {
            it.copy(
                sending = true,
                streamingText = "",
                conversations = it.conversations + (card.id to history),
                error = null,
            )
        }
        requestSave()
        generation = viewModelScope.launch {
            val response = StringBuilder()
            try {
                api.stream(state.settings, card, history) { delta ->
                    response.append(delta)
                    _ui.update { it.copy(streamingText = response.toString()) }
                }
            } catch (_: CancellationException) {
                // Preserve any text received before the user stopped generation.
            } catch (failure: Exception) {
                _ui.update {
                    it.copy(error = "回复失败：" + (failure.message ?: "网络错误") +
                        "。消息已保存，可以重新发送。")
                }
            } finally {
                if (response.isNotEmpty()) {
                    _ui.update {
                        val messages = it.conversations[card.id].orEmpty() +
                            ChatMessage(role = "assistant", content = response.toString())
                        it.copy(conversations = it.conversations + (card.id to messages))
                    }
                    requestSave()
                }
                _ui.update { it.copy(sending = false, streamingText = "") }
                generation = null
            }
        }
    }

    fun stopGeneration() {
        generation?.cancel()
    }

    override fun onCleared() {
        generation?.cancel()
        saves.close()
        super.onCleared()
    }
}
