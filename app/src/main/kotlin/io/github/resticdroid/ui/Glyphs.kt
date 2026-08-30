package io.github.resticdroid.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object Glyphs {
    val Repository: ImageVector by lazy {
        icon(
            "Repository",
            "M12,3C7.58,3 4,4.79 4,7s3.58,4 8,4 8,-1.79 8,-4 -3.58,-4 -8,-4z" +
                "M4,9.5v3.5c0,2.21 3.58,4 8,4s8,-1.79 8,-4V9.5c-1.7,1.53 -4.7,2.5 -8,2.5" +
                "s-6.3,-0.97 -8,-2.5z" +
                "M4,15.5V19c0,2.21 3.58,4 8,4s8,-1.79 8,-4v-3.5c-1.7,1.53 -4.7,2.5 -8,2.5" +
                "s-6.3,-0.97 -8,-2.5z",
        )
    }

    val Backup: ImageVector by lazy {
        icon(
            "Backup",
            "M11,3h2v7h3.5L12,15.5 7.5,10H11z" +
                "M4,17h2v2h12v-2h2v4H4z",
        )
    }

    val Folder: ImageVector by lazy {
        icon(
            "Folder",
            "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0," +
                "-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
        )
    }

    val History: ImageVector by lazy {
        icon(
            "History",
            "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7," +
                "-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27," +
                "19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 " +
                "0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
        )
    }

    val Stop: ImageVector by lazy { icon("Stop", "M6,6h12v12H6z") }

    val Visibility: ImageVector by lazy {
        icon(
            "Visibility",
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73," +
                "-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 " +
                "-2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7," +
                "-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16," +
                "2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1," +
                "12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21," +
                "20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0," +
                "1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 " +
                "-2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15," +
                "3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        }.build()
}
