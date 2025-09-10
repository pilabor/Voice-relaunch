package voice.core.scanner

import android.net.Uri
import androidx.media3.common.FileTypes
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import voice.core.data.BookId
import voice.core.data.audioFileCount
import voice.core.data.folders.FolderType
import voice.core.data.isAudioFile
import voice.core.data.repo.BookContentRepo
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.walk
import voice.core.documentfile.walkBottomUp
import voice.core.logging.core.Logger
import kotlin.time.Duration.Companion.hours
import androidx.core.net.toUri
import voice.core.data.Chapter
import voice.core.documentfile.CachedDocumentFileFactory

internal class MetadataFile(val parent:Uri, val file: CachedDocumentFile, var metadata: Metadata?=null) {
  private val extension: String = (file.name ?: "").substringAfterLast(delimiter = ".", missingDelimiterValue = "").lowercase()

  fun isAudioBook(): Boolean {
    return extension == "m4b"
      || (metadata?.chapters?.size ?: 0) >= 5
      || (metadata?.duration ?: 0) >= 2.hours.inWholeMilliseconds
  }

  fun isChapter() : Boolean {
    return !isAudioBook()
  }
}

@Inject
internal class AutoMediaScanner(
  private val contentRepo: BookContentRepo,
  private val documentFileFactory: CachedDocumentFileFactory,
  private val chapterParser: ChapterParser,
  private val bookParser: BookParser,
  private val deviceHasPermissionBug: DeviceHasStoragePermissionBug,
  private val mediaAnalyzer: MediaAnalyzer,
  ) {

  private var ioScope = CoroutineScope(Dispatchers.IO)
  private var fileScannerChannel: ReceiveChannel<MetadataFile> = Channel(capacity = 250)
  private var metadataLoadedChannel: ReceiveChannel<MetadataFile> = Channel()

  private val bookDirectories: List<CachedDocumentFile> = mutableListOf()

  fun getParentUri(uri:Uri): Uri? {
    when(uri.scheme) {
      "content"-> getParentContentUri(uri)
      "file" -> getParentFileUri(uri)
    }
    return null
  }

  fun getParentFileUri(uri: Uri): Uri? {
    val path = uri.path  // "/var/log/test.mp3"
    if (path != null) {
      val parentPath = path.substring(0, path.lastIndexOf('/') + 1) // "/var/log/"
      return "${uri.scheme}://$parentPath".toUri()      // "file:///var/log/"
    }
    return null
  }

  fun getParentContentUri(uri: Uri): Uri? {
    // val uri = Uri.parse("content://com.android.externalstorage.documents/tree/0000-0000%3Atest/document/0000-0000%3Atest%2Ffoo%2FMovies%2FRR%20parking%20lot%20a%202018_02_22_075101.mp4")
    val documentSegment = uri.pathSegments.lastOrNull()  // "0000-0000:test/foo/Movies/RR parking lot a 2018_02_22_075101.mp4"
    if (documentSegment != null) {
      val parentPath = documentSegment.substring(0, documentSegment.lastIndexOf('/'))  // "0000-0000:test/foo/Movies"
      val parentEncoded = Uri.encode(parentPath)
      return "${uri.scheme}://${uri.authority}/tree/${parentEncoded}/document/${parentEncoded}".toUri()
    }
    return null
  }
  suspend fun scanRoot(files: List<CachedDocumentFile>) {
    fileScannerChannel = ioScope.produce {
      for (rootFile in files) {
        if (rootFile.isDirectory) {
          rootFile.walkBottomUp().forEach { it ->
            // the bottomUp approach allows us to mark the dir as finished as soon as it appears
            if (it.isDirectory) {
              send(MetadataFile(rootFile.uri, it))
            }
          }
        } else {
          val parentUri = getParentUri(rootFile.uri);
          if(parentUri != null) {
            send(MetadataFile(parentUri, rootFile))
          }
        }
      }
    }

    metadataLoadedChannel = ioScope.produce {
      fileScannerChannel.consumeEach { it ->
        it.metadata = mediaAnalyzer.analyze(it.file)
        send(it)
      }
    }

    metadataLoadedChannel.consumeEach { it ->

      if(it.isAudioBook()) {
        var chapters = chapterParser.parse(it.file, it.metadata)
        bookParser.parseAndStore(chapters, it.file)
      } else {
        val directory = bookDirectories.find { dir -> dir.uri.toString() == it.parent.toString()} ?: documentFileFactory.create(it.parent)
        // directory.children.add(it)
      }


    }
  }


}


@Inject
internal class MediaScanner(
  private val contentRepo: BookContentRepo,
  private val chapterParser: ChapterParser,
  private val bookParser: BookParser,
  private val deviceHasPermissionBug: DeviceHasStoragePermissionBug,
  private val autoScanner: AutoMediaScanner,
) {

  suspend fun scan(folders: Map<FolderType, List<CachedDocumentFile>>) {

    val files = folders.flatMap { (folderType, files) ->
      when (folderType) {
        FolderType.Auto -> {
          autoScanner.scanRoot(files)
          emptyList()
        }
        FolderType.SingleFile, FolderType.SingleFolder -> {
          files
        }
        FolderType.Root -> {
          files.flatMap { file ->
            file.children
          }
        }
        FolderType.Author -> {
          files.flatMap { folder ->
            folder.children.flatMap { author ->
              if (author.isFile) {
                listOf(author)
              } else {
                author.children.flatMap {
                  author.children
                }
              }
            }
          }
        }
      }
    }

    contentRepo.setAllInactiveExcept(files.map { BookId(it.uri) })

    val probeFile = folders.values.flatten().findProbeFile()
    if (probeFile != null) {
      if (deviceHasPermissionBug.checkForBugAndSet(probeFile)) {
        Logger.w("Device has permission bug, aborting scan! Probed $probeFile")
        return
      }
    }

    files
      .sortedBy { it.audioFileCount() }
      .forEach { file ->
        scan(file)
      }
  }

  private fun List<CachedDocumentFile>.findProbeFile(): CachedDocumentFile? {
    return asSequence().flatMap { it.walk() }
      .firstOrNull { child ->
        child.isAudioFile() && child.uri.authority == "com.android.externalstorage.documents"
      }
  }

  private suspend fun scan(file: CachedDocumentFile) {
    val chapters = chapterParser.parse(file)
    if (chapters.isEmpty()) return

    val content = bookParser.parseAndStore(chapters, file)

    val chapterIds = chapters.map { it.id }
    val currentChapterGone = content.currentChapter !in chapterIds
    val currentChapter = if (currentChapterGone) chapterIds.first() else content.currentChapter
    val positionInChapter = if (currentChapterGone) 0 else content.positionInChapter
    val updated = content.copy(
      chapters = chapterIds,
      currentChapter = currentChapter,
      positionInChapter = positionInChapter,
      isActive = true,
    )
    if (content != updated) {
      validateIntegrity(updated, chapters)
      contentRepo.put(updated)
    }
  }
}

