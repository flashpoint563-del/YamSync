package io.github.audiz.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.audiz.DispatcherIO
import io.github.audiz.api.MusicRepository
import io.github.audiz.getLocalCoverArtUrl
import io.github.audiz.loadLocalCoverBytes
import io.github.audiz.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * 🖼️ Загрузчик и кэш обложек альбомов и треков
 */
object CoverImageLoader {
    private val cacheLock = Any()
    private val memoryCache = mutableMapOf<String, ImageBitmap>()
    private val paletteCache = mutableMapOf<String, AmbilightPalette>()
    var repository: MusicRepository? = null

    /**
     * Форматирование шаблона URI от Яндекс Музыки (например avatars.yandex.net/.../%%) в прямой URL нужного разрешения
     * Для локальных файлов возвращает локальный URL к обложке (file://...).
     */
    fun formatCoverUrl(uri: String?, size: Int = 400): String? {
        if (uri.isNullOrBlank()) return null
        var clean = uri.trim()
        if (clean.startsWith("local:") || clean.startsWith("file:") || clean.startsWith("/")) {
            return getLocalCoverArtUrl(clean) ?: clean
        }
        if (clean.contains("%%")) {
            clean = clean.replace("%%", "${size}x${size}")
        }
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return clean
    }

    fun getCachedPalette(coverUri: String?): AmbilightPalette? {
        if (coverUri.isNullOrBlank()) return null
        val trimmed = coverUri.trim()
        synchronized(cacheLock) {
            paletteCache[trimmed] ?: formatCoverUrl(trimmed, 400)?.let { paletteCache[it] }
        }?.let { return it }
        return null
    }

    /**
     * Асинхронная загрузка обложки трека с кэшированием в оперативной памяти
     */
    suspend fun loadCover(coverUri: String?, size: Int = 400): ImageBitmap? {
        if (coverUri.isNullOrBlank()) return null
        val trimmed = coverUri.trim()
        synchronized(cacheLock) { memoryCache[trimmed] }?.let { return it }

        // 1. Проверяем локальные файлы
        if (trimmed.startsWith("local:") || trimmed.startsWith("file:") || trimmed.startsWith("/")) {
            val bytes = withContext(DispatcherIO) {
                loadLocalCoverBytes(trimmed)
            }
            if (bytes != null && bytes.isNotEmpty()) {
                return try {
                    val bitmap = bytes.decodeToImageBitmap()
                    val palette = extractAmbilightPalette(bitmap, trimmed)
                    synchronized(cacheLock) {
                        memoryCache[trimmed] = bitmap
                        if (palette != null) {
                            paletteCache[trimmed] = palette
                        }
                    }
                    bitmap
                } catch (e: Exception) {
                    println("CoverImageLoader: Ошибка декодирования локальной обложки $trimmed: ${e.message}")
                    null
                }
            }
            return null
        }

        // 2. Сетевая обложка Яндекс Музыки
        val url = formatCoverUrl(trimmed, size) ?: return null
        synchronized(cacheLock) { memoryCache[url] }?.let { return it }

        val repo = repository ?: return null
        val bytes = withContext(DispatcherIO) {
            repo.fetchImageBytes(url)
        } ?: return null

        return withContext(DispatcherIO) {
            try {
                val bitmap = bytes.decodeToImageBitmap()
                val palette = extractAmbilightPalette(bitmap, trimmed)
                synchronized(cacheLock) {
                    memoryCache[trimmed] = bitmap
                    memoryCache[url] = bitmap
                    if (palette != null) {
                        paletteCache[trimmed] = palette
                        paletteCache[url] = palette
                    }
                }
                bitmap
            } catch (e: Exception) {
                println("CoverImageLoader: Ошибка декодирования изображения $url: ${e.message}")
                null
            }
        }
    }

    /**
     * Быстрая мгновенная проверка наличия обложки в оперативном кэше (без I/O операций).
     */
    fun getCached(coverUri: String?, size: Int = 400): ImageBitmap? {
        if (coverUri.isNullOrBlank()) return null
        val trimmed = coverUri.trim()
        synchronized(cacheLock) {
            memoryCache[trimmed] ?: formatCoverUrl(trimmed, size)?.let { memoryCache[it] }
        }?.let { return it }
        return null
    }

    /**
     * Предварительная фоновая загрузка обложки трека в память (например для следующего трека в очереди).
     */
    fun preloadCover(coverUri: String?, size: Int = 400) {
        if (coverUri.isNullOrBlank()) return
        if (getCached(coverUri, size) != null) return
        CoroutineScope(DispatcherIO).launch {
            loadCover(coverUri, size)
        }
    }
}

/**
 * 🎨 Composable-хэлпер для автоматической загрузки и реактивного обновления ImageBitmap.
 * Сохраняет предыдущее изображение до готовности нового для предотвращения мерцания (flicker-free).
 */
@androidx.compose.runtime.Composable
fun rememberCoverBitmap(coverUri: String?, size: Int = 400): ImageBitmap? {
    val initialBitmap = androidx.compose.runtime.remember(coverUri, size) {
        CoverImageLoader.getCached(coverUri, size)
    }
    val bitmapState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initialBitmap)
    }

    androidx.compose.runtime.LaunchedEffect(coverUri, size) {
        if (initialBitmap != null) {
            bitmapState.value = initialBitmap
        } else if (!coverUri.isNullOrBlank()) {
            val loaded = CoverImageLoader.loadCover(coverUri, size)
            if (loaded != null) {
                bitmapState.value = loaded
            }
        }
    }
    return bitmapState.value
}

