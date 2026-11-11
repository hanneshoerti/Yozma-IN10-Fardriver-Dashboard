package com.fardriver.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fardriver.dash.ble.BleDeviceInfo

@Composable
fun ScanScreen(
    isScanning: Boolean,
    devices: List<BleDeviceInfo>,
    autoConnectHint: String? = null,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (BleDeviceInfo) -> Unit
) {
    Surface(color = Color(0xFF0B0E14), modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            if (autoConnectHint != null) {
                Text(autoConnectHint, color = Color(0xFFFF8A50), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (isScanning) onStopScan() else onStartScan() }) {
                    Text(if (isScanning) "Scan stoppen" else "Scan starten")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Suche nach deinem Fardriver-Dongle (z.B. Name wie \"SM48V70A_81_CZH;30\")",
                color = Color(0xFF8B93A1)
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices.sortedByDescending { it.rssi }) { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF171B24), RoundedCornerShape(14.dp))
                            .clickable { onDeviceSelected(device) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(device.name ?: "(unbekannt)", color = Color.White)
                            Text(device.address, color = Color(0xFF8B93A1), fontSize = 12.sp)
                        }
                        Text("RSSI ${device.rssi}", color = Color(0xFF8B93A1), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
