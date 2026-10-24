package com.example.avitoradar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

// --- МОДЕЛИ ДАННЫХ ---
data class SearchRule(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val keywords: String,
    val category: String,
    val region: String,
    val minPrice: Int?,
    val maxPrice: Int?,
    val excludeWords: String,
    val customUrl: String,
    val isActive: Boolean = true
)

data class AdItem(
    val id: String,
    val searchId: String,
    val title: String,
    val price: Int,
    val url: String,
    val dateFound: String
)

// --- ОБЩЕЕ СОСТОЯНИЕ ДЛЯ СВЯЗИ ФОНА И ЭКРАНА ---
object RadarState {
    val isServiceRunning = MutableStateFlow(false)
    val statusText = MutableStateFlow("Мониторинг остановлен")
    val errorText = MutableStateFlow<String?>(null)
    val foundAds = MutableStateFlow<List<AdItem>>(emptyList())
}

// --- ЛОКАЛЬНОЕ ХРАНИЛИЩЕ В ПАМЯТИ ТЕЛЕФОНА ---
class LocalStorage(context: Context) {
    private val prefs = context.getSharedPreferences("avito_radar_standalone", Context.MODE_PRIVATE)

    fun saveInterval(sec: Int) = prefs.edit().putInt("interval", sec).apply()
    fun getInterval(): Int = prefs.getInt("interval", 45)

    fun saveSearches(list: List<SearchRule>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("keywords", s.keywords)
                put("category", s.category)
                
