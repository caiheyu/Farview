package com.github.caiheyu.farview.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

enum class Glyph(val path: String) {
    CAMERA("M4,7 L8,7 L10,4 L14,4 L16,7 L20,7 Q22,7 22,9 L22,19 Q22,21 20,21 L4,21 Q2,21 2,19 L2,9 Q2,7 4,7 M16,14 A4,4 0,1 1,8 14 A4,4 0,1 1,16 14"),
    MONITOR("M4,3 L20,3 Q22,3 22,5 L22,16 Q22,18 20,18 L4,18 Q2,18 2,16 L2,5 Q2,3 4,3 M12,18 L12,22 M7,22 L17,22"),
    ARROW("M4,12 L20,12 M14,6 L20,12 L14,18"),
    BACK("M20,12 L4,12 M10,6 L4,12 L10,18"),
    CLOSE("M6,6 L18,18 M18,6 L6,18"),
    WIFI("M2,8 Q12,-1 22,8 M5,12 Q12,5 19,12 M8,16 Q12,12 16,16 M12,20 L12,20.2"),
    INFO("M12,22 A10,10 0,1 1,12 2 A10,10 0,1 1,12 22 M12,11 L12,17 M12,7 L12,7.1"),
    FLASH("M13,2 L4,14 L11,14 L10,22 L20,9 L13,9 Z"),
    ROTATE("M20,8 A9,9 0,1 0,21 15 M20,3 L20,8 L15,8 M9,9 L15,9 L15,15 L9,15 Z"),
    QUALITY("M4,5 L20,5 Q22,5 22,7 L22,17 Q22,19 20,19 L4,19 Q2,19 2,17 L2,7 Q2,5 4,5 M6,9 L6,15 M6,12 L10,12 M10,9 L10,15 M14,9 L14,15 L16,15 Q19,12 16,9 Z"),
    FULLSCREEN("M3,9 L3,3 L9,3 M15,3 L21,3 L21,9 M21,15 L21,21 L15,21 M9,21 L3,21 L3,15"),
    GALLERY("M4,3 L20,3 Q22,3 22,5 L22,19 Q22,21 20,21 L4,21 Q2,21 2,19 L2,5 Q2,3 4,3 M2,17 L9,10 L14,15 L18,11 L22,15 M16,7 L16,7.1"),
    MOON("M20,15 A9,9 0,1 1,9 3 A8,8 0,0 0,20 15 Z"),
    REFRESH("M20,8 A9,9 0,1 0,21 15 M20,3 L20,8 L15,8"),
    CHECK("M4,12 L9,17 L20,6"),
    SHIELD("M12,2 L21,6 L20,14 Q18,20 12,22 Q6,20 4,14 L3,6 Z M8,12 L11,15 L16,9"),
    QR("M3,3 L9,3 L9,9 L3,9 Z M15,3 L21,3 L21,9 L15,9 Z M3,15 L9,15 L9,21 L3,21 Z M15,15 L18,15 L18,18 L21,18 L21,21 L15,21 Z"),
    SUN("M16,12 A4,4 0,1 1,8 12 A4,4 0,1 1,16 12 M12,1 L12,4 M12,20 L12,23 M1,12 L4,12 M20,12 L23,12 M4,4 L6,6 M18,18 L20,20 M20,4 L18,6 M6,18 L4,20");

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            PathParser().parsePathString(path).toNodes(), PathFillType.NonZero,
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    }
}

@Composable fun FarIcon(glyph: Glyph, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(glyph.vector, contentDescription = description, modifier = modifier, tint = tint)
}
