package com.thesis.geckowifi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.R
import com.thesis.geckowifi.ui.theme.Space

/**
 * The app's brand, in one place. GECKOGuard is the app; GECKO is the geographic
 * PKI it checks networks against, so in-app copy keeps saying "registered with GECKO".
 *
 * The logo is res/drawable/ic_brand_logo.xml (a placeholder for now): swap that
 * file and every place below picks it up.
 */
object Brand {
    const val NAME = "GECKOGuard"
    internal const val PREFIX = "GECKO"
    internal const val SUFFIX = "Guard"
}

/** The logo, drawn with its own colours (no tint), so a multi-colour logo works as is. */
@Composable
fun BrandLogo(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(
        painter = painterResource(R.drawable.ic_brand_logo),
        contentDescription = null,
        modifier = modifier.size(size)
    )
}

/** "GECKO" in the text colour, "Guard" in the accent: reads as GECKO plus what the app does. */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Text(
        buildAnnotatedString {
            append(Brand.PREFIX)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(Brand.SUFFIX) }
        },
        style = style,
        modifier = modifier
    )
}

/** Logo + wordmark, read by screen readers as just the app name. */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    logoSize: Dp = 28.dp,
    style: TextStyle = MaterialTheme.typography.titleLarge
) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = Brand.NAME },
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandLogo(size = logoSize)
        BrandWordmark(style = style)
    }
}
