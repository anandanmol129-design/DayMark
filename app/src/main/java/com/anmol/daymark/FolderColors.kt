package com.anmol.daymark

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color

/** A folder's colour index into [HabitPalette]: the chosen one, or a stable pick from its id when unassigned. */
internal fun folderColorIndex(page: BoardPage) =
    if (page.color in HabitPalette.indices) page.color else Math.floorMod(page.id.hashCode(), HabitPalette.size)

internal fun folderColor(page: BoardPage) = Color(HabitPalette[folderColorIndex(page)].toInt())

/** A random colour for a new folder, preferring one its siblings don't use yet. */
internal fun newFolderColor(siblings: List<BoardPage>): Int {
    val used = siblings.map(::folderColorIndex).toSet()
    val free = HabitPalette.indices.filter { it !in used }
    return (free.ifEmpty { HabitPalette.indices.toList() }).random()
}
