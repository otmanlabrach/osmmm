package com.otmanlabrach.osmsmartcoin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var currentState by mutableStateOf("لم يتم تفعيل خدمة الوصول بعد")
    private var isServiceEnabled by mutableStateOf(false)
    private var isRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("OSM Smart Coin AI", style = MaterialTheme.typography.headlineMedium)
                        Text("نسخة الضغط التلقائي على الإعلانات")

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("الحالة الحالية", style = MaterialTheme.typography.titleMedium)
                                Text(currentState)
                                Text(
                                    if (isServiceEnabled) "خدمة الوصول: مفعّلة ✓"
                                    else "خدمة الوصول: غير مفعّلة ✗",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        if (!isServiceEnabled) {
                            Button(
                                onClick = { openAccessibilitySettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("تفعيل خدمة الوصول (مطلوب)")
                            }
                            Text(
                                "لازم تروح لإعدادات إمكانية الوصول وتفعّل \"OSM Smart Coin AI\" يدوياً.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Button(
                                onClick = { startAutomation() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isRunning
                            ) {
                                Text("▶ بدء التشغيل التلقائي")
                            }

                            OutlinedButton(
                                onClick = { stopAutomation() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isRunning
                            ) {
                                Text("⏹ إيقاف")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("طريقة الاستخدام:", style = MaterialTheme.typography.titleSmall)
                                Text("1. فعّل خدمة الوصول من الزر فوق")
                                Text("2. افتح لعبة OSM")
                                Text("3. ارجع للتطبيق واضغط \"بدء التشغيل التلقائي\"")
                                Text("4. اترك الهاتف والشاشة مفتوحة")
                                Text("5. التطبيق سيحاول يضغط على الإعلانات تلقائياً")
                            }
                        }

                        Text(
                            "ملاحظة: الدقة تعتمد على شكل الإعلان. جرب فترات قصيرة أولاً.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        isServiceEnabled = isAccessibilityServiceEnabled()
        val prefs = getSharedPreferences("osm_smart_coin_prefs", Context.MODE_PRIVATE)
        currentState = prefs.getString("last_state", "في انتظار التفعيل") ?: "في انتظار التفعيل"
        isRunning = prefs.getBoolean("monitoring_active", false)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = packageName + "/" + AdClickService::class.java.canonicalName
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startAutomation() {
        AdClickService.instance?.startAutomation()
            ?: run {
                currentState = "الخدمة غير متصلة. تأكد أنك فعّلتها من الإعدادات."
            }
        refreshState()
    }

    private fun stopAutomation() {
        AdClickService.instance?.stopAutomation()
        refreshState()
    }
}
