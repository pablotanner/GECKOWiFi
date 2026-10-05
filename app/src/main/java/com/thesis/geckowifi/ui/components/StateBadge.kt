package com.thesis.geckowifi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.ui.theme.ConflictRed
import com.thesis.geckowifi.ui.theme.ConflictRedContainer
import com.thesis.geckowifi.ui.theme.OnSurfaceVariant
import com.thesis.geckowifi.ui.theme.Outline
import com.thesis.geckowifi.ui.theme.SurfaceContainerHigh

private data class BadgeStyle(
    val label: String,
    val icon: ImageVector,
    val contentColor: androidx.compose.ui.graphics.Color,
    val containerColor: androidx.compose.ui.graphics.Color,
    val borderColor: androidx.compose.ui.graphics.Color
)

private fun VerificationState.style(): BadgeStyle = when (this) {
    VerificationState.VERIFIED -> BadgeStyle(
        "Verified", Icons.Outlined.CheckCircle, OnSurfaceVariant, SurfaceContainerHigh, Outline
    )
    VerificationState.CONFLICT -> BadgeStyle(
        "Mismatch", Icons.Outlined.Warning, ConflictRed, ConflictRedContainer, ConflictRed
    )
    VerificationState.UNVERIFIED -> BadgeStyle(
        "Not registered", Icons.Outlined.HelpOutline, OnSurfaceVariant, androidx.compose.ui.graphics.Color.Transparent, Outline
    )
    VerificationState.UNRECOGNIZED -> BadgeStyle(
        "Unrecognized", Icons.Outlined.HelpOutline, OnSurfaceVariant, SurfaceContainerHigh, Outline
    )
    VerificationState.UNREACHABLE -> BadgeStyle(
        "Can't check", Icons.Outlined.WifiOff, OnSurfaceVariant, SurfaceContainerHigh, Outline
    )
    VerificationState.CHECKING -> BadgeStyle(
        "Checking…", Icons.Outlined.Block, OnSurfaceVariant, androidx.compose.ui.graphics.Color.Transparent, Outline
    )
}

/** The small pill badge used throughout the design (Networks, Activity, Network Detail, Check Detail) to show a [VerificationState]. */
@Composable
fun StateBadge(state: VerificationState, modifier: Modifier = Modifier) {
    val style = state.style()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = style.containerColor,
        contentColor = style.contentColor,
        border = BorderStroke(if (state == VerificationState.CONFLICT) 2.dp else 1.dp, style.borderColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(style.icon, contentDescription = null, modifier = Modifier.width(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(style.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

