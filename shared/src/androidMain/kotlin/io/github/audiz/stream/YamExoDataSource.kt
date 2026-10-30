package io.github.audiz.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

class YamExoDataSource(
    private val delegate: AndroidStreamMediaDataSource
) : DataSource {

    private var currentUri: Uri? = null
    private var bytesRemaining: Long = 0
    private var position: Long = 0

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        val totalSize = delegate.size
        
        position = dataSpec.position
        
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalSize != -1L) {
            totalSize - dataSpec.position
        } else {
            C.LENGTH_UNSET.toLong()
        }
        
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        val read = delegate.readAt(position, buffer, offset, bytesToRead)
        
        if (read > 0) {
            position += read
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= read
            }
            return read
        } else if (read == 0) {
            return 0
        } else {
            return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        // delegate.close() is called manually by the player when stopping
    }
}
