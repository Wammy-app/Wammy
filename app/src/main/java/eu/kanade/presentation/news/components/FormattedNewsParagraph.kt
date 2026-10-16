package eu.kanade.presentation.news.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FormattedNewsParagraph(
    paragraph: String,
    modifier: Modifier = Modifier,
) {
    val p = paragraph.trim()
    if (p.isBlank()) return

    // 1. Section Sub-headings (e.g. "Cast members include:", "Staff members are:")
    val isSectionHeading = (p.endsWith(":") || p.endsWith(": ")) &&
        !p.startsWith("•") &&
        p.length < 100 &&
        p.contains(Regex("(cast|staff|visual|trailer|premiered|included|voice|production|story|director|animation|members)", RegexOption.IGNORE_CASE))

    if (isSectionHeading) {
        Text(
            text = p,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 17.sp,
            ),
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
        )
        return
    }

    // 2. Blockquotes (e.g. “A door to another world stretches out...”)
    if (p.startsWith("“") && p.endsWith("”")) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = p,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp,
                ),
            )
        }
        return
    }

    // 3. Bullet or plain list item with Cast ("• Actor as Character" or "Actor as Character")
    val isBullet = p.startsWith("• ")
    val rawText = if (isBullet) p.removePrefix("• ").trim() else p

    if (rawText.contains(" as ")) {
        val parts = rawText.split(" as ", limit = 2)
        val actor = parts[0].trim()
        val character = parts.getOrNull(1)?.trim().orEmpty()

        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        ) {
            Text(
                text = "• ",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                        append(actor)
                    }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(" as ")
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                        append(character)
                    }
                },
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            )
        }
        return
    }

    // 4. Bullet list item with Staff Role ("• Art Director: Name")
    if (isBullet && rawText.contains(": ")) {
        val colonIdx = rawText.indexOf(": ")
        val role = rawText.substring(0, colonIdx + 1).trim()
        val people = rawText.substring(colonIdx + 2).trim()

        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        ) {
            Text(
                text = "• ",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                        append("$role ")
                    }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(people)
                    }
                },
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            )
        }
        return
    }

    // 5. Generic bullet list item
    if (isBullet) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        ) {
            Text(
                text = "• ",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = rawText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                ),
            )
        }
        return
    }

    // 6. Regular paragraph with bold support (**bold** or <b>bold</b>)
    val annotatedString = buildAnnotatedString {
        val pattern = Regex("(\\*\\*.*?\\*\\*|<b>.*?</b>)")
        var lastIndex = 0
        pattern.findAll(p).forEach { matchResult ->
            val range = matchResult.range
            if (range.first > lastIndex) {
                append(p.substring(lastIndex, range.first))
            }
            val boldContent = matchResult.value
                .removePrefix("**").removeSuffix("**")
                .removePrefix("<b>").removeSuffix("</b>")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                append(boldContent)
            }
            lastIndex = range.last + 1
        }
        if (lastIndex < p.length) {
            append(p.substring(lastIndex))
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 22.sp,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
