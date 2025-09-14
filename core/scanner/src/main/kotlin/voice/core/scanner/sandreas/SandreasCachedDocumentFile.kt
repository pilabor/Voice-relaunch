package voice.core.scanner.sandreas

import android.net.Uri
import voice.core.documentfile.CachedDocumentFile

internal data class SandreasCachedDocumentFile(
  override val children: List<CachedDocumentFile>,
  override val name: String?,
  override val isDirectory: Boolean,
  override val isFile: Boolean,
  override val length: Long,
  override val lastModified: Long,
  override val uri: Uri,
) : CachedDocumentFile

public fun CachedDocumentFile.walkBottomUp(): Sequence<CachedDocumentFile> = sequence {
  suspend fun SequenceScope<CachedDocumentFile>.walk(file: CachedDocumentFile) {
    if (file.isDirectory) {
      file.children.forEach { walk(it) }
    }
    yield(file)
  }
  walk(this@walkBottomUp)
}
