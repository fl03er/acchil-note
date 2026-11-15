package ai.acchil.note

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface

/**
 * Pleos Connect UI Design Guide 기반 토큰.
 * 색상은 가이드의 Colors 표(이미지)에서 견본 색을 읽어 옮겼다. 낮/밤은 시스템 테마를 따른다.
 * 서체는 가이드 지정 서체 Asta Sans (42dot, OFL).
 */
data class Palette(
    val dark: Boolean,
    val background: Int,     // background_secondary
    val surface: Int,        // surface_basic (카드)
    val surfaceLow: Int,     // surface_low
    val surfaceHigh: Int,    // surface_high
    val surfaceAccent: Int,  // surface_accent
    val text1: Int,
    val text2: Int,
    val text3: Int,
    val buttonBasic: Int,    // button_basic_enabled
    val buttonFilled: Int,   // button_filled_enabled
    val onFilled: Int,
    val green: Int,          // informative
    val blue: Int,
    val red: Int,
    val purple: Int,
) {
    fun alpha(color: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        val LIGHT = Palette(
            dark = false,
            background = 0xFFF7F8FA.toInt(), surface = 0xFFFFFFFF.toInt(), surfaceLow = 0xFFF7F8FA.toInt(),
            surfaceHigh = 0xFFEDEEF2.toInt(), surfaceAccent = 0xFFE0E1E6.toInt(),
            text1 = 0xFF131417.toInt(), text2 = 0xB3131417.toInt(), text3 = 0x73131417,
            buttonBasic = 0xFFF3F3F3.toInt(), buttonFilled = 0xFF44464E.toInt(), onFilled = Color.WHITE,
            green = 0xFF02C265.toInt(), blue = 0xFF0064FF.toInt(), red = 0xFFFE3D16.toInt(), purple = 0xFF5A46FA.toInt(),
        )
        val DARK = Palette(
            dark = true,
            background = 0xFF1D1E21.toInt(), surface = 0xFF131417.toInt(), surfaceLow = 0xFF131417.toInt(),
            surfaceHigh = 0xFF313236.toInt(), surfaceAccent = 0xFF44464E.toInt(),
            text1 = 0xFFFCFCFC.toInt(), text2 = 0xB3FCFCFC.toInt(), text3 = 0x73FCFCFC,
            buttonBasic = 0xFF313236.toInt(), buttonFilled = 0xFFE0E1E6.toInt(), onFilled = 0xFF131417.toInt(),
            green = 0xFF00E074.toInt(), blue = 0xFF0082FF.toInt(), red = 0xFFFF4C28.toInt(), purple = 0xFF6A57FF.toInt(),
        )

        fun of(context: Context): Palette {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
        }
    }
}

/** 가이드 스타일 단계(Headline / Title / Body / Label)에 맞춘 크기(sp). */
object Type {
    const val DISPLAY = 96f
    const val HEADLINE = 34f
    const val TITLE = 26f
    const val BODY = 20f
    const val LABEL = 16f

    private val cache = mutableMapOf<Int, Typeface>()

    fun asta(context: Context, weight: Int = 400): Typeface = cache.getOrPut(weight) {
        runCatching {
            Typeface.Builder(context.assets, "fonts/AstaSans.ttf")
                .setFontVariationSettings("'wght' $weight")
                .build()
        }.getOrDefault(Typeface.create(Typeface.DEFAULT, weight, false))
    }
}

/** 원인별 표시 색. 감지 유형도 같은 규칙. */
fun Palette.colorFor(type: String) = when (type) {
    AcchilDetector.INTERVENTION -> blue
    AcchilDetector.HARD_BRAKE -> red
    AcchilDetector.SWERVE -> purple
    else -> text2
}
