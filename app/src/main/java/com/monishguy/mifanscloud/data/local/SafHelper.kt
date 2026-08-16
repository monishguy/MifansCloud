package com.monishguy.mifanscloud.data.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF（Storage Access Framework）目录内创建文档的共享入口。
 *
 * 坑：`DocumentsContract.createDocument` 的第一个参数必须是 **document URI**，
 * 直接传 OpenDocumentTree 返回的 **tree URI** 会抛 `Invalid URI` 崩溃。
 * 必须先 `buildDocumentUriUsingTree(treeUri, getTreeDocumentId(treeUri))`
 * 把 tree 转成父文档 URI（子目录 tree 同样适用，docId 含完整路径）。
 */
object SafHelper {

    /** 在 treeUri 目录下创建文件，返回可写 document URI；失败返回 null（不抛）。 */
    fun createDocument(
        contentResolver: ContentResolver,
        treeUri: String,
        mimeType: String,
        displayName: String,
    ): Uri? = runCatching {
        val tree = Uri.parse(treeUri)
        val parentDocument = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        DocumentsContract.createDocument(contentResolver, parentDocument, mimeType, displayName)
    }.getOrNull()

    /** 把树目录本身作为父文档（用于嵌套 createDocument 等场景）。 */
    fun parentDocumentUri(treeUri: String): Uri? = runCatching {
        val tree = Uri.parse(treeUri)
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()
}
