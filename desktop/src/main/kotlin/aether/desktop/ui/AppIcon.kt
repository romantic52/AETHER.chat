package aether.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Иконка приложения и трея рисуется кодом: отдельного ассета в репозитории нет,
 * а трею нужен ещё и вариант со счётчиком непрочитанных.
 */
object AppIcon {
    private val cache = HashMap<String, Painter>()

    fun tray(unread: Int, size: Int = 32): Painter = cache.getOrPut("tray-$unread-$size") {
        BitmapPainter(render(unread, size).toComposeImageBitmap())
    }

    fun window(size: Int = 128): Painter = cache.getOrPut("window-$size") {
        BitmapPainter(render(0, size).toComposeImageBitmap())
    }

    fun awtImage(size: Int = 64): BufferedImage = render(0, size)

    private fun render(unread: Int, size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g.color = Color(0x33, 0x90, 0xEC)
        g.fillOval(0, 0, size - 1, size - 1)

        g.color = Color.WHITE
        g.font = Font("Segoe UI", Font.BOLD, (size * 0.56f).toInt())
        val glyph = "Æ"
        val metrics = g.fontMetrics
        g.drawString(
            glyph,
            (size - metrics.stringWidth(glyph)) / 2f,
            (size - metrics.height) / 2f + metrics.ascent,
        )

        if (unread > 0) {
            val badge = (size * 0.56f).toInt()
            val bx = size - badge
            val by = size - badge
            g.color = Color(0xE5, 0x3E, 0x3E)
            g.fillOval(bx, by, badge - 1, badge - 1)
            g.color = Color.WHITE
            g.stroke = BasicStroke(size * 0.05f)
            g.drawOval(bx, by, badge - 1, badge - 1)
            // Compose рисует иконку трея в 16×16 (жёстко зашито для Windows):
            // цифра там превращается в кашу, поэтому число показываем только на
            // крупных иконках, а в трее остаётся заметная точка. Точный счётчик
            // виден в тултипе трея и в заголовке окна.
            if (size >= 32) {
                val label = if (unread > 99) "99+" else unread.toString()
                g.font = Font("Segoe UI", Font.BOLD, (badge * if (label.length > 2) 0.42f else 0.62f).toInt())
                val badgeMetrics = g.fontMetrics
                g.drawString(
                    label,
                    bx + (badge - badgeMetrics.stringWidth(label)) / 2f,
                    by + (badge - badgeMetrics.height) / 2f + badgeMetrics.ascent,
                )
            }
        }
        g.dispose()
        return image
    }
}
