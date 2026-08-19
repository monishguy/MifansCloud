package com.monishguy.mifanscloud.data.local

import android.content.Context

/** 数据板块（各持独立保存目录）。 */
enum class SaveSection(val title: String, val prefsKey: String) {
    ALBUM("相册", "backup_tree_uri"),      // 兼容旧键：相册目录沿用既有字段
    RECORDING("录音", "dir_recording"),
    CONTACT("通讯录", "dir_contact"),
    NOTE("笔记", "dir_note"),
}

/**
 * 各板块的 SAF 保存目录（SharedPreferences，用户在选择器中自选）。
 * 默认空；点击后由 UI 调系统文件夹选择器（OpenDocumentTree）设置。
 */
class SaveDirStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(section: SaveSection): String? = prefs.getString(section.prefsKey, null)

    fun set(section: SaveSection, uri: String) {
        prefs.edit().putString(section.prefsKey, uri).apply()
    }

    private companion object {
        const val PREFS_NAME = "mifans_dirs"
    }
}
