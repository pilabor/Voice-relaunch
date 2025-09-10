package voice.core.documentfile

fun CachedDocumentFile.walk(): Sequence<CachedDocumentFile> = sequence {
  suspend fun SequenceScope<CachedDocumentFile>.walk(file: CachedDocumentFile) {
    yield(file)
    if (file.isDirectory) {
      file.children.forEach { walk(it) }
    }
  }
  walk(this@walk)
}

fun CachedDocumentFile.walkBottomUp(): Sequence<CachedDocumentFile> = sequence {
  suspend fun SequenceScope<CachedDocumentFile>.walk(file: CachedDocumentFile) {
    if (file.isDirectory) {
      file.children.forEach { walk(it) }
    }
    yield(file)
  }
  walk(this@walkBottomUp)
}
