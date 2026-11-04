package ai.narratium.nativeapp

import ai.narratium.nativeapp.data.CharacterCard
import ai.narratium.nativeapp.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardTest {
    @Test
    fun generatedPromptIncludesRelevantCardFields() {
        val card = CharacterCard(
            name = "小月",
            description = "图书馆守护者",
            personality = "勇敢好奇",
            scenario = "世界尽头",
        )
        val prompt = card.systemPrompt()
        assertTrue(prompt.contains("小月"))
        assertTrue(prompt.contains("图书馆守护者"))
        assertTrue(prompt.contains("勇敢好奇"))
        assertTrue(prompt.contains("世界尽头"))
    }

    @Test
    fun messagesDefaultToUniqueIds() {
        val first = ChatMessage(role = "user", content = "你好")
        val second = ChatMessage(role = "user", content = "你好")
        assertTrue(first.id != second.id)
        assertEquals("user", first.role)
    }

    @Test
    fun longExampleIsTruncatedInPrompt() {
        val card = CharacterCard(name = "测试", example = "a".repeat(5000))
        assertTrue(card.systemPrompt().length < 3500)
    }
}
