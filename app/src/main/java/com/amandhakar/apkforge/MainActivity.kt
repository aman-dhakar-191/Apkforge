package com.amandhakar.apkforge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.amandhakar.apkforge.ui.*

class MainActivity : ComponentActivity() {
    private val vm: ForgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val state by vm.state.collectAsState()
        var showSettings by remember { mutableStateOf(false) }
        var showInstalled by remember { mutableStateOf(false) }

        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importApk(uri)
        }
        val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
            val id = vm.state.value.details?.project?.id
            if (uri != null && id != null) vm.exportOriginal(id, uri)
        }

        var pendingImage by remember { mutableStateOf<com.amandhakar.apkforge.core.ResourceEntry?>(null) }
        val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val entry = pendingImage; pendingImage = null
            if (uri != null && entry != null) vm.stageImage(entry, uri)
        }
        val saveForged = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
            if (uri != null) vm.exportForged(uri)
        }
        val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) vm.saveLog(uri)
        }

        BackHandler(enabled = showSettings || showInstalled || state.details != null) {
            if (showSettings) showSettings = false else if (state.details != null) vm.close() else showInstalled = false
        }

        Scaffold(
            snackbarHost = {
                state.message?.let {
                    Snackbar(Modifier.padding(16.dp), action = { TextButton({ vm.message(null) }) { Text("OK") } }) { Text(it) }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    showSettings -> SettingsScreen(
                        state = state,
                        onToggle = vm::setDebugLog,
                        onRefresh = vm::refreshLog,
                        onClear = vm::clearLog,
                        onShare = { shareLog() },
                        onSave = { saveLog.launch("apkforge-debug-log.txt") },
                        onCreateKey = vm::createKey,
                        onDeleteKey = vm::deleteKey,
                        onBack = { showSettings = false },
                    )
                    state.details != null -> ProjectScreen(
                        details = state.details!!,
                        build = state.build,
                        blockedReason = vm.buildBlockedReason(state.details!!, true),
                        onBack = vm::close,
                        onExport = { saveAs.launch(exportName(state.details!!)) },
                        onDelete = { vm.delete(state.details!!.project.id) },
                        onCopyAsNew = vm::setCopyAsNew,
                        onSuffix = vm::setSuffix,
                        onLabel = vm::setLabel,
                        onPickImage = { pendingImage = it; pickPicture.launch(arrayOf("image/*")) },
                        onUnstage = vm::unstage,
                        onBuild = vm::buildCopy,
                        onExportForged = { saveForged.launch((state.details!!.metadata?.packageName ?: "app") + "-modified.apk") },
                        onInstall = { installModified() },
                    )
                    showInstalled -> InstalledAppsScreen(
                        apps = state.installed,
                        problem = state.accessProblem,
                        onPick = { vm.importInstalled(it) },
                        onPickFile = { vm.dismissAccessProblem(); pick.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        onDismissProblem = vm::dismissAccessProblem,
                        onBack = { showInstalled = false },
                    )
                    else -> HomeScreen(
                        projects = state.projects,
                        onImport = { pick.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        onOpen = vm::open,
                        onDelete = vm::delete,
                        onInstalled = { vm.loadInstalled(); showInstalled = true },
                        onSettings = { vm.refreshLog(); showSettings = true },
                    )
                }
                state.busy?.let { BusyOverlay(it) }
            }
        }
    }

    private fun exportName(d: ProjectDetails): String =
        (d.metadata?.packageName ?: "app") + "-original.apk"

    private fun installModified() {
        if (!com.amandhakar.apkforge.build.ForgeInstaller.canInstall(this)) {
            vm.message("Allow Apkforge to install apps on the next screen, then come back and tap Install again.")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
        } else {
            vm.installForged()
        }
    }

    private fun shareLog() {
        val file = vm.logForSharing()
        if (file == null) { vm.message("The log is empty."); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share debug log"))
    }
}
