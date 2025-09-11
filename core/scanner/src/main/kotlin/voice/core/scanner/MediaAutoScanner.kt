package voice.core.scanner

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.walkBottomUp
import java.util.EnumSet
import kotlin.time.Duration.Companion.hours

internal data class CustomCachedDocumentFile(
  override val children: List<CachedDocumentFile>,
  override val name: String?,
  override val isDirectory: Boolean,
  override val isFile: Boolean,
  override val length: Long,
  override val lastModified: Long,
  override val uri: Uri
) : CachedDocumentFile

internal enum class AutoScannerStages
{
  Initialized,
  DirectoryScanCompleted,
  MetadataLoaded,
}

internal class DirectoryPackage(val uri: Uri, val files: List<MetadataFile>)

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

@Inject
internal class MediaAutoScanner(
  private val chapterParser: ChapterParser,
  private val bookParser: BookParser,
  private val mediaAnalyzer: MediaAnalyzer,
) {

  private var ioScope = CoroutineScope(Dispatchers.IO)
  private var directoryPackageChannel: ReceiveChannel<DirectoryPackage> = Channel(capacity = 50)
  private var metadataLoadedChannel: ReceiveChannel<DirectoryPackage> = Channel()

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
    // first we scan the directory recursively for files
    // producer / consumer
    directoryPackageChannel = ioScope.produce {
      for (rootFile in files) {
        if (rootFile.isDirectory) {
          // root subdirectories are scanned recursively
          // files are organized in DirectoryPackages to handle books that consist of multiple mp3 files as well as directories
          // containing one book per file
          val currentDirectoryFiles = mutableListOf<MetadataFile>()
          rootFile.walkBottomUp().forEach { it ->
            // the bottomUp approach allows us to submit a finished DirectoryPackage soon as "it" is a directory
            if (it.isDirectory) {
              send(DirectoryPackage(it.uri, currentDirectoryFiles))
              currentDirectoryFiles.clear()
            } else {
              currentDirectoryFiles.add(MetadataFile(rootFile.uri, it))
            }
          }
        } else {
          // root files are submitted as single audio file DirectoryPackage immediately
          val parentUri = getParentUri(rootFile.uri);
          if(parentUri != null) {
            val meta = MetadataFile(parentUri, rootFile)
            meta.completedStages.add(AutoScannerStages.DirectoryScanCompleted)
            send(DirectoryPackage(rootFile.uri, listOf(meta)))
          }
        }
      }
    }

    // here we scan all the loaded DirectoryPackage files for their metadata
    metadataLoadedChannel = ioScope.produce {
      // 2 threads to analyse metadata faster (just as an example what we could do, maybe it is not even better performance)
      repeat(2) {
        directoryPackageChannel.consumeEach { it ->
          val copy = DirectoryPackage(it.uri, it.files.toList())
          for(metadataFile in copy.files) {
            metadataFile.metadata = mediaAnalyzer.analyze(metadataFile.file)
          }
          // put the finished directoryPackage into the metadataLoadedChannel
          send(it)
        }
      }
    }

    metadataLoadedChannel.consumeEach { it ->
      // single file package without audio book properties (e.g. files in root directory)
      if(it.files.size == 1) {
        val firstFile = it.files.first()
        val chapters = chapterParser.parse(firstFile.file, firstFile.metadata)
        bookParser.parseAndStore(chapters, firstFile.file, firstFile.metadata)
      }

      // single file audio books (e.g. m4b or stik=2)
      it.files.filter { it -> it.isAudioBook()}.forEach {it ->
        val chapters = chapterParser.parse(it.file, it.metadata)
        bookParser.parseAndStore(chapters, it.file, it.metadata)
      }


      // multi-file audio books
      val multiFileBook = CustomCachedDocumentFile(
        children = it.files.filter { f -> f.isChapter() }.map { f -> f.file },
        name = it.uri.toFile().name,
        isDirectory = true,
        isFile = false,
        length = it.files.sumOf { f -> f.file.length },
        lastModified = 0, // it.files.maxOf { f -> f.file.lastModified } ?: 0,
        uri = it.uri
      )

      // todo:
      // problem: This is gonna re-retrieve the metadata by folder when used with bookParser
      var chapters = chapterParser.parse(multiFileBook)
      bookParser.parseAndStore(chapters, multiFileBook)
    }
  }

}
