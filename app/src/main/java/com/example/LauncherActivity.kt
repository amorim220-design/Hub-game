package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                LauncherMainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var currentOptions by remember { mutableStateOf(OptionsManager.loadOptions(context)) }
    var installedPackages by remember { mutableStateOf(PackageInstaller.listInstalledPackages(context)) }
    var installedDrivers by remember { mutableStateOf(DriverInstaller.listInstalledDrivers(context)) }

    var lastGpuProbeJson by remember {
        val prefs = context.getSharedPreferences(GpuProbeActivity.PREFS_GPU_PROBE, Context.MODE_PRIVATE)
        mutableStateOf(prefs.getString(GpuProbeActivity.KEY_LAST_PROBE_RESULT, null))
    }

    var logcatText by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }

    // Launcher for Game Data Folder selection
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (ignored: Exception) {}

            // Extract displayable folder path
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val path = if (parts.size > 1) {
                if (parts[0].equals("primary", ignoreCase = true)) {
                    "/storage/emulated/0/${parts[1]}"
                } else {
                    "/storage/${parts[0]}/${parts[1]}"
                }
            } else {
                uri.path ?: docId
            }

            val updated = currentOptions.copy(gameDirectory = path)
            currentOptions = updated
            OptionsManager.saveOptions(context, updated)
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Pasta do jogo configurada: $path")
            }
        }
    }

    // Launcher for Runtime Package ZIP import
    val runtimeZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isOperating = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val pkg = PackageInstaller.installPackageFromUri(context, uri)
                    withContext(Dispatchers.Main) {
                        installedPackages = PackageInstaller.listInstalledPackages(context)
                        val updated = currentOptions.copy(activeRuntimeId = pkg.id)
                        currentOptions = updated
                        OptionsManager.saveOptions(context, updated)
                        snackbarHostState.showSnackbar("Pacote instalado: ${pkg.name}")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar("Erro ao instalar pacote: ${e.message}")
                    }
                } finally {
                    withContext(Dispatchers.Main) { isOperating = false }
                }
            }
        }
    }

    // Launcher for Vulkan Driver ZIP import
    val driverZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isOperating = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val driver = DriverInstaller.installDriverFromUri(context, uri)
                    withContext(Dispatchers.Main) {
                        installedDrivers = DriverInstaller.listInstalledDrivers(context)
                        val updated = currentOptions.copy(activeDriverId = driver.id)
                        currentOptions = updated
                        OptionsManager.saveOptions(context, updated)
                        snackbarHostState.showSnackbar("Driver instalado: ${driver.name}")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar("Erro ao instalar driver: ${e.message}")
                    }
                } finally {
                    withContext(Dispatchers.Main) { isOperating = false }
                }
            }
        }
    }

    // Launcher for GPU Probe Activity
    val gpuProbeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val prefs = context.getSharedPreferences(GpuProbeActivity.PREFS_GPU_PROBE, Context.MODE_PRIVATE)
        lastGpuProbeJson = prefs.getString(GpuProbeActivity.KEY_LAST_PROBE_RESULT, null)
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Sonda de GPU finalizada com sucesso!")
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("launcher_scaffold"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VideogameAsset, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("launcher_bottom_nav"),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_game)) },
                    modifier = Modifier.testTag("nav_tab_game")
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_graphics)) },
                    modifier = Modifier.testTag("nav_tab_graphics")
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_drivers)) },
                    modifier = Modifier.testTag("nav_tab_drivers")
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 3,
                    onClick = {
                        selectedTabIndex = 3
                        logcatText = DiagnosticsManager.captureRecentLogcat()
                    },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_diagnostics)) },
                    modifier = Modifier.testTag("nav_tab_diagnostics")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTabIndex) {
                0 -> GameTabContent(
                    options = currentOptions,
                    installedPackages = installedPackages,
                    onBrowseFolder = { folderPickerLauncher.launch(null) },
                    onImportPackage = { runtimeZipLauncher.launch("application/zip") },
                    onSelectPackage = { pkgId ->
                        val updated = currentOptions.copy(activeRuntimeId = pkgId)
                        currentOptions = updated
                        OptionsManager.saveOptions(context, updated)
                    },
                    onDeletePackage = { pkg ->
                        PackageInstaller.deletePackage(pkg)
                        installedPackages = PackageInstaller.listInstalledPackages(context)
                        if (currentOptions.activeRuntimeId == pkg.id) {
                            val updated = currentOptions.copy(activeRuntimeId = "")
                            currentOptions = updated
                            OptionsManager.saveOptions(context, updated)
                        }
                    },
                    onLaunchGame = {
                        // Launch isolated GameActivity
                        val intent = Intent(context, GameActivity::class.java).apply {
                            val active = installedPackages.find { it.id == currentOptions.activeRuntimeId }
                            if (active != null) {
                                putExtra(GameActivity.EXTRA_RUNTIME_PATH, active.mainLibraryFile.absolutePath)
                            }
                            putExtra(GameActivity.EXTRA_GAME_DIR, currentOptions.gameDirectory)
                        }
                        context.startActivity(intent)
                    }
                )

                1 -> GraphicsTabContent(
                    options = currentOptions,
                    onOptionsChanged = { updated ->
                        currentOptions = updated
                        OptionsManager.saveOptions(context, updated)
                    }
                )

                2 -> DriversTabContent(
                    options = currentOptions,
                    installedDrivers = installedDrivers,
                    onImportDriver = { driverZipLauncher.launch("application/zip") },
                    onSelectDriver = { driverId ->
                        val updated = currentOptions.copy(activeDriverId = driverId)
                        currentOptions = updated
                        OptionsManager.saveOptions(context, updated)
                    },
                    onDeleteDriver = { driver ->
                        DriverInstaller.deleteDriver(driver)
                        installedDrivers = DriverInstaller.listInstalledDrivers(context)
                        if (currentOptions.activeDriverId == driver.id) {
                            val updated = currentOptions.copy(activeDriverId = "")
                            currentOptions = updated
                            OptionsManager.saveOptions(context, updated)
                        }
                    }
                )

                3 -> DiagnosticsTabContent(
                    lastGpuProbeJson = lastGpuProbeJson,
                    logcatText = logcatText,
                    onRunProbe = {
                        val intent = Intent(context, GpuProbeActivity::class.java)
                        gpuProbeLauncher.launch(intent)
                    },
                    onRefreshLogs = {
                        logcatText = DiagnosticsManager.captureRecentLogcat()
                    },
                    onExportZip = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val zip = DiagnosticsManager.generateDiagnosticsZip(context, lastGpuProbeJson)
                            withContext(Dispatchers.Main) {
                                snackbarHostState.showSnackbar("Diagnóstico exportado: ${zip.name}")
                            }
                        }
                    }
                )
            }

            if (isOperating) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("loading_indicator"))
                }
            }
        }
    }
}

@Composable
fun GameTabContent(
    options: AppOptions,
    installedPackages: List<RuntimePackage>,
    onBrowseFolder: () -> Unit,
    onImportPackage: () -> Unit,
    onSelectPackage: (String) -> Unit,
    onDeletePackage: (RuntimePackage) -> Unit,
    onLaunchGame: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Game Folder Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("game_folder_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.title_select_game_folder),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.desc_select_game_folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (options.gameDirectory.isNotEmpty())
                            stringResource(R.string.text_folder_selected, options.gameDirectory)
                        else
                            stringResource(R.string.text_no_folder_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (options.gameDirectory.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onBrowseFolder,
                        modifier = Modifier.testTag("browse_folder_button")
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_browse_folder))
                    }
                }
            }
        }

        // Runtime Package Selection Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("runtime_package_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.title_runtime_package),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = onImportPackage,
                            modifier = Modifier.testTag("import_package_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.btn_import_runtime))
                        }
                    }
                    Text(
                        text = stringResource(R.string.desc_runtime_package),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (installedPackages.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.text_no_runtime_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            items(installedPackages) { pkg ->
                val isSelected = options.activeRuntimeId == pkg.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPackage(pkg.id) }
                        .testTag("runtime_item_${pkg.id}"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pkg.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "v${pkg.version} • ${pkg.author} • ${pkg.mainLibraryName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = "Selecionado", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            IconButton(onClick = { onDeletePackage(pkg) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remover", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // Launch Game Big Action Button
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onLaunchGame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("launch_game_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.btn_launch_game),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun GraphicsTabContent(
    options: AppOptions,
    onOptionsChanged: (AppOptions) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Resolution Presets
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.title_resolution),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val resolutions = listOf("720p (HD)", "1080p (FHD)", "1440p (QHD)", "Nativo (Tela)")
                    resolutions.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOptionsChanged(options.copy(resolutionPreset = index)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = options.resolutionPreset == index,
                                onClick = { onOptionsChanged(options.copy(resolutionPreset = index)) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // FPS Limit
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${stringResource(R.string.title_fps_limit)}: ${if (options.fpsLimit == 0) "Ilimitado" else "${options.fpsLimit} FPS"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(30, 60, 90, 120, 0).forEach { limit ->
                            FilterChip(
                                selected = options.fpsLimit == limit,
                                onClick = { onOptionsChanged(options.copy(fpsLimit = limit)) },
                                label = { Text(if (limit == 0) "Max" else "$limit") }
                            )
                        }
                    }
                }
            }
        }

        // Direct3D 9 & Widescreen Tweaks
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ajustes de Motor & DXVK",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Widescreen HUD
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.title_widescreen), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.desc_widescreen), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = options.widescreenHud,
                            onCheckedChange = { onOptionsChanged(options.copy(widescreenHud = it)) }
                        )
                    }

                    // Minimap Fix
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.title_minimap), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.desc_minimap), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = options.minimapFix,
                            onCheckedChange = { onOptionsChanged(options.copy(minimapFix = it)) }
                        )
                    }

                    // Pipeline Cache
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.title_pipeline_cache), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.desc_pipeline_cache), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = options.pipelineCache,
                            onCheckedChange = { onOptionsChanged(options.copy(pipelineCache = it)) }
                        )
                    }

                    // CPU Textures
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.title_cpu_textures), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.desc_cpu_textures), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = options.cpuTextures,
                            onCheckedChange = { onOptionsChanged(options.copy(cpuTextures = it)) }
                        )
                    }

                    // Triple Buffering
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.title_back_buffers), fontWeight = FontWeight.SemiBold)
                        }
                        Switch(
                            checked = options.tripleBuffering,
                            onCheckedChange = { onOptionsChanged(options.copy(tripleBuffering = it)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DriversTabContent(
    options: AppOptions,
    installedDrivers: List<VulkanDriverInfo>,
    onImportDriver: () -> Unit,
    onSelectDriver: (String) -> Unit,
    onDeleteDriver: (VulkanDriverInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.title_vulkan_driver),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onImportDriver) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.btn_import_driver))
                        }
                    }
                    Text(
                        text = "Selecione o driver Vulkan carregado via libvulkan.so hook (ex.: Mesa Turnip para Adreno).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Default System Driver item
        item {
            val isDefault = options.activeDriverId.isEmpty()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectDriver("") },
                colors = CardDefaults.cardColors(
                    containerColor = if (isDefault)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.driver_default),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Driver Vulkan nativo do fabricante do aparelho (libvulkan.so)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isDefault) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        items(installedDrivers) { driver ->
            val isSelected = options.activeDriverId == driver.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectDriver(driver.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = driver.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v${driver.version} • Autor: ${driver.author} • ${driver.driverFileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        IconButton(onClick = { onDeleteDriver(driver) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remover", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsTabContent(
    lastGpuProbeJson: String?,
    logcatText: String,
    onRunProbe: () -> Unit,
    onRefreshLogs: () -> Unit,
    onExportZip: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // GPU Probe Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.title_gpu_probe),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.desc_gpu_probe),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (lastGpuProbeJson != null) {
                        val parsedProbe = remember(lastGpuProbeJson) {
                            try {
                                JSONObject(lastGpuProbeJson)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (parsedProbe != null) {
                            if (parsedProbe.optString("status") == "ok") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Dispositivo: ${parsedProbe.optString("deviceName")}", fontWeight = FontWeight.SemiBold)
                                    Text("Vulkan API: ${parsedProbe.optString("apiVersion")}", style = MaterialTheme.typography.bodySmall)
                                    Text("Driver: ${parsedProbe.optString("driverVersion")} (Vendor 0x${Integer.toHexString(parsedProbe.optInt("vendorId"))})", style = MaterialTheme.typography.bodySmall)
                                    Text("Extensões Suportadas: ${parsedProbe.optInt("extensionCount")}", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                Text("Erro na sonda: ${parsedProbe.optString("message")}", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Text(lastGpuProbeJson, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("Nenhuma sonda de GPU executada ainda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRunProbe) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_run_probe))
                    }
                }
            }
        }

        // Export Diagnostics Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.title_diagnostics_export),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.desc_diagnostics_export),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(onClick = onExportZip) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_export_diagnostics))
                    }
                }
            }
        }

        // Logcat Preview Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Logs Recentes (Hook & Vulkan)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onRefreshLogs) {
                            Icon(Icons.Default.Refresh, contentDescription = "Atualizar Logs")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = if (logcatText.isNotEmpty()) logcatText else "Nenhum log registrado ainda.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
