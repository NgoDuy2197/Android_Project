package com.dsoft.jgamer.ui

/**
 * A control-panel colour theme. Drives the panel background, the D-pad, the
 * utility buttons and the per-face-button "candy" colours. 10 cute presets.
 */
data class ControlTheme(
    val name: String,
    val panelBg: Int,
    val edge: Int,
    val dpad: Int,
    val util: Int,
    val accent: Int,
    val text: Int,
    val a: Int,
    val b: Int,
    val x: Int,
    val y: Int,
    val lr: Int,
    val sys: Int
)

object ControlThemes {
    private fun c(v: Long) = v.toInt()

    val list: List<ControlTheme> = listOf(
        ControlTheme("Cream Retro",
            panelBg = c(0xFFE9DFB0), edge = c(0xFFCDBE86), dpad = c(0xFF5B5347),
            util = c(0xFFCFC091), accent = c(0xFFE07A3D), text = c(0xFF3A2E1E),
            a = c(0xFFE38A78), b = c(0xFFE6C079), x = c(0xFF84A9D6), y = c(0xFF8FC59A),
            lr = c(0xFFB9A7D6), sys = c(0xFFCBBD8E)),
        ControlTheme("Game Boy",
            panelBg = c(0xFFC4CFA1), edge = c(0xFF8A9A6B), dpad = c(0xFF2E3320),
            util = c(0xFFA6B487), accent = c(0xFF5A6B33), text = c(0xFF2E3320),
            a = c(0xFF6B8E23), b = c(0xFF556B2F), x = c(0xFF7E8B5A), y = c(0xFF8A9A5B),
            lr = c(0xFF4A5A2A), sys = c(0xFF8A9A6B)),
        ControlTheme("Dark Neon",
            panelBg = c(0xFF14161F), edge = c(0xFF2A2E3F), dpad = c(0xFFE0E4EC),
            util = c(0xFF2A2E3F), accent = c(0xFF00E5FF), text = c(0xFFE6ECF7),
            a = c(0xFFFF5D6C), b = c(0xFFFFC24B), x = c(0xFF4FC3F7), y = c(0xFF69F0AE),
            lr = c(0xFFB388FF), sys = c(0xFF455A64)),
        ControlTheme("Candy Pink",
            panelBg = c(0xFFFCE4EC), edge = c(0xFFF8BBD0), dpad = c(0xFFAD1457),
            util = c(0xFFF48FB1), accent = c(0xFFEC407A), text = c(0xFF6A1B3A),
            a = c(0xFFF06292), b = c(0xFFFFB74D), x = c(0xFF64B5F6), y = c(0xFF81C784),
            lr = c(0xFFBA68C8), sys = c(0xFFF8BBD0)),
        ControlTheme("Ocean",
            panelBg = c(0xFFE3F2FD), edge = c(0xFF90CAF9), dpad = c(0xFF0D47A1),
            util = c(0xFF64B5F6), accent = c(0xFF1E88E5), text = c(0xFF0D3A66),
            a = c(0xFF4FC3F7), b = c(0xFF4DD0E1), x = c(0xFF7986CB), y = c(0xFF4DB6AC),
            lr = c(0xFF9575CD), sys = c(0xFF90CAF9)),
        ControlTheme("Sunset",
            panelBg = c(0xFFFFF3E0), edge = c(0xFFFFCC80), dpad = c(0xFFE65100),
            util = c(0xFFFFB74D), accent = c(0xFFFB8C00), text = c(0xFF5D2E00),
            a = c(0xFFFF8A65), b = c(0xFFFFB300), x = c(0xFF4FC3F7), y = c(0xFF9CCC65),
            lr = c(0xFFBA68C8), sys = c(0xFFFFCC80)),
        ControlTheme("Mint",
            panelBg = c(0xFFE0F2F1), edge = c(0xFF80CBC4), dpad = c(0xFF00695C),
            util = c(0xFF4DB6AC), accent = c(0xFF26A69A), text = c(0xFF06403A),
            a = c(0xFF4DB6AC), b = c(0xFFFFB74D), x = c(0xFF4FC3F7), y = c(0xFF81C784),
            lr = c(0xFF9575CD), sys = c(0xFF80CBC4)),
        ControlTheme("Grape",
            panelBg = c(0xFFF3E5F5), edge = c(0xFFCE93D8), dpad = c(0xFF4A148C),
            util = c(0xFFBA68C8), accent = c(0xFF8E24AA), text = c(0xFF3A1052),
            a = c(0xFFBA68C8), b = c(0xFFFFB74D), x = c(0xFF64B5F6), y = c(0xFF81C784),
            lr = c(0xFF9575CD), sys = c(0xFFCE93D8)),
        ControlTheme("Mono Slate",
            panelBg = c(0xFFECEFF1), edge = c(0xFFB0BEC5), dpad = c(0xFF37474F),
            util = c(0xFF90A4AE), accent = c(0xFF546E7A), text = c(0xFF263238),
            a = c(0xFF78909C), b = c(0xFF90A4AE), x = c(0xFF607D8B), y = c(0xFFB0BEC5),
            lr = c(0xFF546E7A), sys = c(0xFFB0BEC5)),
        ControlTheme("Famicom",
            panelBg = c(0xFFE8E0D0), edge = c(0xFFC9401F), dpad = c(0xFF7A1F12),
            util = c(0xFFD8A38E), accent = c(0xFFC62828), text = c(0xFF4A1008),
            a = c(0xFFC62828), b = c(0xFFE0A030), x = c(0xFFD8C0A0), y = c(0xFFB03020),
            lr = c(0xFF7A1F12), sys = c(0xFFC9401F))
    )

    val names: Array<String> get() = list.map { it.name }.toTypedArray()
    fun get(index: Int): ControlTheme = list.getOrElse(index) { list[0] }
}
