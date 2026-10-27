package com.anyfile.x.feature.details

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.NameCheck
import com.anyfile.x.files.ApkInfoReader
import com.anyfile.x.files.FileHasher
import com.anyfile.x.files.FileLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Details of one file plus its checksums and APK info; the work stops when the sheet closes. */
class DetailsViewModel(application: Application) : AndroidViewModel(application) {

    data class Info(
        val inspection: FileInspector.Inspection,
        val suggestion: NameCheck.Suggestion?,
        /** A file-system path, or null when only the URI is known. */
        val path: String?,
        val readableUri: String,
        val modified: Long?
    )

    /** A computation that runs on request: progress, then a value or an error. */
    sealed interface Computation<out T> {
        data class Running(val done: Long, val total: Long?) : Computation<Nothing>
        data class Done<T>(val value: T) : Computation<T>
        data class Failed(val message: String) : Computation<Nothing>
    }

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()

    private val _checksums = MutableStateFlow<Computation<FileHasher.Hashes>?>(null)
    val checksums: StateFlow<Computation<FileHasher.Hashes>?> = _checksums.asStateFlow()

    private val _apkInfo = MutableStateFlow<Computation<ApkInfoReader.ApkInfo>?>(null)
    val apkInfo: StateFlow<Computation<ApkInfoReader.ApkInfo>?> = _apkInfo.asStateFlow()

    private var uri: Uri? = null

    /** Reads the details of [uri] once; [preset] skips detecting the type again. */
    fun load(uri: Uri, preset: FileInspector.Inspection?, fallbackMime: String) {
        if (this.uri != null) return
        this.uri = uri
        val app = getApplication<Application>()
        viewModelScope.launch {
            val metadata = FileInspector.metadata(app, uri)
            val name = preset?.displayName ?: metadata.displayName ?: uri.lastPathSegment
            val inspection = FileInspector.Inspection(
                displayName = name,
                size = preset?.size ?: metadata.size,
                result = preset?.result ?: FileInspector.detect(app, uri, name, fallbackMime)
            )
            // Recognizing a document URI asks the package manager.
            val path = withContext(Dispatchers.IO) { FileLocation.path(app, uri, metadata.dataPath) }
            _info.value = Info(
                inspection = inspection,
                suggestion = NameCheck.check(inspection.displayName, inspection.result),
                path = path,
                readableUri = FileLocation.readableUri(uri),
                modified = metadata.lastModified
            )
        }
    }

    fun computeChecksums() {
        val uri = uri ?: return
        run(_checksums) { onProgress -> FileHasher.hash(getApplication<Application>(), uri, size(), onProgress) }
    }

    fun readApkInfo() {
        val uri = uri ?: return
        run(_apkInfo) { onProgress -> ApkInfoReader.read(getApplication<Application>(), uri, size(), onProgress) }
    }

    private fun size(): Long? = _info.value?.inspection?.size

    private fun <T> run(
        state: MutableStateFlow<Computation<T>?>,
        work: suspend (onProgress: (Long, Long?) -> Unit) -> T
    ) {
        if (state.value is Computation.Running) return
        val total = size()
        state.value = Computation.Running(0, total)
        viewModelScope.launch {
            state.value = try {
                Computation.Done(work { done, _ -> state.value = Computation.Running(done, total) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Computation.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
