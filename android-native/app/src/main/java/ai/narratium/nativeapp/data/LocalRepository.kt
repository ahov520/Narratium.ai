package ai.narratium.nativeapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-private JSON persistence. This is independent of the browser's IndexedDB.
 * A temporary file and rename prevent an interrupted write truncating the primary file.
 */
class LocalRepository(context: Context) {
    private val target = File(context.filesDir, "narratium-native-v1.json")
    private val mutex = Mutex()

    suspend fun load(): LocalState = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!target.exists()) return@withContext LocalState()
            val root = JSONObject(target.readText())
            val charactersArray = root.optJSONArray("characters") ?: JSONArray()
            val characters = (0 until charactersArray.length()).mapNotNull { index ->
                charactersArray.optJSONObject(index)?.let { item ->
                    CharacterCard(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        description = item.optString("description"),
                        personality = item.optString("personality"),
                        scenario = item.optString("scenario"),
                        greeting = item.optString("greeting"),
                        example = item.optString("example"),
                    )
                }
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }

            val conversations = mutableMapOf<String, List<ChatMessage>>()
            val stored = root.optJSONObject("conversations") ?: JSONObject()
            val keys = stored.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val array = stored.optJSONArray(id) ?: continue
                conversations[id] = (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { item ->
                        val role = item.optString("role")
                        if (role != "user" && role != "assistant") return@let null
                        ChatMessage(
                            id = item.optString("id"),
                            role = role,
                            content = item.optString("content"),
                            createdAt = item.optLong("createdAt", 0L),
                        )
                    }
                }
            }
            LocalState(characters, conversations)
        }
    }

    suspend fun save(state: LocalState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = JSONObject()
            root.put("version", 1)
            val characters = JSONArray()
            state.characters.forEach { card ->
                characters.put(JSONObject().apply {
                    put("id", card.id)
                    put("name", card.name)
                    put("description", card.description)
                    put("personality", card.personality)
                    put("scenario", card.scenario)
                    put("greeting", card.greeting)
                    put("example", card.example)
                })
            }
            root.put("characters", characters)

            val conversations = JSONObject()
            state.conversations.forEach { (key, messages) ->
                val array = JSONArray()
                messages.forEach { message ->
                    array.put(JSONObject().apply {
                        put("id", message.id)
                        put("role", message.role)
                        put("content", message.content)
                        put("createdAt", message.createdAt)
                    })
                }
                conversations.put(key, array)
            }
            root.put("conversations", conversations)
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(root.toString())
            check(temporary.renameTo(target)) { "Unable to save chat history" }
        }
    }
}
