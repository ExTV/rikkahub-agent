package me.rerere.rikkahub.ui.pages.setting.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowReloadHorizontal
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.QrCode01
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Upload01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ninerouter.NineRouterService
import me.rerere.rikkahub.data.ninerouter.NineRouterStatus
import me.rerere.rikkahub.data.ninerouter.NineRouterUpdateInfo
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject

@Composable
fun NineRouterStationCard(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val nineRouterService: NineRouterService = koinInject()

    var status by remember { mutableStateOf(NineRouterStatus.STOPPED) }
    var isCheckingStatus by remember { mutableStateOf(true) }
    var isWaking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<NineRouterUpdateInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }

    val localEndpoint = remember { nineRouterService.getLocalEndpoint() }
    val lanEndpoint = remember { nineRouterService.getLanEndpoint() }

    LaunchedEffect(Unit) {
        isCheckingStatus = true
        status = nineRouterService.checkStatus()
        isCheckingStatus = false
        updateInfo = nineRouterService.checkUpdate()
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Name & Live Status Dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "9Router Command Station",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Local AI Gateway — Port 20128",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val dotColor = when (status) {
                        NineRouterStatus.ONLINE -> Color(0xFF4CAF50)
                        NineRouterStatus.STOPPED -> Color(0xFFFF9800)
                        NineRouterStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.error
                    }
                    val statusText = when (status) {
                        NineRouterStatus.ONLINE -> "ONLINE"
                        NineRouterStatus.STOPPED -> "STOPPED"
                        NineRouterStatus.NOT_INSTALLED -> "NOT INSTALLED"
                    }

                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = dotColor)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = dotColor
                    )

                    IconButton(
                        onClick = {
                            scope.launch {
                                isCheckingStatus = true
                                status = nineRouterService.checkStatus()
                                isCheckingStatus = false
                                updateInfo = nineRouterService.checkUpdate()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isCheckingStatus) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(HugeIcons.ArrowReloadHorizontal, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Quick Actions: Wake or Open Dashboard
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status == NineRouterStatus.STOPPED) {
                    Button(
                        onClick = {
                            scope.launch {
                                isWaking = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val ok = nineRouterService.wake()
                                isWaking = false
                                status = nineRouterService.checkStatus()
                                if (ok) {
                                    toaster.show("9Router is online!", type = ToastType.Success)
                                } else {
                                    toaster.show("Failed to wake 9Router", type = ToastType.Error)
                                }
                            }
                        },
                        enabled = !isWaking,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isWaking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Waking up...")
                        } else {
                            Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Wake 9Router")
                        }
                    }
                } else if (status == NineRouterStatus.ONLINE) {
                    Button(
                        onClick = {
                            context.openUrl("http://127.0.0.1:20128/dashboard")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(HugeIcons.DashboardSquare01, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Web Dashboard")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                nineRouterService.stop()
                                status = nineRouterService.checkStatus()
                                toaster.show("9Router stopped", type = ToastType.Info)
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(HugeIcons.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }

            // Endpoints Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Localhost Endpoint",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = localEndpoint,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(onClick = {
                            context.writeClipboardText(localEndpoint)
                            toaster.show("Local endpoint copied", type = ToastType.Success)
                        }) {
                            Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LAN Endpoint (Wi-Fi Sharing)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = lanEndpoint,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(onClick = {
                            context.writeClipboardText(lanEndpoint)
                            toaster.show("LAN endpoint copied", type = ToastType.Success)
                        }) {
                            Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Update Available Notifier
            updateInfo?.takeIf { it.hasUpdate }?.let { update ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
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
                                text = "Update Available: v${update.latestVersion}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Current version: v${update.currentVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isUpdating = true
                                    val ok = nineRouterService.update()
                                    isUpdating = false
                                    if (ok) {
                                        toaster.show("Updating 9Router in background...", type = ToastType.Success)
                                    } else {
                                        toaster.show("Failed to start update", type = ToastType.Error)
                                    }
                                }
                            },
                            enabled = !isUpdating,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isUpdating) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(HugeIcons.Upload01, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Update")
                            }
                        }
                    }
                }
            }
        }
    }
}
