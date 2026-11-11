package com.wowcam.app.effect

/**
 * The cinematic looks offered by wow cam.
 *
 * Everything is expressed as a 3x3 RGB matrix plus a constant offset, so a single
 * GPU shader can switch looks instantly without re-binding the camera or touching
 * the video encoder.
 */
enum class FilterPreset {
    NORMAL,
    NATURAL,
    VIVID,
    MONO,
    WARM
}

/**
 * An immutable GPU color transform.
 *
 * [matrix] is stored column-major, ready for `glUniformMatrix3fv(..., transpose = false, ...)`.
 * [offset] is added after the matrix multiply.
 */
class ColorTransform(
    @JvmField val matrix: FloatArray,
    @JvmField val offset: FloatArray
)

object ColorFilters {

    private const val LUMA_R = 0.213f
    private const val LUMA_G = 0.715f
    private const val LUMA_B = 0.072f

    val NORMAL: ColorTransform = ColorTransform(
        floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        ),
        floatArrayOf(0f, 0f, 0f)
    )

    /** Filmic, low-contrast, gentle warm bias — keeps skin tones neutral. */
    val NATURAL: ColorTransform = build(
        saturation = 0.92f,
        gainR = 1.02f,
        gainG = 1.0f,
        gainB = 0.97f,
        contrast = 0.97f
    )

    /** Punchy social-media look: richer saturation with a gentle contrast lift. */
    val VIVID: ColorTransform = build(
        saturation = 1.38f,
        gainR = 1.02f,
        gainG = 1.02f,
        gainB = 1.02f,
        contrast = 1.06f
    )

    /** Pure luminance with a touch of contrast so blacks stay clean. */
    val MONO: ColorTransform = build(
        saturation = 0f,
        gainR = 1f,
        gainG = 1f,
        gainB = 1f,
        contrast = 1.04f
    )

    /** Warm nostalgic grade: desaturated, amber highlights, cool shadows. */
    val WARM: ColorTransform = build(
        saturation = 0.88f,
        gainR = 1.11f,
        gainG = 1.03f,
        gainB = 0.87f,
        contrast = 1.05f
    )

    fun forPreset(preset: FilterPreset): ColorTransform = when (preset) {
        FilterPreset.NORMAL -> NORMAL
        FilterPreset.NATURAL -> NATURAL
        FilterPreset.VIVID -> VIVID
        FilterPreset.MONO -> MONO
        FilterPreset.WARM -> WARM
    }

    /**
     * Builds `offset + contrast * gain * saturation` as one column-major matrix.
     * Contrast pivots around mid-grey (0.5).
     */
    private fun build(
        saturation: Float,
        gainR: Float,
        gainG: Float,
        gainB: Float,
        contrast: Float
    ): ColorTransform {
        val sat = saturationMatrix(saturation)
        val gain = floatArrayOf(
            gainR, 0f, 0f,
            0f, gainG, 0f,
            0f, 0f, gainB
        )
        val combined = multiply(gain, sat)
        val scaled = FloatArray(9) { combined[it] * contrast }
        val pivot = 0.5f * (1f - contrast)
        return ColorTransform(
            toColumnMajor(scaled),
            floatArrayOf(pivot, pivot, pivot)
        )
    }

    /** Converts a row-major 3x3 matrix into the column-major layout OpenGL ES expects. */
    private fun toColumnMajor(m: FloatArray): FloatArray = floatArrayOf(
        m[0], m[3], m[6],
        m[1], m[4], m[7],
        m[2], m[5], m[8]
    )

    /** Row-major saturation matrix using Rec. 709 luminance weights. */
    private fun saturationMatrix(saturation: Float): FloatArray {
        val inv = 1f - saturation
        val r = LUMA_R * inv
        val g = LUMA_G * inv
        val b = LUMA_B * inv
        return floatArrayOf(
            r + saturation, g, b,
            r, g + saturation, b,
            r, g, b + saturation
        )
    }

    /** Row-major 3x3 multiply: returns a * b. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) {
                    sum += a[row * 3 + k] * b[k * 3 + col]
                }
                out[row * 3 + col] = sum
            }
        }
        return out
    }
}
