package io.legado.app.help.book

import java.io.File

/** 同目录候选写完并校验后才替换；平台不支持覆盖 rename 时安全失败，不删目标重试。 */
internal fun writeImageCandidate(
    target: File,
    bytes: ByteArray,
    validate: (File) -> Boolean,
    write: (File, ByteArray) -> Unit = { file, data -> file.writeBytes(data) },
    replace: (File, File) -> Boolean = { candidate, destination -> candidate.renameTo(destination) },
): Boolean {
    if (bytes.isEmpty()) return false
    val candidate = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
    return try {
        write(candidate, bytes)
        if (candidate.length() != bytes.size.toLong() || !validate(candidate)) return false
        replace(candidate, target)
    } finally {
        candidate.delete()
    }
}
