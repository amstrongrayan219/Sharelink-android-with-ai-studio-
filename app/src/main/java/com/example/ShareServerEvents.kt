package com.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ReceivedFile(
    val name: String,
    val size: Long,
    val time: Long,
    val absolutePath: String
)

object ShareServerEvents {
    private val _receivedFilesFlow = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val receivedFilesFlow = _receivedFilesFlow.asStateFlow()

    fun notifyFileReceived(context: Context) {
        val targetDir = context.getExternalFilesDir("ShareLink") ?: File(context.filesDir, "ShareLink")
        if (targetDir.exists()) {
            val list = targetDir.listFiles()?.map { file ->
                ReceivedFile(
                    name = file.name,
                    size = file.length(),
                    time = file.lastModified(),
                    absolutePath = file.absolutePath
                )
            }?.sortedByDescending { it.time } ?: emptyList()
            _receivedFilesFlow.value = list
        }
    }
}
