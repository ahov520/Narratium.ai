package ai.narratium.nativeapp.data

import org.json.JSONObject
import java.util.UUID

data class CharacterCard(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val greeting: String = "",
    val example: String = "",
) {
    fun systemPrompt(): String = buildString {
        append("You are roleplaying the fictional character ")
        append(name)
        append(". Stay in character, write engaging interactive fiction, and respond to the user's choices.\n")
        if (description.isNotBlank()) append("Description: " + description + "\n")
        if (personality.isNotBlank()) append("Personality: " + personality + "\n")
        if (scenario.isNotBlank()) append("Scenario: " + scenario + "\n")
        if (example.isNotBlank()) append("Example dialogue:\n" + example.take(3000) + "\n")
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ModelSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val temperature: Float = 0.8f,
)

data class LocalState(
    val characters: List<CharacterCard> = emptyList(),
    val conversations: Map<String, List<ChatMessage>> = emptyMap(),
)

object CharacterCardParser {
    /** Supports Narratium raw cards and SillyTavern v2/v3 JSON cards. */
    fun parse(json: String): CharacterCard {
        require(json.toByteArray(Charsets.UTF_8).size <= 2_000_000) {
            "角色卡超过 2 MB 限制"
        }
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        fun field(name: String): String =
            data.optString(name).ifBlank { root.optString(name) }.take(30_000)
        val name = field("name").trim()
        require(name.isNotEmpty()) { "角色卡缺少 name 字段" }
        return CharacterCard(
            name = name.take(120),
            description = field("description"),
            personality = field("personality"),
            scenario = field("scenario"),
            greeting = field("first_mes"),
            example = field("mes_example"),
        )
    }
}
