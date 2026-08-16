package com.monishguy.mifanscloud.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 欢迎页板块。 */
enum class Section(val title: String, val icon: ImageVector) {
    ALBUM("相册", Icons.Filled.List),
    RECORDING("录音", Icons.Filled.Call),
    CONTACT("通讯录", Icons.Filled.Person),
    NOTE("笔记", Icons.Filled.Edit),
}

/**
 * 欢迎页：四个板块 2×2 排布，点击进入对应板块。
 */
@Composable
fun WelcomeScreen(
    onOpenSection: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("米饭云服务", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "小米云备份同步 · 选择板块",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Section.entries) { section ->
                SectionCard(section = section, onClick = { onOpenSection(section) })
            }
        }
    }
}

@Composable
private fun SectionCard(section: Section, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                section.icon,
                contentDescription = section.title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(36.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(section.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
