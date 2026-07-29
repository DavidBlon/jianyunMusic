package com.ncm.app.ui.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.ncm.app.data.model.ArtistBrief

@Suppress("DEPRECATION")
@Composable
fun ArtistLinks(
    artists: List<ArtistBrief>?,
    onArtistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    suffix: String = "",
    fallback: String = "未知歌手",
    maxLines: Int = 1
) {
    val text = remember(artists, suffix, fallback) {
        val validArtists = artists.orEmpty().filter { it.id > 0 && it.name.isNotBlank() }
        buildAnnotatedString {
            if (validArtists.isEmpty()) {
                append(fallback)
            } else {
                validArtists.forEachIndexed { index, artist ->
                    if (index > 0) append(" / ")
                    pushStringAnnotation(
                        tag = ARTIST_ID_TAG,
                        annotation = artist.id.toString()
                    )
                    append(artist.name)
                    pop()
                }
            }
            append(suffix)
        }
    }

    ClickableText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = color)),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onClick = { offset ->
            text.getStringAnnotations(
                tag = ARTIST_ID_TAG,
                start = offset,
                end = offset
            ).firstOrNull()
                ?.item
                ?.toLongOrNull()
                ?.let(onArtistClick)
        }
    )
}

private const val ARTIST_ID_TAG = "artist_id"
