package com.monishguy.mifanscloud.data.recording

/** 录音类型（编码在文件名尾缀中）。 */
enum class RecordingType(val code: Int) {
    RECORDER(0),
    PHONE_CALL(1),
    FM(2),
    APP(3),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): RecordingType =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** 解析后的录音文件名与类型。 */
data class ParsedRecordingName(
    val fileName: String,
    val type: RecordingType,
)

/**
 * 解析小米云录音的编码文件名（对齐 XiaomiAlbumSyncer `parseXiaomiRecordingName`）。
 *
 * 标准格式：`{base}.{ext}_{n}_{n}_{n}_{typeCode}`（正则
 * `^(.+)\.([^._]+)_(\d+)_(\d+)_(\d+)_(\d+)$`，类型码取第 4 段数字）；
 * 历史/异常格式：从右侧剥四段 `_数字` 尾缀兜底。
 */
object RecordingNameParser {

    private val REGEX = Regex("""^(.+)\.([^._]+)_(\d+)_(\d+)_(\d+)_(\d+)$""")

    fun parse(rawName: String): ParsedRecordingName {
        val match = REGEX.matchEntire(rawName)
        if (match != null) {
            val (base, ext, _, typeCode) = match.destructured
            return ParsedRecordingName(
                fileName = "$base.$ext",
                type = RecordingType.fromCode(typeCode.toIntOrNull() ?: -1),
            )
        }
        var name = rawName
        repeat(4) { name = name.substringBeforeLast("_", name) }
        return ParsedRecordingName(fileName = name, type = RecordingType.UNKNOWN)
    }
}
