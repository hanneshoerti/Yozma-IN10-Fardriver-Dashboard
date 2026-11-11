package com.fardriver.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fardriver.dash.ble.ConnectionState
import com.fardriver.dash.ble.FardriverProtocol

private val BgDark = Color(0xFF0B0E14)
private val CardDark = Color(0xFF171B24)
private val Cyan = Color(0xFF35E0F0)
private val Green = Color(0xFF35E08A)
private val TextGray = Color(0xFF8B93A1)

@Composable
fun DashboardScreen(
    connectionState: ConnectionState,
    state: FardriverProtocol.VehicleState,
    rawDebugLine: String,
    rawChunkLine: String = "",
    rawChunkHistory: List<String> = emptyList(),
    discoveredServices: List<String> = emptyList(),
    gattEvents: List<String> = emptyList(),
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    Surface(color = BgDark, modifier = Modifier.fillMaxSize()) {
        val clipboard = LocalClipboardManager.current
        var showDebug by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top bar: Connection status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionBadge(connectionState, onScanClick)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen", tint = TextGray)
                    }
                    Text("STD", color = Green, fontWeight = FontWeight.Bold)
                }
            }

            // Speed hero card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.speedKmh?.let { "%.0f".format(it) } ?: "--",
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = Cyan
                    )
                    Text("KM/H", color = TextGray, letterSpacing = 2.sp)
                    Spacer(Modifier.height(20.dp))
                    PowerBar(powerKw = state.powerKw ?: 0.0, maxKw = 2.6)
                }
            }

            // Metric grid
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    Icons.Filled.BatteryChargingFull, Green, "BATTERY",
                    state.batteryPercent?.let { "$it" } ?: "--", "%", Modifier.weight(1f)
                )
                MetricTile(
                    Icons.Filled.Bolt, Cyan, "VOLTAGE",
                    state.voltage?.let { "%.1f".format(it) } ?: "--", "V", Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    Icons.Filled.ElectricBolt, Color(0xFFFF8A50), "CURRENT",
                    state.currentA?.let { "%.1f".format(it) } ?: "--", "A", Modifier.weight(1f)
                )
                MetricTile(
                    Icons.Filled.TrendingUp, Cyan, "POWER",
                    state.powerKw?.let { "%.2f".format(it) } ?: "--", "kW", Modifier.weight(1f)
                )
            }

            SectionLabel("TEMPERATURES")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    Icons.Filled.Thermostat, Color(0xFF4D8BFF), "MOTOR",
                    state.motorTempC?.let { "$it" } ?: "--", "°C", Modifier.weight(1f)
                )
                MetricTile(
                    Icons.Filled.Memory, Color(0xFF4D8BFF), "CONTROLLER",
                    state.controllerTempC?.let { "$it" } ?: "--", "°C", Modifier.weight(1f)
                )
            }

            // Dezenter Schalter ganz unten - Debug-Infos standardmaessig ausgeblendet.
            TextButton(onClick = { showDebug = !showDebug }) {
                Text(
                    if (showDebug) "Debug-Infos ausblenden" else "Debug-Infos anzeigen",
                    color = TextGray,
                    fontSize = 11.sp
                )
            }

            if (showDebug) {
                SectionLabel("STATUS (Debug)")
                Button(onClick = {
                    val text = buildString {
                        appendLine("=== Rohdaten-Historie ===")
                        rawChunkHistory.forEach { appendLine(it) }
                        appendLine()
                        appendLine("=== GATT-Events ===")
                        gattEvents.forEach { appendLine(it) }
                        appendLine()
                        appendLine("=== Services/Characteristics ===")
                        discoveredServices.forEach { appendLine(it) }
                    }
                    clipboard.setText(AnnotatedString(text))
                }) {
                    Text("Debug-Log kopieren")
                }
                Text(
                    text = rawDebugLine.ifBlank { "Warte auf erkannte Fardriver-Pakete ..." },
                    color = TextGray,
                    fontSize = 12.sp
                )
                Text(
                    text = "Letzter Rohempfang: " + rawChunkLine.ifBlank { "(noch nichts empfangen)" },
                    color = TextGray,
                    fontSize = 11.sp
                )
                if (rawChunkHistory.isNotEmpty()) {
                    SectionLabel("ROHDATEN-HISTORIE (neueste zuletzt)")
                    Column {
                        rawChunkHistory.forEach {
                            Text(it, color = TextGray, fontSize = 11.sp)
                        }
                    }
                }
                if (discoveredServices.isNotEmpty()) {
                    SectionLabel("GEFUNDENE SERVICES/CHARACTERISTICS")
                    Column {
                        discoveredServices.forEach {
                            Text(it, color = TextGray, fontSize = 11.sp)
                        }
                    }
                }
                if (gattEvents.isNotEmpty()) {
                    SectionLabel("GATT-EVENTS")
                    Column {
                        gattEvents.forEach {
                            Text(it, color = TextGray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionState, onScanClick: () -> Unit) {
    val (label, color) = when (state) {
        is ConnectionState.Connected -> "CONNECTED" to Green
        is ConnectionState.Connecting -> "CONNECTING..." to Color(0xFFF0C419)
        is ConnectionState.DiscoveringServices -> "SERVICES..." to Color(0xFFF0C419)
        is ConnectionState.Error -> "FEHLER" to Color(0xFFFF5555)
        ConnectionState.Disconnected -> "DISCONNECTED" to TextGray
    }
    AssistChip(
        onClick = onScanClick,
        label = { Text(label, color = color, fontSize = 12.sp) },
        colors = AssistChipDefaults.assistChipColors(containerColor = CardDark)
    )
}

@Composable
private fun PowerBar(powerKw: Double, maxKw: Double) {
    val fraction = (powerKw / maxKw).coerceIn(0.0, 1.0).toFloat()
    Column(modifier = Modifier.fillMaxWidth(0.85f)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color(0xFF2A2F3A), RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(Cyan, RoundedCornerShape(3.dp))
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0W", color = TextGray, fontSize = 11.sp)
            Text("POWER", color = TextGray, fontSize = 11.sp)
            Text("${"%.1f".format(maxKw)}kW", color = TextGray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(iconColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(label, color = TextGray, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(2.dp))
                    Text(unit, color = TextGray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}
