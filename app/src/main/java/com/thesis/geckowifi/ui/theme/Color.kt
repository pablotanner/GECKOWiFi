package com.thesis.geckowifi.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The app's only palette. Every screen reads colours from MaterialTheme.colorScheme,
 * never from these values directly, so light and dark stay in sync.
 *
 * One accent (gecko green: registered, verified) and one alarm colour (red:
 * CONFLICT only). Neutrals carry everything else. Dynamic colour is off on
 * purpose: a security warning must look the same on every phone, whatever the
 * wallpaper.
 *
 * Contrast measured with the ux-ui-agent-skills contrast checker (WCAG 2.2):
 * text pairs are all >= 5:1, outline vs. background >= 4.1:1.
 */

private val Green10 = Color(0xFF0B3D2A)
private val Green30 = Color(0xFF0F4D37)
private val Green40 = Color(0xFF1F6B4F)
private val Green80 = Color(0xFF8AD5B1)
private val Green90 = Color(0xFFCDEBDC)
private val Green95 = Color(0xFFB7F0D3)
private val Green20 = Color(0xFF003825)

private val Red10 = Color(0xFF410E0B)
private val Red20 = Color(0xFF601410)
private val Red30 = Color(0xFF7A1A15)
private val Red40 = Color(0xFFB3261E)
private val Red80 = Color(0xFFF2B8B5)
private val Red90 = Color(0xFFF9DEDC)
private val Red95 = Color(0xFFFFDAD6)

private val Warm99 = Color(0xFFFBFAF7)
private val Warm96 = Color(0xFFF1EFEA)
private val Warm92 = Color(0xFFE8E5DF)
private val Warm88 = Color(0xFFDCD9D2)
private val Warm50 = Color(0xFF767A73)
private val Warm30 = Color(0xFF4A4D48)
private val Warm10 = Color(0xFF1B1C1A)

private val Night6 = Color(0xFF121411)
private val Night10 = Color(0xFF1C1F1B)
private val Night15 = Color(0xFF272A26)
private val Night25 = Color(0xFF3A3F39)
private val Night60 = Color(0xFF8C9189)
private val Night80 = Color(0xFFBFC3BB)
private val Night90 = Color(0xFFE4E3DE)

val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Warm30,
    onSecondary = Color.White,
    secondaryContainer = Warm92,
    onSecondaryContainer = Warm10,
    background = Warm99,
    onBackground = Warm10,
    surface = Warm99,
    onSurface = Warm10,
    surfaceVariant = Warm92,
    onSurfaceVariant = Warm30,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Warm96,
    surfaceContainer = Warm96,
    surfaceContainerHigh = Warm92,
    surfaceContainerHighest = Warm88,
    outline = Warm50,
    outlineVariant = Warm88,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10
)

val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green20,
    primaryContainer = Green30,
    onPrimaryContainer = Green95,
    secondary = Night80,
    onSecondary = Night6,
    secondaryContainer = Night15,
    onSecondaryContainer = Night90,
    background = Night6,
    onBackground = Night90,
    surface = Night6,
    onSurface = Night90,
    surfaceVariant = Night15,
    onSurfaceVariant = Night80,
    surfaceContainerLowest = Night6,
    surfaceContainerLow = Night10,
    surfaceContainer = Night10,
    surfaceContainerHigh = Night15,
    surfaceContainerHighest = Night25,
    outline = Night60,
    outlineVariant = Night25,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red95
)
