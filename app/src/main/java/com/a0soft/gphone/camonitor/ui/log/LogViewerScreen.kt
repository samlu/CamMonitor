package com.a0soft.gphone.camonitor.ui.log

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.LogLevel
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.theme.StatusError
import com.a0soft.gphone.camonitor.ui.theme.StatusWarning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by AppLogger.logsFlow.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }
    val listState = rememberLazyListState()
    // Mirrors AppLogger.minLevel so the icon re-renders on toggle; the logger
    // itself is a plain object and cannot notify Compose.
    var logLevel by remember { mutableStateOf(AppLogger.minLevel) }
    val verbose = logLevel == LogLevel.DEBUG

    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            "INFO" -> logs.filter { it.level == LogLevel.INFO }
            "WARN" -> logs.filter { it.level == LogLevel.WARN }
            "ERROR" -> logs.filter { it.level == LogLevel.ERROR }
            "WebRTC" -> logs.filter { it.tag.contains("WebRtc", ignoreCase = true) }
            "MQTT" -> logs.filter { it.tag.contains("Mqtt", ignoreCase = true) || it.tag.contains("Signaling", ignoreCase = true) }
            else -> logs
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_logs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val file = AppLogger.exportBundle()
                            if (file == null || !file.exists()) {
                                AppLogger.w("LogViewer", "No log files to export")
                                return@launch
                            }
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    // A bundle of several files is a zip now, so the
                                    // MIME type has to match or receivers refuse it.
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.log_share_title)))
                            } catch (e: Exception) {
                                AppLogger.e("LogViewer", "Failed to share log bundle", e)
                            }
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.log_share_description))
                    }
                    // Verbosity switch: the streaming camera writes enough lines to
                    // matter for heat and storage. INFO is the quiet default users
                    // can drop to; DEBUG (the startup value) keeps everything.
                    IconButton(onClick = {
                        val next = if (verbose) LogLevel.INFO else LogLevel.DEBUG
                        AppLogger.minLevel = next
                        logLevel = next
                    }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(
                                if (verbose) R.string.log_level_verbose else R.string.log_level_normal
                            ),
                            tint = if (verbose) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { AppLogger.clearLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.log_clear_description))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf("ALL", "INFO", "WARN", "ERROR", "WebRTC", "MQTT")
                filters.forEach { f ->
                    FilterChip(
                        selected = selectedFilter == f,
                        onClick = { selectedFilter = f },
                        label = {
                            Text(if (f == "ALL") stringResource(R.string.log_filter_all) else f)
                        }
                    )
                }
            }

            // Log Lines LazyColumn
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredLogs) { entry ->
                    val color = when (entry.level) {
                        LogLevel.ERROR -> StatusError
                        LogLevel.WARN -> StatusWarning
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        LogLevel.DEBUG -> Color.Gray
                    }
                    Text(
                        text = entry.format(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = color,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}
