package com.ncm.app

import com.ncm.app.util.albumArtworkUrl
import com.ncm.app.util.albumArtworkThumbnailCacheKey
import com.ncm.app.util.albumArtworkThumbnailUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlsTest {

    @Test
    fun `album artwork uses one canonical source url across list and player surfaces`() {
        val original = "https://p1.music.126.net/album.jpg?param=140y140"

        assertEquals(
            "https://p1.music.126.net/album.jpg?param=700y700",
            albumArtworkUrl(original)
        )
    }

    @Test
    fun `album artwork keeps embedded data urls unchanged`() {
        val embedded = "data:image/png;base64,abc"

        assertEquals(embedded, albumArtworkUrl(embedded))
    }

    @Test
    fun `playlist artwork uses a small thumbnail source`() {
        val original = "https://p1.music.126.net/album.jpg?param=700y700"

        assertEquals(
            "https://p1.music.126.net/album.jpg?param=160y160",
            albumArtworkThumbnailUrl(original)
        )
    }

    @Test
    fun `thumbnail cache key is stable across server resize variants`() {
        val small = "https://p1.music.126.net/album.jpg?param=160y160"
        val large = "https://p1.music.126.net/album.jpg?param=700y700"

        assertEquals(
            albumArtworkThumbnailCacheKey(small),
            albumArtworkThumbnailCacheKey(large)
        )
    }
}
