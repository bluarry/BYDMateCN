package com.bydmatecn.app.local

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmatecn.app.MainActivity

private data class LocalNavItem(val route: String, val title: String, val icon: ImageVector)

@Composable
internal fun LocalMainNavigation(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    val items = listOf(
        LocalNavItem("dashboard", "概览", Icons.Outlined.Home),
        LocalNavItem("trips", "行程", Icons.Outlined.DirectionsCar),
        LocalNavItem("charges", "充电", Icons.Outlined.BatteryChargingFull),
        LocalNavItem("local_capabilities", "工具", Icons.Outlined.DirectionsCar),
        LocalNavItem("settings", "设置", Icons.Outlined.Settings),
    )
    Column(
        modifier.fillMaxHeight().background(Color(0xFF0A1725)).padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "BYDMateCN", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        )
        items.forEach { item ->
            val active = item.route == "local_capabilities"
            Card(
                onClick = {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_START_ROUTE, item.route)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    activity?.finish()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(if (active) Color(0xFF087BC1) else Color.Transparent),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(item.icon, null, tint = if (active) Color.White else Color(0xFF8298AE), modifier = Modifier.size(23.dp))
                    Text(item.title, color = if (active) Color.White else Color(0xFFA8BACB), fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}
