package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ScrapedAsset
import com.example.data.ScrapedSession
import com.example.scraper.CaptureApi
import com.example.scraper.ScraperEngine
import com.example.scraper.ZipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UiState {
    data object Home : UiState
    data class Dashboard(val sessionId: String) : UiState
    data object History : UiState
    data class Browser(val url: String) : UiState
}

data class DashboardUiState(
    val session: ScrapedSession? = null,
    val assets: List<ScrapedAsset> = emptyList(),
    val filteredAssets: List<ScrapedAsset> = emptyList(),
    val selectedCategory: String = "ALL",
    val searchQuery: String = "",
    val minSizeKb: Int = 0,
    val maxSizeKb: Int = 50000,
    val selectedAssetIds: Set<String> = emptySet(),
    val isExporting: Boolean = false,
    val exportProgressCurrent: Int = 0,
    val exportProgressTotal: Int = 0,
    val exportStatusText: String = "",
    val exportedZipFile: File? = null,
    val selectedAssetForPreview: ScrapedAsset? = null,
    val codePreviewAsset: ScrapedAsset? = null,
    val codePreviewContent: String? = null,
    val isLoadingCode: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).scrapedDao()

    val sessions: StateFlow<List<ScrapedSession>> = dao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<UiState>(UiState.Home)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isScraping = MutableStateFlow(false)
    val isScraping: StateFlow<Boolean> = _isScraping.asStateFlow()

    private val _scrapingError = MutableStateFlow<String?>(null)
    val scrapingError: StateFlow<String?> = _scrapingError.asStateFlow()

    private val _dashboardState = MutableStateFlow(DashboardUiState())
    val dashboardState: StateFlow<DashboardUiState> = _dashboardState.asStateFlow()

    fun navigateTo(state: UiState) {
        _uiState.value = state
        if (state is UiState.Dashboard) {
            loadSessionAndAssets(state.sessionId)
        }
    }

    fun startScraping(url: String) {
        if (url.isBlank()) {
            _scrapingError.value = "Please enter a valid URL."
            return
        }

        viewModelScope.launch {
            _isScraping.value = true
            _scrapingError.value = null
            try {
                val (session, assets) = ScraperEngine.scrapeUrl(url.trim())
                dao.insertSession(session)
                dao.deleteAssetsForSession(session.id)
                dao.insertAssets(assets)

                navigateTo(UiState.Dashboard(session.id))
            } catch (e: Exception) {
                _scrapingError.value = "Extraction failed: ${e.localizedMessage}"
            } finally {
                _isScraping.value = false
            }
        }
    }

    fun loadSessionAndAssets(sessionId: String) {
        viewModelScope.launch {
            val session = dao.getSessionById(sessionId)
            val assets = dao.getAssetsForSessionSync(sessionId)
            val allIds = assets.map { it.id }.toSet()
            _dashboardState.update {
                it.copy(
                    session = session,
                    assets = assets,
                    selectedAssetIds = allIds,
                    filteredAssets = filterAssets(assets, it.selectedCategory, it.searchQuery, it.minSizeKb, it.maxSizeKb)
                )
            }
        }
    }

    fun setSelectedCategory(category: String) {
        _dashboardState.update { state ->
            state.copy(
                selectedCategory = category,
                filteredAssets = filterAssets(state.assets, category, state.searchQuery, state.minSizeKb, state.maxSizeKb)
            )
        }
    }

    fun setSearchQuery(query: String) {
        _dashboardState.update { state ->
            state.copy(
                searchQuery = query,
                filteredAssets = filterAssets(state.assets, state.selectedCategory, query, state.minSizeKb, state.maxSizeKb)
            )
        }
    }

    fun setSizeFilter(minKb: Int, maxKb: Int) {
        _dashboardState.update { state ->
            state.copy(
                minSizeKb = minKb,
                maxSizeKb = maxKb,
                filteredAssets = filterAssets(state.assets, state.selectedCategory, state.searchQuery, minKb, maxKb)
            )
        }
    }

    private fun filterAssets(assets: List<ScrapedAsset>, category: String, query: String, minKb: Int, maxKb: Int): List<ScrapedAsset> {
        return assets.filter { asset ->
            val matchesCategory = category == "ALL" || asset.category.equals(category, ignoreCase = true)
            val matchesQuery = query.isBlank() || asset.fileName.contains(query, ignoreCase = true) || asset.url.contains(query, ignoreCase = true)
            val sizeKb = (asset.sizeBytes / 1024).toInt()
            val matchesSize = sizeKb in minKb..maxKb
            matchesCategory && matchesQuery && matchesSize
        }
    }

    fun toggleAssetSelection(assetId: String) {
        _dashboardState.update { state ->
            val newSelected = if (state.selectedAssetIds.contains(assetId)) {
                state.selectedAssetIds - assetId
            } else {
                state.selectedAssetIds + assetId
            }
            state.copy(selectedAssetIds = newSelected)
        }
    }

    fun selectAllFiltered(select: Boolean) {
        _dashboardState.update { state ->
            val filteredIds = state.filteredAssets.map { it.id }.toSet()
            val newSelected = if (select) {
                state.selectedAssetIds + filteredIds
            } else {
                state.selectedAssetIds - filteredIds
            }
            state.copy(selectedAssetIds = newSelected)
        }
    }

    fun setSelectedAssetForPreview(asset: ScrapedAsset?) {
        _dashboardState.update { it.copy(selectedAssetForPreview = asset) }
    }

    fun loadCodePreview(asset: ScrapedAsset) {
        viewModelScope.launch {
            _dashboardState.update { it.copy(codePreviewAsset = asset, isLoadingCode = true, codePreviewContent = null) }
            val content = withContext(Dispatchers.IO) {
                try {
                    CaptureApi.readTextAsset(asset)
                } catch (e: Exception) {
                    "No se pudo abrir el contenido capturado: ${e.localizedMessage}\nURL: ${asset.url}"
                }
            }
            _dashboardState.update { it.copy(codePreviewContent = content, isLoadingCode = false) }
        }
    }

    fun clearCodePreview() {
        _dashboardState.update { it.copy(codePreviewAsset = null, codePreviewContent = null) }
    }

    fun exportZip(context: Context) {
        val session = _dashboardState.value.session ?: return
        val allAssets = _dashboardState.value.assets
        val selectedIds = _dashboardState.value.selectedAssetIds
        val assetsToExport = allAssets.filter { selectedIds.contains(it.id) }

        if (assetsToExport.isEmpty()) return

        viewModelScope.launch {
            _dashboardState.update {
                it.copy(
                    isExporting = true,
                    exportProgressCurrent = 0,
                    exportProgressTotal = assetsToExport.size,
                    exportStatusText = "Preparing ZIP..."
                )
            }

            var zipFile: File? = null
            var exportError = ""
            try {
                zipFile = ZipExporter.exportAssetsToZip(context, session.title, assetsToExport) { current, total, fileName ->
                    _dashboardState.update {
                        it.copy(
                            exportProgressCurrent = current,
                            exportProgressTotal = total,
                            exportStatusText = "Preparando ZIP ($current/$total): $fileName"
                        )
                    }
                }
            } catch (e: Exception) {
                exportError = e.localizedMessage ?: "No se pudo crear el ZIP."
            }

            _dashboardState.update {
                it.copy(
                    isExporting = false,
                    exportedZipFile = zipFile,
                    exportStatusText = exportError
                )
            }
        }
    }

    fun clearExportedZip() {
        _dashboardState.update { it.copy(exportedZipFile = null) }
    }

    fun shareZip(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share Scraped Assets ZIP")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error sharing ZIP: ${e.message}")
        }
    }

    fun deleteSession(session: ScrapedSession) {
        viewModelScope.launch {
            dao.deleteAssetsForSession(session.id)
            dao.deleteSession(session)
        }
    }
}
