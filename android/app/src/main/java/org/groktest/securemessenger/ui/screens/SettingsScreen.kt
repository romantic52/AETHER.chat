package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToCustomization: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Настройки профиля
            ListItem(
                headlineContent = { Text("Настройки профиля", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Имя, юзернейм, аватар, био", fontSize = 14.sp) },
                leadingContent = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Профиль",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.clickable { onNavigateToProfile() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )



            // Кастомизация
            ListItem(
                headlineContent = { Text("Кастомизация", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Шрифт и обои чата", fontSize = 14.sp) },
                leadingContent = {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = "Кастомизация",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.clickable { onNavigateToCustomization() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )



            // Уведомления
            ListItem(
                headlineContent = { Text("Уведомления", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Звуки, вибрация, исключения", fontSize = 14.sp) },
                leadingContent = {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = "Уведомления",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.clickable { onNavigateToNotifications() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )



            // Конфиденциальность
            ListItem(
                headlineContent = { Text("Конфиденциальность", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Блокировка, скрытие данных", fontSize = 14.sp) },
                leadingContent = {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Конфиденциальность",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.clickable { onNavigateToPrivacy() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )



            // О приложении
            ListItem(
                headlineContent = { Text("О приложении", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Версия, лицензии", fontSize = 14.sp) },
                leadingContent = {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "О приложении",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.clickable { onNavigateToAbout() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}
