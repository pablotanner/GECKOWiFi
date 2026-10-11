package com.thesis.geckowifi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Radius
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.Tone

/** Foreground / container pair for a [Tone], from the theme so light and dark both work. */
data class ToneColors(val content: Color, val container: Color, val border: Color)

@Composable
fun Tone.colors(): ToneColors {
    val c = MaterialTheme.colorScheme
    return when (this) {
        Tone.POSITIVE -> ToneColors(c.onPrimaryContainer, c.primaryContainer, c.primaryContainer)
        Tone.DANGER -> ToneColors(c.onErrorContainer, c.errorContainer, c.error)
        Tone.NEUTRAL -> ToneColors(c.onSurface, c.surfaceContainerHigh, c.surfaceContainerHigh)
    }
}

/** Small status pill: icon plus text, never colour alone. */
@Composable
fun VerdictBadge(tone: Tone, label: String, icon: ImageVector?, modifier: Modifier = Modifier) {
    val colors = tone.colors()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.sm),
        color = colors.container,
        contentColor = colors.content,
        border = if (tone == Tone.DANGER) BorderStroke(1.dp, colors.border) else null
    ) {
        Row(
            Modifier.padding(start = Space.sm, end = Space.sm + 2.dp, top = Space.xs, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** List/section heading; marked as a heading for TalkBack navigation. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .padding(horizontal = Space.lg)
            .padding(top = Space.xl, bottom = Space.sm)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** A flat grouped block (no shadow): title plus content. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(Space.lg)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = Space.sm).semantics { heading() }
                )
            }
            content()
        }
    }
}

/** "label ...... value" row for evidence lists. [mono] for identifiers compared character by character. */
@Composable
fun DetailRow(label: String, value: String, mono: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontFamily = if (mono) MonoFontFamily else null,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
    }
}

/** Label above a full-width value, for long identifiers (domains, key hashes) that must not be truncated. */
@Composable
fun StackedValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
