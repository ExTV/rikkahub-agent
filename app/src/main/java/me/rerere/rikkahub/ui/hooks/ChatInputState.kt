package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

data class ActionPill(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val isSkill: Boolean = false,
)

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    var actionPill by mutableStateOf<ActionPill?>(null)
    var skillPills by mutableStateOf<List<ActionPill>>(emptyList())

    fun setAction(id: String, label: String, icon: ImageVector) {
        actionPill = ActionPill(id, label, icon, false)
    }

    fun removeAction() {
        actionPill = null
    }

    fun addSkill(name: String, icon: ImageVector) {
        if (skillPills.none { it.id == name }) {
            skillPills = skillPills + ActionPill(id = name, label = name, icon = icon, isSkill = true)
        }
    }

    fun removeSkill(name: String) {
        skillPills = skillPills.filter { it.id != name }
    }

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
        actionPill = null
        skillPills = emptyList()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val rawText = textContent.text.toString().trim()
        val formattedText = buildString {
            actionPill?.let { pill ->
                when (pill.id) {
                    "goal" -> append("[Goal Loop]: ")
                    "grill-me" -> append("[Grill-Me Plan Alignment]: ")
                    "teamwork" -> append("[Teamwork Multi-Agent]: ")
                    "learn" -> append("[Reflection & Learn]: ")
                    "compact" -> append("/compact ")
                    "plan" -> append("/plan ")
                    "build" -> append("/build ")
                    "imagine" -> append("/imagine ")
                    else -> append("[Action: ${pill.label}] ")
                }
            }
            if (rawText.isNotBlank()) {
                append(rawText)
            }
            if (skillPills.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("[Active Skills: ")
                append(skillPills.joinToString(", ") { it.label })
                append("]")
            }
        }

        val text = formattedText.trim()

        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            if (text.isNotBlank()) {
                                merged.add(UIMessagePart.Text(text))
                            }
                        }

                        part is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                merged.add(part)
                            }
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                if (editedTextIndex < 0 && text.isNotBlank()) {
                    merged.add(0, UIMessagePart.Text(text))
                }
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isBlank() && messageContent.isEmpty() && actionPill == null && skillPills.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
