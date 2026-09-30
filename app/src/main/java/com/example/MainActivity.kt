package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsState()
                val isScraping by viewModel.isScraping.collectAsState()
                val scrapingError by viewModel.scrapingError.collectAsState()
                val sessions by viewModel.sessions.collectAsState()
                val dashboardState by viewModel.dashboardState.collectAsState()

                when (val state = uiState) {
                    is UiState.Home -> {
                        HomeScreen(
                            viewModel = viewModel,
                            isScraping = isScraping,
                            errorMessage = scrapingError,
                            sessions = sessions
                        )
                    }
                    is UiState.Dashboard -> {
                        DashboardScreen(
                            viewModel = viewModel,
                            dashboardState = dashboardState
                        )
                    }
                    is UiState.History -> {
                        HistoryScreen(
                            viewModel = viewModel,
                            sessions = sessions
                        )
                    }
                    is UiState.Browser -> {
                        BrowserScreen(
                            viewModel = viewModel,
                            initialUrl = state.url,
                            isScraping = isScraping,
                            errorMessage = scrapingError
                        )
                    }
                }
            }
        }
    }
}
