package org.groktest.securemessenger.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import org.groktest.securemessenger.ui.theme.ThemePalettes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

/**
 * Кастомизация. Тема единая — чёрная графитовая (см. SecureMessengerTheme),
 * поэтому контролы темы/стиля/акцента/прозрачности убраны. Остаются только
 * шрифт и обои чата.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationScreen(onBack: () -> Unit) {
    val themeSettings = LocalThemeSettings.current

    val fonts = listOf(
        "Default" to "По умолчанию",
        "Serif" to "С засечками",
        "SansSerif" to "Без засечек",
        "Monospace" to "Моноширинный",
        "Cursive" to "Рукописный"
    )

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            themeSettings.setBackgroundImageUri(uri.toString())
        }
    }

    GlassBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Кастомизация", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item {
                    Text(
                        "Тема",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(ThemePalettes.all) { p ->
                            val selected = themeSettings.themeKey.value == p.key
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { themeSettings.setThemeKey(p.key) }
                                        .background(p.scheme.background)
                                        .border(
                                            width = if (selected) 2.5.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                ) {
                                    // Мини-превью: входящий + исходящий пузырь
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(9.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(Modifier.fillMaxWidth(0.72f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.scheme.surfaceVariant))
                                        Box(Modifier.align(Alignment.End).fillMaxWidth(0.6f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.scheme.tertiary))
                                    }
                                    // Акцент-точка
                                    Box(
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                            .size(14.dp).clip(CircleShape).background(p.scheme.primary)
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    p.title,
                                    fontSize = 12.sp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    Text(
                        "Шрифт",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 0.dp, bottom = 8.dp)
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(fonts) { (fontKey, fontName) ->
                            val isSelected = themeSettings.fontFamilyStyle.value == fontKey
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { themeSettings.setFontFamilyStyle(fontKey) }
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    fontName,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                }

                item {
                    Text(
                        "Быстрая реакция",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    Text(
                        "Ставится двойным тапом по сообщению",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(listOf("❤️", "👍", "🔥", "😂", "😮", "😢")) { emoji ->
                            val isSelected = themeSettings.quickReaction.value == emoji
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .clickable { themeSettings.setQuickReaction(emoji) }
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(emoji, fontSize = 24.sp)
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                }

                item {
                    Text(
                        "Обои чатов",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    )

                    ListItem(
                        headlineContent = { Text("Установить свои обои") },
                        supportingContent = { Text("Выбрать из галереи") },
                        modifier = Modifier.clickable { launcher.launch("image/*") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    if (themeSettings.backgroundImageUri.value != null) {
                        ListItem(
                            headlineContent = { Text("Сбросить обои", color = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.clickable { themeSettings.setBackgroundImageUri(null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}
