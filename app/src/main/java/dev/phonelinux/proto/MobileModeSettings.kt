package dev.phonelinux.proto

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** User-adjustable mobile-desktop settings (Phase A bounded set, from
 *  Ash's 2026-10-29 "customizable screen/sizes" criteria). Every control
 *  maps to a REAL mechanism in GuestMobileShell: Xft.dpi + jwm font and
 *  tray geometry + xsetroot. Nothing here promises per-app UI changes.
 *  Read at session-launch time, so changes apply on the next mobile
 *  session start (no live retheme of a running session in Phase A). */
data class MobileUiPrefs(
    val scale: Float = 1.0f,
    val panelSize: String = "normal",     // normal | large
    val panelPosition: String = "bottom", // bottom | top
    val background: String = "#2E3440"
)

object MobileModeSettings {
    const val KEY_SCALE = "ldoa_mobile_ui_scale"
    const val KEY_PANEL_SIZE = "ldoa_mobile_panel_size"
    const val KEY_PANEL_POSITION = "ldoa_mobile_panel_position"
    const val KEY_BACKGROUND = "ldoa_mobile_background"

    val BACKGROUNDS = listOf("#2E3440", "#3B2437", "#16324F", "#1F3A2D")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("plinux_mobile", Context.MODE_PRIVATE)

    fun read(ctx: Context): MobileUiPrefs {
        val p = prefs(ctx)
        val bg = p.getString(KEY_BACKGROUND, BACKGROUNDS[0]) ?: BACKGROUNDS[0]
        return MobileUiPrefs(
            scale = p.getFloat(KEY_SCALE, 1.0f).coerceIn(0.75f, 2.0f),
            panelSize = if (p.getString(KEY_PANEL_SIZE, "normal") == "large") "large" else "normal",
            panelPosition = if (p.getString(KEY_PANEL_POSITION, "bottom") == "top") "top" else "bottom",
            background = if (HEX.matches(bg)) bg else BACKGROUNDS[0])
    }

    fun write(ctx: Context, ui: MobileUiPrefs) {
        prefs(ctx).edit()
            .putFloat(KEY_SCALE, ui.scale.coerceIn(0.75f, 2.0f))
            .putString(KEY_PANEL_SIZE, ui.panelSize)
            .putString(KEY_PANEL_POSITION, ui.panelPosition)
            .putString(KEY_BACKGROUND, if (HEX.matches(ui.background)) ui.background else BACKGROUNDS[0])
            .apply()
    }
}

/** The settings card. NOT YET WIRED: the picker entry is the GNOME
 *  worker's coordinated MainActivity edit; this composable is ready to
 *  drop into that entry unchanged. */
@Composable
fun MobileModeSettingsCard(ctx: Context) {
    var ui by remember { mutableStateOf(MobileModeSettings.read(ctx)) }
    fun save(next: MobileUiPrefs) { ui = next; MobileModeSettings.write(ctx, next) }
    val textDim = Color(0xFF9AA6C8)
    val cardBg = Color(0xFF1A2340)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardBg)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "mobile-mode-settings" }
    ) {
        Text("Mobile desktop", color = textDim, fontSize = 13.sp)
        Text("Size ${(ui.scale * 100).roundToInt()}%", color = Color.White, fontSize = 14.sp)
        Slider(
            value = ui.scale,
            onValueChange = { save(ui.copy(scale = (it * 20).roundToInt() / 20f)) },
            valueRange = 0.75f..2.0f,
            modifier = Modifier.semantics { contentDescription = "mobile-ui-scale" }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Large touch panel", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = ui.panelSize == "large",
                onCheckedChange = { save(ui.copy(panelSize = if (it) "large" else "normal")) },
                modifier = Modifier.semantics { contentDescription = "mobile-panel-size" })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Panel on top", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = ui.panelPosition == "top",
                onCheckedChange = { save(ui.copy(panelPosition = if (it) "top" else "bottom")) },
                modifier = Modifier.semantics { contentDescription = "mobile-panel-position" })
        }
        Text("Background", color = textDim, fontSize = 13.sp)
        Row {
            for (hex in MobileModeSettings.BACKGROUNDS) {
                val selected = ui.background.equals(hex, ignoreCase = true)
                Box(
                    Modifier.padding(4.dp).size(36.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { save(ui.copy(background = hex)) }
                        .semantics { contentDescription = "mobile-bg-" + hex.removePrefix("#") }
                )
            }
        }
        Text("Applies when the mobile desktop starts.", color = textDim, fontSize = 11.sp)
    }
}
