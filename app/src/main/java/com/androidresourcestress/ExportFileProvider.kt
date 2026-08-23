package com.androidresourcestress

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class ExportFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = when {
        uri.lastPathSegment?.endsWith(".json", ignoreCase = true) == true ->
            "application/json"
        uri.lastPathSegment?.endsWith(".log", ignoreCase = true) == true ->
            "text/plain"
        else -> "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Export files are read-only" }
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolveFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun resolveFile(uri: Uri): File {
        val name = uri.lastPathSegment?.takeIf {
            it.isNotBlank() && it == File(it).name
        } ?: throw IllegalArgumentException("Invalid export path")
        val root = File(requireNotNull(context).cacheDir, ResultExporter.EXPORT_DIRECTORY)
            .canonicalFile
        val file = File(root, name).canonicalFile
        require(file.parentFile == root && file.isFile) { "Export file not found" }
        return file
    }
}
