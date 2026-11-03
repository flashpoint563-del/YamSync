package io.github.audiz.ui

import androidx.compose.runtime.Composable

/**
 * 🎨 Настройка цвета текста и иконок системного статус-бара.
 * @param darkContent true = темный текст (для светлой темы), false = белый текст (для темной темы).
 */
@Composable
expect fun SetStatusBarDarkContent(darkContent: Boolean)
