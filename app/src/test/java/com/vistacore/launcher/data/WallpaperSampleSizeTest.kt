package com.vistacore.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped wallpaper assets are full-resolution stock photos — the largest is
 * 8192x5122, which as ARGB_8888 is a 160 MB single native allocation on a box with
 * 1.4 GB of RAM. loadAssetBitmap used to decode them whole on every MainActivity
 * onResume. These pin the subsampling that makes that safe.
 *
 * Dimensions below are the real ones, read from the JPEG headers in
 * app/src/main/assets/wallpapers/.
 */
class WallpaperSampleSizeTest {

    private fun sample(w: Int, h: Int) =
        WallpaperManager.sampleSizeFor(w, h, 1920, 1080)

    /** Decoded bytes at RGB_565 (2 bytes/px), which is what loadAssetBitmap requests. */
    private fun decodedMb(w: Int, h: Int): Double {
        val s = sample(w, h)
        return (w / s).toDouble() * (h / s) * 2 / (1024 * 1024)
    }

    @Test
    fun `the worst asset no longer costs a hundred and sixty megabytes`() {
        // sunflower.jpg, 8192x5122. Full ARGB_8888 decode = 160.1 MB.
        assertEquals(4, sample(8192, 5122))
        assertTrue(
            "sunflower must decode under 8MB, was ${decodedMb(8192, 5122)}MB",
            decodedMb(8192, 5122) < 8.0
        )
    }

    @Test
    fun `the default image preset is bounded`() {
        // aurora.jpg, 6000x4000 — the first entry in imagePresets, so the one a
        // user gets by simply switching wallpaper type to image.
        assertEquals(2, sample(6000, 4000))
        assertTrue(decodedMb(6000, 4000) < 12.0)
    }

    @Test
    fun `the worst case is primate at 6240x4160, and it is a limit of power-of-two sampling`() {
        // 6240x4160 -> sample 2 -> 3120x2080 = 12.38 MB. sample 4 would give
        // 1560x1040, below the 1080p target, so it would upscale and look soft.
        // This is the ceiling the approach can reach, not a defect — and it is
        // still an 87% cut from the 99 MB ARGB_8888 full decode. Recorded here so
        // a future change that "fixes" it knows what it is trading away.
        assertEquals(2, sample(6240, 4160))
        assertTrue(decodedMb(6240, 4160) < 13.0)
        assertTrue("must beat the 99MB full decode by a wide margin", decodedMb(6240, 4160) < 99.0 / 5)
    }

    @Test
    fun `every shipped asset decodes under sixteen megabytes`() {
        val assets = listOf(
            8192 to 5122, // sunflower
            6240 to 4160, // primate
            6000 to 4000, // spectrum / nebula / marble / aurora
            5929 to 3958, // lakeside
            5496 to 3670, // mountains
            5184 to 3456, // storm_clouds
            5013 to 2945, // dark_leaves
            4288 to 2848, // night_sky
            3378 to 2482, // treeline
            3840 to 2160, // abstract_waves
            3000 to 2400, // ocean_shore
            1920 to 1258  // golden_field
        )
        for ((w, h) in assets) {
            assertTrue(
                "${w}x$h decoded to ${decodedMb(w, h)}MB",
                decodedMb(w, h) < 16.0
            )
        }
    }

    @Test
    fun `never undershoots the target - a subsampled image must still cover 1080p`() {
        // Undershooting means upscaling a too-small bitmap, which looks soft on a TV.
        // Every asset must stay at or above the target box after subsampling.
        val assets = listOf(
            8192 to 5122, 6240 to 4160, 6000 to 4000, 5929 to 3958,
            5496 to 3670, 5184 to 3456, 5013 to 2945, 4288 to 2848,
            3378 to 2482, 3840 to 2160, 3000 to 2400
        )
        for ((w, h) in assets) {
            val s = sample(w, h)
            assertTrue("${w}x$h sampled to ${w / s}x${h / s}, narrower than 1920", w / s >= 1920)
            assertTrue("${w}x$h sampled to ${w / s}x${h / s}, shorter than 1080", h / s >= 1080)
        }
    }

    @Test
    fun `an image already at or below target is never subsampled`() {
        assertEquals(1, sample(1920, 1080))
        assertEquals(1, sample(1920, 1258)) // golden_field
        assertEquals(1, sample(1280, 720))
    }

    @Test
    fun `only powers of two are returned`() {
        val cases = listOf(8192 to 5122, 6000 to 4000, 5013 to 2945, 3000 to 2400, 1920 to 1080)
        for ((w, h) in cases) {
            val s = sample(w, h)
            assertTrue("$s is not a power of two", s > 0 && (s and (s - 1)) == 0)
        }
    }

    @Test
    fun `degenerate bounds fall back to no subsampling rather than dividing by zero`() {
        // BitmapFactory reports -1 for outWidth/outHeight when it cannot decode.
        assertEquals(1, sample(0, 0))
        assertEquals(1, sample(-1, -1))
        assertEquals(1, WallpaperManager.sampleSizeFor(6000, 4000, 0, 0))
    }
}
