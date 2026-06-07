package com.bahm.thoth.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bahm.thoth.ui.common.AnswerCitation
import com.bahm.thoth.ui.common.AnswerContent

@Composable
fun UserMessageBubble(message: ChatMessage) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "You",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun AssistantMessageBubble(
    message: ChatMessage,
    onOpenArticle: (zimEntryPath: String) -> Unit,
) {
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Thoth",
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
            )
            Spacer(Modifier.height(4.dp))

            if (message.isGenerating) {
                ThinkingIndicator(color = textColor)
            } else {
                AnswerContent(
                    html = message.content,
                    citations = message.sources.map { AnswerCitation(it.articleTitle, it.zimEntryPath) },
                    onOpenArticle = onOpenArticle,
                    textColor = textColor,
                    accentColor = accentColor,
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.height(16.dp).width(16.dp),
            strokeWidth = 2.dp,
            color = color,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "thinking…",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}
