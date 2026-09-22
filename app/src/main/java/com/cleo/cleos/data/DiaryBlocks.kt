package com.cleo.cleos.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A diary entry is a sequence of blocks, so pictures sit where they were put, between
 * paragraphs, instead of being a gallery stapled to the end.
 */
@Serializable
sealed interface DiaryBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : DiaryBlock

    /** [file] is a name inside ImageStore's directory, never an absolute path. */
    @Serializable
    @SerialName("image")
    data class Image(val file: String, val width: Int, val height: Int) : DiaryBlock
}

object DiaryBlocks {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun decode(raw: String): List<DiaryBlock> =
        runCatching { json.decodeFromString<List<DiaryBlock>>(raw) }.getOrElse { listOf(DiaryBlock.Text(raw)) }

    fun encode(blocks: List<DiaryBlock>): String = json.encodeToString(blocks)

    fun plainText(blocks: List<DiaryBlock>): String =
        blocks.filterIsInstance<DiaryBlock.Text>().joinToString("\n") { it.text }.trim()

    fun images(blocks: List<DiaryBlock>): List<DiaryBlock.Image> = blocks.filterIsInstance<DiaryBlock.Image>()
}
