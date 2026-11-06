package com.vitalink.wear.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Íconos de Material Design (Apache 2.0) como vectores. */
object WIcons {
    val Heart = icon(
        "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 " +
            "19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z",
    )
    val Sos = icon(
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2V7h2v6z",
    )
    val Pill = icon(
        "M4.22,11.29l7.07,-7.07c2.34,-2.34 6.14,-2.34 8.48,0s2.34,6.14 0,8.48l-7.07,7.07c-2.34,2.34 -6.14,2.34 -8.48,0" +
            "s-2.34,-6.14 0,-8.48zM5.64,12.71c-1.56,1.56 -1.56,4.09 0,5.65s4.09,1.56 5.65,0l3.54,-3.53 -5.66,-5.66 -3.53,3.54z",
    )
    val Check = icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Place = icon(
        "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5" +
            "s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z",
    )
    val Walk = icon(
        "M13.5,5.5c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM9.8,8.9L7,23h2.1l1.8,-8 2.1,2v6h2v-7.5l-2.1,-2" +
            " 0.6,-3C14.8,12 16.8,13 19,13v-2c-1.9,0 -3.5,-1 -4.3,-2.4l-1,-1.6c-0.4,-0.6 -1,-1 -1.7,-1 -0.3,0 -0.5,0.1 -0.8,0.1" +
            "L6,8.3V13h2V9.6l1.8,-0.7",
    )
    val Battery = icon(
        "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33" +
            "C17,4.6 16.4,4 15.67,4z",
    )
    val Shield = icon(
        "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59" +
            "L18,9l-8,8z",
    )
    val Bolt = icon("M7,2v11h3v9l7,-12h-4l4,-8z")
    val Fall = icon(
        "M9.5,4c1.1,0 2,0.9 2,2s-0.9,2 -2,2 -2,-0.9 -2,-2 0.9,-2 2,-2zM4,19.5L5.5,21l4,-4 1.5,1.5V21h2v-3.5l-2,-2" +
            " 1.5,-3.5c1,1.2 2.5,2 4.5,2v-2c-1.4,0 -2.6,-0.7 -3.3,-1.8l-1,-1.6C12.3,8.2 11.7,8 11,8c-0.3,0 -0.5,0.1 -0.8,0.2" +
            "L5,10.3V15h2v-3.3l1.8,-0.7 -1.5,7.5L4,19.5z",
    )
    val People = icon(
        "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3" +
            "S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z" +
            "M16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
    )
}

private fun icon(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
