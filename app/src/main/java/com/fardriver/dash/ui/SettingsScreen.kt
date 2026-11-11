package com.fardriver.dash.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgDark = Color(0xFF0B0E14)
private val CardDark = Color(0xFF171B24)
private val Cyan = Color(0xFF35E0F0)
private val TextGray = Color(0xFF8B93A1)

@Composable
fun SettingsScreen(
    initialMotorPolePairs: Int,
    initialWheelCircumferenceM: Double,
    onBack: () -> Unit,
    onSave: (motorPolePairs: Int, wheelCircumferenceM: Double) -> Unit
) {
    var polePairsText by remember { mutableStateOf(initialMotorPolePairs.toString()) }
    var circText by remember { mutableStateOf(initialWheelCircumferenceM.toString()) }

    val polePairs = polePairsText.toIntOrNull()
    val circumference = circText.toDoubleOrNull()

    Surface(color = BgDark, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Geschwindigkeits-Kalibrierung", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Falls die angezeigte km/h-Geschwindigkeit nicht mit der echten übereinstimmt, " +
                    "hier anpassen. Radumfang und angezeigte Geschwindigkeit sind proportional: " +
                    "willst du z.B. die Anzeige um 10% senken, den Radumfang um 10% verringern.",
                color = TextGray,
                fontSize = 13.sp
            )

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Motor-Polpaare", color = TextGray, fontSize = 12.sp)
                    OutlinedTextField(
                        value = polePairsText,
                        onValueChange = { polePairsText = it },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Radumfang (Meter)", color = TextGray, fontSize = 12.sp)
                    OutlinedTextField(
                        value = circText,
                        onValueChange = { circText = it },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (polePairs != null && polePairs > 0 && circumference != null && circumference > 0) {
                val oldFactor = initialWheelCircumferenceM / initialMotorPolePairs
                val newFactor = circumference / polePairs
                val changePercent = ((newFactor / oldFactor) - 1.0) * 100
                Text(
                    "Änderung ggü. aktuellem Wert: ${"%+.1f".format(changePercent)}% auf die Geschwindigkeitsanzeige",
                    color = Cyan,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Abbrechen")
                }
                Button(
                    onClick = {
                        if (polePairs != null && polePairs > 0 && circumference != null && circumference > 0) {
                            onSave(polePairs, circumference)
                        }
                    },
                    enabled = polePairs != null && polePairs > 0 && circumference != null && circumference > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Speichern")
                }
            }
        }
    }
}
