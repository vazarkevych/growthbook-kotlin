package com.comllc.cachinglibrary_kmm.sandbox

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

actual typealias SandboxFileManager = ContextWrapper

actual inline fun <reified T> SandboxFileManager.saveSandbox(fileName: String, value: @Serializable T)  {

    val file = getTargetFile(fileName)

    if (file.exists()) {
        file.delete()
    }

    file.createNewFile()

    val json = Json {  }.encodeToString(value)

    file.appendText(json)

}
actual inline fun <reified T> SandboxFileManager.getSandbox(fileName: String) : @Serializable T?{

    val file = getTargetFile(fileName)

    if (file.exists()) {
        val inputAsString = FileInputStream(file).bufferedReader().use { it.readText() }
        return Json {  }.decodeFromString<T>(inputAsString)
    }

    return null
}

actual fun SandboxFileManager.deleteSandbox(fileName: String){
    val file = getTargetFile(fileName)

    if (file.exists()) {
        file.delete()
    }
}

inline fun SandboxFileManager.getTargetFile(fileName: String) : File {
    val path = this.getFilesDir()
    val letDirectory = File(path, "Sandbox-KMM")
    letDirectory.mkdirs()
    var targetFileName = fileName
    if (fileName.endsWith(".txt", true)) {
        targetFileName = fileName.removeSuffix(".txt")
    }
    return File(letDirectory, targetFileName + ".txt")
}

