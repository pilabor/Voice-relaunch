package voice.core.scanner

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.google.common.collect.ImmutableList
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.yield
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.walkBottomUp
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration.Companion.hours

// scan directory - send directory packages
// consume directory packages - send

internal data class CustomCachedDocumentFile(
  override val children: List<CachedDocumentFile>,
  override val name: String?,
  override val isDirectory: Boolean,
  override val isFile: Boolean,
  override val length: Long,
  override val lastModified: Long,
  override val uri: Uri,
) : CachedDocumentFile

/*
internal enum class AutoScannerStages
{
  Initialized,
  DirectoryScanCompleted,
  MetadataLoaded,
}
*/

internal class DirectoryPackage(val parentDirectory: CachedDocumentFile?, val files: ImmutableList<CachedDocumentFile>)
/*
internal class MetadataFile(val parent:Uri, val file: CachedDocumentFile, var metadata: Metadata?=null) {
  private val extension: String = (file.name ?: "").substringAfterLast(delimiter = ".", missingDelimiterValue = "").lowercase()

  val completedStages: EnumSet<AutoScannerStages> = EnumSet.of(AutoScannerStages.Initialized)

  fun isAudioBook(): Boolean {
    return extension == "m4b"
      || ((metadata?.chapters?.size ?: 0) >= 5
      && (metadata?.duration ?: 0) >= 2.hours.inWholeMilliseconds)
  }

  fun isChapter() : Boolean {
    return !isAudioBook()
  }
}
*/

@Inject
internal class MediaAutoScanner(
  private val chapterParser: ChapterParser,
  private val bookParser: BookParser,
  private val mediaAnalyzer: MediaAnalyzer,
) {

  private var ioScope = CoroutineScope(Dispatchers.IO)
  private var directoryPackageChannel: ReceiveChannel<DirectoryPackage> = Channel(capacity = 50)
  private var metadataLoadedChannel: ReceiveChannel<DirectoryPackage> = Channel()

  val metadataRepo: ConcurrentMap<Uri, Metadata?> = ConcurrentHashMap<Uri, Metadata?>()

  fun getParentUri(uri: Uri): Uri? {
    when (uri.scheme) {
      "content" -> getParentContentUri(uri)
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

  fun isAudioBook(file: CachedDocumentFile): Boolean {
    if (file.uri.toString().endsWith("m4b")) {
      return true
    }
    val metadata = metadataRepo[file.uri]
    if (metadata == null) {
      return false
    }
    if (metadata.chapters.size < 5 || metadata.duration < 2.hours.inWholeMilliseconds) {
      return false
    }
    return true
  }

  suspend fun scanRoot(files: List<CachedDocumentFile>): List<CachedDocumentFile> {
    // first we scan the directory recursively for files
    // producer / consumer
    directoryPackageChannel = ioScope.produce {
      for (rootFile in files) {
        if (rootFile.isDirectory) {
          // root subdirectories are scanned recursively
          // files are organized in DirectoryPackages to handle books that consist of multiple mp3 files as well as directories
          // containing one book per file
          val currentDirectoryFiles = mutableListOf<CachedDocumentFile>()
          rootFile.walkBottomUp().forEach { it ->
            // the bottomUp approach allows us to submit a finished DirectoryPackage soon as "it" is a directory
            if (it.isDirectory) {
              if (currentDirectoryFiles.isNotEmpty()) {
                send(DirectoryPackage(it, ImmutableList.copyOf(currentDirectoryFiles)))
                currentDirectoryFiles.clear()
              }
            } else {
              currentDirectoryFiles.add(it)
            }
          }
        } else {
          // root files are submitted as single audio file with empty parent DirectoryPackage immediately
          send(DirectoryPackage(null, ImmutableList.of(rootFile)))
        }
      }
    }

    // here we scan all the loaded DirectoryPackage files for their metadata
    metadataLoadedChannel = ioScope.produce {
      // 2 threads to analyse metadata faster (just as an example what we could do, maybe it is not even better performance)
      repeat(2) {
        directoryPackageChannel.consumeEach { it ->
          for (metadataFile in it.files) {
            metadataRepo.put(metadataFile.uri, mediaAnalyzer.analyze(metadataFile))
          }
          // put the finished directoryPackage into the metadataLoadedChannel
          send(it)
        }
      }
    }

    val result = mutableListOf<CachedDocumentFile>()
    metadataLoadedChannel.consumeEach { dp ->
      if (dp.files.isEmpty()) {
        // this should never happen
        return@consumeEach
      }

      // single file package without audio book properties (e.g. files in root directory)
      if (dp.files.size == 1) {
        result.add(dp.files.first())
        return@consumeEach
        /*
        val file = dp.files.first()
        val chapters = chapterParser.parse(file, metadataRepo)
        bookParser.parseAndStore(chapters, file, metadataRepo[file.uri])
         */
      }

      // single file audio books (e.g. m4b or stik=2)
      val singleFileAudiobookFiles = dp.files.filter { file -> isAudioBook(file) }

      singleFileAudiobookFiles.forEach { file ->
        result.add(file)
        /*
        val chapters = chapterParser.parse(file, metadataRepo)
        bookParser.parseAndStore(chapters, file, metadataRepo[file.uri])
         */
      }

      // all files are already processed (no more files in the folder)
      if (singleFileAudiobookFiles.size == dp.files.size) {
        return@consumeEach
      }

      val dpUri = dp.parentDirectory?.uri ?: getParentFileUri(dp.files.first().uri)
      // cannot get directory uri
      if (dpUri == null) {
        return@consumeEach
      }

      // multi-file audio books
      val multiFileBook = CustomCachedDocumentFile(
        // directories may contain mixed contents (e.g. a-full-book.m4b, chapter1.mp3, chapter2.mp3, chapter3.mp3, ...)
        children = dp.files.filter { f -> !isAudioBook(f) },
        name = dp.parentDirectory?.name,
        isDirectory = true,
        isFile = false,
        length = dp.files.sumOf { f -> f.length },
        lastModified = 0, // it.files.maxOf { f -> f.file.lastModified } ?: 0,
        uri = dpUri,
      )

      result.add(multiFileBook)
      /*
      val chapters = chapterParser.parse(multiFileBook, metadataRepo)
      bookParser.parseAndStore(chapters, multiFileBook)

       */
    }

    // todo: we should somehow be able to reuse the gathered metadata in the process, possibly by just adding a metadata to CachedDocumentFile
    // problem: CachedDocumentFile.metadata nullable - cannot be determined if the metadata has not been tried to retrieve or just has an error / is not available
    return result
  }
}
