package dev.phonelinux.proto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

class SpikeViewModel : ViewModel() {
    var state by mutableStateOf<InstallState>(InstallState.NotInstalled)
    var log by mutableStateOf("")
    var busy by mutableStateOf(false)
    var installedOnce by mutableStateOf(false)

    fun install(ctx: android.content.Context, d: Distro) {
        if (busy) return
        busy = true
        val installer = DistroInstaller(ctx.applicationContext)
        viewModelScope.launch {
            val ok = installer.install(d) { state = it }
            busy = false
            installedOnce = ok
            log += if (ok) "[ok] ${d.displayName} installed\n" else "[fail] install failed\n"
        }
    }

    fun runSpike(ctx: android.content.Context, d: Distro) {
        if (busy) return
        busy = true
        val appCtx = ctx.applicationContext
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            log += "$ proot --version\n"
            val v = Proot.selfTest(appCtx)
            log += "exit=${v.exitCode} (${v.wallMs}ms)\n${v.stdout}${v.stderr}\n"
            log += "$ guest: uname -a && cat /etc/alpine-release\n"
            val g = Proot.runGuest(appCtx, DistroInstaller(appCtx).rootfsDir(d),
                listOf("/bin/sh", "-c", "uname -a; echo ---; cat /etc/alpine-release; echo ---; echo guest-exec-OK"))
            log += "exit=${g.exitCode} (${g.wallMs}ms)\n${g.stdout}${g.stderr}\n"
            android.util.Log.i("PHONELINUX_SPIKE", "selftest exit=${v.exitCode}; guest exit=${g.exitCode}; guestOut=${g.stdout.replace("\n","|")}")
            busy = false
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val distro = Distros.ALPINE
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, secondary = Accent2, background = BgTop, surface = CardBg)) {
                val vm: SpikeViewModel = viewModel()
                LaunchedEffect(Unit) {
                    if (DistroInstaller(applicationContext).isInstalled(distro)) {
                        vm.state = InstallState.Installed; vm.installedOnce = true
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                        Text("Phone Linux", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("prototype - spike build", color = TextDim, fontSize = 13.sp)
                        Spacer(Modifier.height(24.dp))
                        DistroCard(distro, vm) {
                            when (vm.state) {
                                InstallState.Installed -> vm.runSpike(this@MainActivity, distro)
                                else -> vm.install(this@MainActivity, distro)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Output", color = TextDim, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0E1A)).padding(12.dp)) {
                            Text(vm.log, color = Accent2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DistroCard(d: Distro, vm: SpikeViewModel, onClick: () -> Unit) {
    val s = vm.state
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
            onClick = onClick, enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = border)
        ) {
            Text(
                when (s) {
                    InstallState.Installed -> "Run guest test"
                    InstallState.NotInstalled, is InstallState.Failed -> "Install  (${d.sizeBytes / 1024 / 1024} MB)"
                    else -> "Working…"
                }
            )
        }
    }
}
