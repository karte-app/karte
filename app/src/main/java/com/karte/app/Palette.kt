package com.karte.app

import android.graphics.Color

/**
 * Pleos Connect 기본 테마의 디자인 토큰 값을 옮긴 색상표.
 * 에뮬레이터(v2.1.0)의 토큰 라이브러리에서 주간·야간 값을 확인했습니다.
 * 기본 테마는 무채색 위주에 모서리 반경 0, 버튼 테두리 1dp를 씁니다.
 */
class Palette(val night: Boolean) {
    private fun pick(day: String, dark: String): Int = Color.parseColor(if (night) dark else day)

    /** colorSurfaceA */
    val surface = pick("#FFFFFF", "#151515")
    /** colorSurfaceContainer / colorSecondaryContainer */
    val container = pick("#F3F3F3", "#1E1E1E")
    /** colorPrimary / colorOnPrimary */
    val primary = pick("#000000", "#DDDDDD")
    val onPrimary = pick("#FFFFFF", "#121212")
    /** colorSecondary / colorOnSecondary */
    val secondary = pick("#FFFFFF", "#272727")
    val onSecondary = pick("#333333", "#F7F8FA")
    /** textOnSurfaceDefault / colorOnSurfaceVariant1 */
    val text = pick("#000000", "#FFFFFF")
    val textSub = pick("#494949", "#BCBABA")
    /** colorOutline / colorOutlineVariant2 / colorDivider */
    val outline = pick("#000000", "#5B5B5B")
    val outlineWeak = pick("#BDBDBD", "#383838")
    val divider = pick("#D2D2D2", "#333333")
    /** colorStatePrimaryPressed 대신 눌림 표현에 쓰는 colorStateSecondaryPressed */
    val pressed = pick("#14000000", "#14FFFFFF")
    /** colorAccent(위험·녹음 중) 채움색과 그 위의 글자색 */
    val danger = Color.parseColor("#D3232A")
    val onDanger = Color.WHITE
    /** 흰 배경·검은 배경 위에서 대비 4.5:1 이상이 되도록 고른 위험 글자색 */
    val dangerText = pick("#C4161C", "#FF7A6E")
    val scrim = pick("#66000000", "#99000000")

    /** 증상 분류 색. 모두 배경 대비 4.5:1 이상입니다. */
    fun category(category: Category): Int = when (category) {
        Category.WARNING -> dangerText
        Category.NOISE -> pick("#1262F5", "#7DAEFF")
        Category.VIBRATION -> pick("#6B3FD4", "#B49CFF")
        Category.BRAKING -> pick("#A85400", "#FFA552")
        Category.STEERING -> pick("#00766C", "#4FD1C5")
        Category.POWERTRAIN -> pick("#1B7A34", "#5FD98A")
        Category.OTHER -> textSub
    }

    /** 분류 태그의 옅은 배경(같은 색 12%) */
    fun tint(color: Int): Int = Color.argb(0x1F, Color.red(color), Color.green(color), Color.blue(color))
}
