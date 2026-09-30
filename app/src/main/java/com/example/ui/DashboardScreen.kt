package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.ScrapedAsset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    dashboardState: DashboardUiState
) {
    val context = LocalContext.current
    val session = dashboardState.session
    val assets = dashboardState.filteredAssets
    val selectedIds = dashboardState.selectedAssetIds

    // Handle exported ZIP intent sharing
    LaunchedEffect(dashboardState.exportedZipFile) {
        val file = dashboardState.exportedZipFile
        if (file != null) {
            viewModel.shareZip(context, file)
            viewModel.clearExportedZip()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session?.title ?: "Extraction Dashboard",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            text = "${selectedIds.size} of ${dashboardState.assets.size} assets selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(UiState.Home) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.exportZip(context) },
                        modifier = Modifier.testTag("export_zip_button")
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = "Export ZIP")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.exportZip(context) },
                icon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
                text = { Text("Download ZIP (${selectedIds.size})") },
                modifier = Modifier.testTag("fab_download_zip")
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Summary Stats Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatItem(label = "Detected", value = "${session?.totalAssets ?: 0}", icon = Icons.Default.Category)
                    StatItem(label = "Selected", value = "${selectedIds.size}", icon = Icons.Default.CheckCircle)
                    StatItem(label = "Size", value = "${session?.totalSizeMB ?: 0.0} MB", icon = Icons.Default.Storage)
                }
            }

            // Search Filter Bar
            OutlinedTextField(
                value = dashboardState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search assets by name or URL...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("asset_search_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Size Filter & Select All / Deselect All Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = dashboardState.minSizeKb > 0,
                        onClick = {
                            if (dashboardState.minSizeKb > 0) {
                                viewModel.setSizeFilter(0, 50000)
                            } else {
                                viewModel.setSizeFilter(10, 50000) // Filter out tiny files < 10KB
                            }
                        },
                        label = { Text(if (dashboardState.minSizeKb > 0) "Size > 10KB" else "All Sizes") }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.selectAllFiltered(true) }) {
                        Text("Select All")
                    }
                    TextButton(onClick = { viewModel.selectAllFiltered(false) }) {
                        Text("Deselect All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Category Filter Tabs
            val categories = listOf("ALL", "IMAGE", "AUDIO", "VIDEO", "SCRIPT", "DATA")
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { cat ->
                    FilterChip(
                        selected = dashboardState.selectedCategory == cat,
                        onClick = { viewModel.setSelectedCategory(cat) },
                        label = { Text(cat) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Assets List
            if (assets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No assets match your filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(assets) { asset ->
                        val isSelected = selectedIds.contains(asset.id)
                        AssetItemCard(
                            asset = asset,
                            isSelected = isSelected,
                            onToggleSelection = { viewModel.toggleAssetSelection(asset.id) },
                            onPreview = {
                                if (asset.category == "IMAGE") {
                                    viewModel.setSelectedAssetForPreview(asset)
                                } else {
                                    viewModel.loadCodePreview(asset)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Export Progress Dialog
    if (dashboardState.isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting ZIP...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(dashboardState.exportStatusText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (dashboardState.exportProgressTotal > 0) {
                                dashboardState.exportProgressCurrent.toFloat() / dashboardState.exportProgressTotal.toFloat()
                            } else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Image Preview Dialog
    val previewAsset = dashboardState.selectedAssetForPreview
    if (previewAsset != null) {
        AssetPreviewDialog(
            asset = previewAsset,
            onDismiss = { viewModel.setSelectedAssetForPreview(null) }
        )
    }

    // Code / Script / Data Viewer Dialog
    val codeAsset = dashboardState.codePreviewAsset
    if (codeAsset != null) {
        CodeViewerDialog(
            asset = codeAsset,
            content = dashboardState.codePreviewContent,
            isLoading = dashboardState.isLoadingCode,
            onDismiss = { viewModel.clearCodePreview() }
        )
    }
}

@Composable
fun StatItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun AssetItemCard(
    asset: ScrapedAsset,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPreview: () -> Unit
) {
    val icon = when (asset.category) {
        "IMAGE" -> Icons.Default.Image
        "AUDIO" -> Icons.Default.AudioFile
        "VIDEO" -> Icons.Default.VideoFile
        "SCRIPT" -> Icons.Default.Code
        else -> Icons.Default.Description
    }

    val iconTint = when (asset.category) {
        "IMAGE" -> MaterialTheme.colorScheme.tertiary
        "AUDIO" -> MaterialTheme.colorScheme.secondary
        "VIDEO" -> MaterialTheme.colorScheme.error
        "SCRIPT" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .testTag("asset_item_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() }
            )

            Spacer(modifier = Modifier.width(4.dp))

            if (asset.category == "IMAGE") {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = asset.url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = iconTint.copy(alpha = 0.15f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = icon, contentDescription = null, tint = iconTint)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = asset.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = asset.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(text = asset.category)
                    Badge(text = "${asset.sizeBytes / 1024} KB")
                }
            }

            IconButton(onClick = onPreview) {
                Icon(
                    imageVector = if (asset.category == "IMAGE") Icons.Default.Visibility else Icons.Default.Code,
                    contentDescription = "Inspect",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
