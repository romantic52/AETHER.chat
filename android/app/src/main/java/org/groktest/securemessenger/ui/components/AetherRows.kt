package org.groktest.securemessenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherCircle
import org.groktest.securemessenger.ui.theme.aetherIsland

/**
 * Эталонные элементы «настроечных» экранов (канон Aether Liquid Glass).
 * Вынесены из SettingsScreen, чтобы все под-экраны (Безопасность,
 * Конфиденциальность, Уведомления, Эксперименты, профили) выглядели одинаково:
 * строка-остров с иконкой в стеклянном круге, заголовок секции,
 * основная кнопка и круглая кнопка-действие.
 */

/** Заголовок секции настроек: акцентный, 13sp SemiBold, отступ как у SettingsScreen. */
@Composable
fun AetherSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(start = 12.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * Строка-остров настроек: aetherIsland(RowRadius) + иконка в aetherCircle,
 * опциональный trailing-слот (Switch, TextButton, стрелка и т.п.).
 */
@Composable
fun AetherSettingsRow(
    title: String,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.RowRadius),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = AetherStyle.SoftStrokeAlpha
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(AetherStyle.SmallControlSize)
                    .aetherCircle(),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides accent) {
                    icon()
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Строка-остров с переключателем; тап по всей строке тоже переключает. */
@Composable
fun AetherSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    AetherSettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
        onClick = { if (enabled) onCheckedChange(!checked) }
    )
}

/** Основная кнопка приложения: 52dp, скругление FieldRadius, акцент темы. */
@Composable
fun AetherPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(AetherStyle.FieldRadius)
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Круглая кнопка-действие профилей/групп: стеклянный круг ControlSize + подпись. */
@Composable
fun AetherActionCircle(
    icon: ImageVector,
    label: String? = null,
    onClick: () -> Unit,
    contentDescription: String? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(AetherStyle.ControlSize)
                .aetherCircle()
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDescription ?: label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        if (label != null) {
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
