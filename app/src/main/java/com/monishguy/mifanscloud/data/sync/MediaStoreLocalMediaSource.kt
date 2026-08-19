package com.monishguy.mifanscloud.data.sync

import android.content.Context
import android.provider.MediaStore

/**
 * Android MediaStore 实现：查询本机全部图片与视频的 (id, dateTaken, size)。
 *
 * 需要运行时权限 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`（Android 13+），
 * 由调用方在 UI 层申请后再调用。
 */
class MediaStoreLocalMediaSource(context: Context) : LocalMediaSource {

    private val resolver = context.contentResolver

    override fun queryImagesAndVideos(): List<LocalMedia> {
        val result = mutableListOf<LocalMedia>()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        for (uri in collections) {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    result += LocalMedia(
                        id = cursor.getLong(idCol),
                        dateTakenMs = cursor.getLong(dateCol),
                        sizeBytes = cursor.getLong(sizeCol),
                    )
                }
            }
        }
        return result
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.SIZE,
        )
    }
}
