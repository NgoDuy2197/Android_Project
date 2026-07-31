package com.dsoft.jgamer.model

import android.view.KeyEvent

/** A face button on the on-screen gamepad. */
data class PadButton(val keyCode: Int, val label: String)

/** A selectable libretro core (bundled in jniLibs/arm64-v8a). */
data class CoreSpec(val file: String, val label: String)

/** On-screen face-button arrangement. SIXBUTTON = Sega 2-row grid (X Y Z / A B C). */
enum class PadStyle { STANDARD, SIXBUTTON }

/**
 * The emulated systems. Each lists one or more candidate [cores] (the first is
 * the default; extras let the user switch engine per game — important for arcade
 * where a romset may only boot on a specific core). [zipIsRom] marks systems
 * (arcade) where a .zip IS the ROM and must NOT be extracted.
 */
enum class GameSystem(
    val id: String,
    val displayName: String,
    val cores: List<CoreSpec>,
    val extensions: List<String>,
    val buttons: List<PadButton>,
    val zipIsRom: Boolean = false,
    val padStyle: PadStyle = PadStyle.STANDARD
) {
    NES("nes", "NES",
        listOf(CoreSpec("fceumm_libretro_android.so", "FCEUmm")),
        listOf("nes", "fds", "unf", "zip"),
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "B"),
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "A"),
            PadButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SEL"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "START")
        )),
    SNES("snes", "SNES",
        listOf(CoreSpec("snes9x_libretro_android.so", "Snes9x")),
        listOf("sfc", "smc", "swc", "fig", "bs", "zip"),
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_Y, "Y"),
            PadButton(KeyEvent.KEYCODE_BUTTON_X, "X"),
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "B"),
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "A"),
            PadButton(KeyEvent.KEYCODE_BUTTON_L1, "L"),
            PadButton(KeyEvent.KEYCODE_BUTTON_R1, "R"),
            PadButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SEL"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "START")
        )),
    GB("gb", "GB/GBC",
        listOf(
            CoreSpec("gambatte_libretro_android.so", "Gambatte"),
            CoreSpec("mgba_libretro_android.so", "mGBA")
        ),
        listOf("gb", "gbc", "dmg", "sgb", "zip"),
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "B"),
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "A"),
            PadButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SEL"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "START")
        )),
    GBA("gba", "GBA",
        listOf(CoreSpec("mgba_libretro_android.so", "mGBA")),
        listOf("gba", "zip"),
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "B"),
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "A"),
            PadButton(KeyEvent.KEYCODE_BUTTON_L1, "L"),
            PadButton(KeyEvent.KEYCODE_BUTTON_R1, "R"),
            PadButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SEL"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "START")
        )),
    GENESIS("genesis", "Genesis",
        listOf(
            CoreSpec("genesis_plus_gx_libretro_android.so", "Genesis Plus GX"),
            CoreSpec("picodrive_libretro_android.so", "PicoDrive")
        ),
        listOf("md", "gen", "smd", "bin", "zip"),
        // Labels are Sega buttons; keyCodes are the RetroPad codes Genesis Plus GX
        // maps to (A=Y, B=B, C=A, Y=X, X=L, Z=R, Mode=Select, Start=Start).
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_Y, "A"),
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "B"),
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "C"),
            PadButton(KeyEvent.KEYCODE_BUTTON_X, "Y"),
            PadButton(KeyEvent.KEYCODE_BUTTON_L1, "X"),
            PadButton(KeyEvent.KEYCODE_BUTTON_R1, "Z"),
            PadButton(KeyEvent.KEYCODE_BUTTON_SELECT, "MODE"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "START")
        ),
        padStyle = PadStyle.SIXBUTTON),
    ARCADE("arcade", "Arcade",
        listOf(
            CoreSpec("fbneo_libretro_android.so", "FinalBurn Neo"),
            CoreSpec("mame2003_plus_libretro_android.so", "MAME 2003-Plus"),
            CoreSpec("mame2003_libretro_android.so", "MAME 2003 (0.78)"),
            CoreSpec("mame2010_libretro_android.so", "MAME 2010 (0.139)")
        ),
        listOf("zip", "chd"),
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_Y, "Y"),
            PadButton(KeyEvent.KEYCODE_BUTTON_X, "X"),
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "B"),
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "A"),
            PadButton(KeyEvent.KEYCODE_BUTTON_L1, "L"),
            PadButton(KeyEvent.KEYCODE_BUTTON_R1, "R"),
            PadButton(KeyEvent.KEYCODE_BUTTON_SELECT, "COIN"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "START")
        ),
        zipIsRom = true),
    PICO8("pico8", "PICO-8",
        listOf(CoreSpec("retro8_libretro_android.so", "Retro8")),
        listOf("p8", "png"),
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "O"),
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "X"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "MENU")
        ));

    /** Default core file. */
    val coreFile: String get() = cores.first().file

    companion object {
        fun fromId(id: String?): GameSystem = entries.firstOrNull { it.id == id } ?: NES

        fun guessFromName(name: String): GameSystem? {
            val lower = name.lowercase()
            if (lower.endsWith(".p8") || lower.endsWith(".p8.png")) return PICO8
            val ext = lower.substringAfterLast('.', "")
            if (ext == "zip" || ext.isEmpty()) return null
            return entries.firstOrNull { !it.zipIsRom && ext in it.extensions && ext != "zip" }
        }

        fun matchesSystem(name: String, system: GameSystem): Boolean {
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            if (system.zipIsRom) return ext in system.extensions       // arcade: .zip/.chd
            if (system == PICO8) return lower.endsWith(".p8") || lower.endsWith(".p8.png")
            return ext in system.extensions && ext != "zip"
        }
    }
}
