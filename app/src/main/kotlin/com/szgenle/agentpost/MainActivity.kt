package com.szgenle.agentpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AgentPostApp()
            }
        }
    }
}

@Composable
fun AgentPostApp() {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Text(
            text = "AgentPost · M0 骨架就绪",
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
        )
    }
}
