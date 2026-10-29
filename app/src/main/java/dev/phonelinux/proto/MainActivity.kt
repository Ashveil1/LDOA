package dev.phonelinux.proto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private val BgTop = Color(0xFF0B1020)
private val BgBottom = Color(0xFF131B33)
private val Accent = Color(0xFF6C8CFF)
private val Accent2 = Color(0xFF41D9A3)
private val CardBg = Color(0xFF1A2340)
private val TextDim = Color(0xFF9AA6C8)

enum class Screen { PICKER, TERMINAL }

// gnome/evidence branch: GNOME render + interaction qualification in
// flight (wallpaper/topbar/dock pixel-proven run 40661199161). Merge to
// main only after interaction + cold install-to-desktop + restart tests
// pass at this exact head.
private const val DESKTOP_PREVIEW_ENABLED = true

class SpikeViewModel : ViewModel() {
    val states: SnapshotStateMap<String, InstallState> = mutableStateMapOf()
    var log by mutableStateOf("")
    var busy by mutableStateOf(false)
    var screen by mutableStateOf(Screen.PICKER)

    fun stateFor(d: Distro): InstallState = states[d.id] ?: InstallState.NotInstalled

    var terminal by mutableStateOf<TerminalSession?>(null)
    var termText by mutableStateOf("")
    var termRunning by mutableStateOf(false)
    /** Non-null while the first-start large-download warning is shown. */
    var pendingDesktop by mutableStateOf<Distro?>(null)
    /** True while XServer.openDesktop is provisioning/launching. */
    var desktopPrep by mutableStateOf(false)
    /** Set by the activity: opens the desktop for a distro (XServer). */
    var autoDesktop: ((Distro) -> Unit)? = null
    var coldAutoEntered = false

    fun install(ctx: android.content.Context, d: Distro) {
        if (busy) return
        busy = true
        val installer = DistroInstaller(ctx.applicationContext)
        viewModelScope.launch {
            val ok = installer.install(d) { states[d.id] = it }
            busy = false
            log += if (ok) "[ok] ${d.displayName} installed\n" else "[fail] ${d.displayName} install failed\n"
            // User spec: tap -> install -> AUTO-ENTER the desktop, no extra
            // taps. Ubuntu only; the Alpine lane keeps its manual flow.
            if (ok && d.pkgMgr == PkgMgr.APT && DESKTOP_PREVIEW_ENABLED) {
                log += "[..] starting ${d.displayName} desktop (GNOME provisioning can take a while)\n"
                autoDesktop?.invoke(d)
            }
        }
    }

    fun openTerminal(ctx: android.content.Context, d: Distro) {
        if (terminal == null) {
            val appCtx = ctx.applicationContext
            val session = TerminalSession(appCtx, DistroInstaller(appCtx).rootfsDir(d)) {
                termText = terminal?.snapshot() ?: ""
            }
            terminal = session
            termRunning = session.start()
            termText = session.snapshot()
        }
        screen = Screen.TERMINAL
    }

    fun sendCommand(line: String) {
        if (line.isBlank()) return
        terminal?.send(line)
    }

    fun closeTerminal() {
        terminal?.close(); terminal = null
        termRunning = false
        screen = Screen.PICKER
    }

    override fun onCleared() { terminal?.close() }
}

class MainActivity : ComponentActivity() {
    private val vm: SpikeViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // Returning from the X view (or first resume): the picker is
        // foreground, so any preparing overlay is stale.
        vm.desktopPrep = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The X server runs as a foreground service; ask for notifications
        // once, up front, so the dialog never covers the desktop surface.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Phone-native default: tap-to-point (direct touch), not termux-x11's
        // trackpad default. Only when never set; the lorie preferences
        // screen remains free to change it.
        val touchPrefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
        if (!touchPrefs.contains("touchMode")) {
            touchPrefs.edit().putString("touchMode", "3").apply()
        }
        val distros = Distros.ALL
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, secondary = Accent2, background = BgTop, surface = CardBg)) {
                // First start of the Ubuntu desktop downloads the GNOME
                // package set (large). Ask BEFORE that download starts;
                // everything else goes straight through.
                val requestDesktop: (Distro) -> Unit = { d ->
                    val needsProvision = d.pkgMgr == PkgMgr.APT &&
                        !GuestUbuntu.hasGnome(DistroInstaller(applicationContext).rootfsDir(d))
                    if (needsProvision) {
                        vm.pendingDesktop = d
                    } else {
                        vm.desktopPrep = true
                        XServer.openDesktop(this@MainActivity, d)
                    }
                }
                // Post-install auto-enter goes through the same first-start
                // download consent as the manual desktop button.
                vm.autoDesktop = { d -> requestDesktop(d) }
                LaunchedEffect(Unit) {
                    for (d in distros) {
                        if (DistroInstaller(applicationContext).isInstalled(d)) {
                            vm.states[d.id] = InstallState.Installed
                        }
                    }
                    // Cold-start re-entry: the user left with a live Ubuntu
                    // desktop - reopen it without making them tap again.
                    val prefs = getSharedPreferences("plinux_desktop", MODE_PRIVATE)
                    if (!vm.coldAutoEntered && DESKTOP_PREVIEW_ENABLED &&
                        prefs.getBoolean("ubuntu_desktop_active", false) &&
                        vm.stateFor(Distros.UBUNTU) == InstallState.Installed) {
                        vm.coldAutoEntered = true
                        // GNOME is already provisioned on this path (the
                        // marker is only set once it is), so no download
                        // consent is needed here.
                        vm.desktopPrep = true
                        XServer.openDesktop(this@MainActivity, Distros.UBUNTU)
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
                    vm.pendingDesktop?.let { pd ->
                        AlertDialog(
                            onDismissRequest = { vm.pendingDesktop = null },
                            confirmButton = { TextButton(onClick = {
                                vm.pendingDesktop = null
                                vm.desktopPrep = true
                                XServer.openDesktop(this@MainActivity, pd)
                            }) { Text("Download and start") } },
                            dismissButton = { TextButton(onClick = { vm.pendingDesktop = null }) { Text("Not now") } },
                            title = { Text("Ubuntu desktop - first start") },
                            text = { Text("The first start downloads the desktop packages - a large download (several hundred MB; the exact size is not measured). Use Wi-Fi, not mobile data.\n\nThis can take several minutes. Keep the app open.") })
                    }
                    if (vm.desktopPrep) {
                        Column(Modifier.fillMaxSize().padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Accent)
                            Spacer(Modifier.height(20.dp))
                            Text("Starting Ubuntu desktop", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text("First start downloads a large package set and can take several minutes. Keep the app open; your files stay on the device.",
                                color = TextDim, fontSize = 14.sp)
                        }
                    } else when (vm.screen) {
                        Screen.PICKER -> PickerScreen(distros, vm,
                            onPrimary = { d ->
                                when (vm.stateFor(d)) {
                                    InstallState.Installed -> vm.openTerminal(this@MainActivity, d)
                                    else -> vm.install(this@MainActivity, d)
                                }
                            },
                            onDesktop = { d -> requestDesktop(d) })
                        Screen.TERMINAL -> TerminalScreen(vm, onBack = { vm.closeTerminal() })
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerScreen(distros: List<Distro>, vm: SpikeViewModel, onPrimary: (Distro) -> Unit, onDesktop: (Distro) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Text("Phone Linux", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("prototype - terminal build", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        for (d in distros) {
            DistroCard(d, vm.stateFor(d), vm.busy, onPrimary = { onPrimary(d) })
            // Ubuntu's desktop button stays hidden until the GNOME session is
            // runtime-qualified: offering it now would start a multi-hundred-MB
            // unproven apt install on the user's phone. Ubuntu is still
            // installable with a working terminal (real userland + apt).
            if (vm.stateFor(d) == InstallState.Installed &&
                (d.pkgMgr == PkgMgr.APK || DESKTOP_PREVIEW_ENABLED)) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onDesktop(d) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "open-desktop-" + d.id }
                ) { Text("Open " + d.displayName + " desktop (experimental)", color = Accent) }
            }
            Spacer(Modifier.height(12.dp))
        }
        GestureToggles()
        Spacer(Modifier.height(8.dp))
        Text("Output", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0E1A)).padding(12.dp)) {
            Text(vm.log, color = Accent2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun GestureToggles() {
    // Finger interaction map (native-first, user-delegated UX pick
    // 2026-10-29): tap = click; one-finger drag stays native (scroll,
    // window move, selection); one-finger hold = native press-and-hold
    // where the toolkit supports it (GTK context menu at the timeout, text
    // selection preserved); two-finger tap = right-click everywhere via
    // lorie injection; two-finger drag = wheel scroll for legacy clients.
    // Each injected gesture can be switched off here; the lorie input layer
    // reads these keys per gesture (TouchInputHandler patch).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(ctx.packageName + "_preferences", android.content.Context.MODE_PRIVATE) }
    var twoFingerTap by remember { mutableStateOf(prefs.getBoolean("ldoa_twofinger_tap_rightclick", true)) }
    var twoFinger by remember { mutableStateOf(prefs.getBoolean("ldoa_twofinger_scroll", true)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Touch gestures", color = TextDim, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Two-finger tap = right-click", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = twoFingerTap, onCheckedChange = {
                twoFingerTap = it; prefs.edit().putBoolean("ldoa_twofinger_tap_rightclick", it).apply()
            }, modifier = Modifier.semantics { contentDescription = "toggle-twofinger-tap-rightclick" })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Two-finger scroll", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = twoFinger, onCheckedChange = {
                twoFinger = it; prefs.edit().putBoolean("ldoa_twofinger_scroll", it).apply()
            }, modifier = Modifier.semantics { contentDescription = "toggle-twofinger-scroll" })
        }
    }
}

@Composable
private fun DistroCard(d: Distro, s: InstallState, busy: Boolean, onPrimary: () -> Unit) {
    val border by animateColorAsState(if (s == InstallState.Installed) Accent2 else Accent, label = "b")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(d.description, color = TextDim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        when (s) {
            is InstallState.Downloading -> {
                val frac = if (s.totalBytes > 0) s.doneBytes.toFloat() / s.totalBytes else 0f
                LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Downloading  ${s.doneBytes / 1024} / ${s.totalBytes / 1024} KB", color = TextDim, fontSize = 12.sp)
            }
            InstallState.Verifying -> Text("Verifying checksum…", color = TextDim, fontSize = 13.sp)
            InstallState.Extracting -> Text("Extracting…", color = TextDim, fontSize = 13.sp)
            is InstallState.Failed -> Text("Failed: ${s.message}", color = Color(0xFFFF7A7A), fontSize = 13.sp)
            else -> {}
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onPrimary, enabled = !busy,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = if (s == InstallState.Installed) "open-terminal-" + d.id else "install-" + d.id
            },
            colors = ButtonDefaults.buttonColors(containerColor = border)
        ) {
            Text(
                when (s) {
                    InstallState.Installed -> "Open terminal"
                    InstallState.NotInstalled, is InstallState.Failed -> "Install  (${d.sizeBytes / 1024 / 1024} MB)"
                    else -> "Working…"
                }
            )
        }
    }
}

@Composable
private fun TerminalScreen(vm: SpikeViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(vm.termText) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    // No imePadding: windowSoftInputMode=adjustResize shrinks the window
    // above the IME already; stacking imePadding on top double-applied the
    // inset and (pre-adjustResize) adjustPan pushed the whole column -
    // header and output - off the top of the screen with the keyboard open.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Terminal", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(
                if (vm.termRunning) "live" else "stopped",
                color = if (vm.termRunning) Accent2 else Color(0xFFFF7A7A), fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onBack) { Text("Close", color = Accent) }
        }
        Spacer(Modifier.height(8.dp))
        // Bottom-anchored like a real terminal: short output sits at the
        // bottom, long output scrolls; auto-scroll effect follows new output.
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0A0E1A)).padding(12.dp),
            contentAlignment = Alignment.BottomStart) {
            Text(vm.termText, color = Accent2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll)
                    .semantics { contentDescription = "terminal-output" })
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).semantics { contentDescription = "terminal-input" },
                placeholder = { Text("type a command…", color = TextDim, fontSize = 13.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.sendCommand(input); input = ""
                })
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.sendCommand(input); input = "" },
                enabled = vm.termRunning && input.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "terminal-send" }
            ) { Text("Run") }
        }
    }
}
