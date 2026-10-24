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
                put("region", s.region)
                put("minPrice", s.minPrice ?: -1)
                put("maxPrice", s.maxPrice ?: -1)
                put("excludeWords", s.excludeWords)
                put("customUrl", s.customUrl)
                put("isActive", s.isActive)
            })
        }
        prefs.edit().putString("searches", arr.toString()).apply()
    }

    fun getSearches(): List<SearchRule> {
        val raw = prefs.getString("searches", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val res = mutableListOf<SearchRule>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            res.add(
                SearchRule(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    keywords = o.optString("keywords", ""),
                    category = o.optString("category", ""),
                    region = o.optString("region", "rossiya"),
                    minPrice = o.optInt("minPrice", -1).takeIf { it >= 0 },
                    maxPrice = o.optInt("maxPrice", -1).takeIf { it >= 0 },
                    excludeWords = o.optString("excludeWords", ""),
                    customUrl = o.optString("customUrl", ""),
                    isActive = o.optBoolean("isActive", true)
                )
            )
        }
        return res
    }

    fun saveHistory(list: List<AdItem>) {
        val arr = JSONArray()
        list.take(250).forEach { ad ->
            arr.put(JSONObject().apply {
                put("id", ad.id)
                put("searchId", ad.searchId)
                put("title", ad.title)
                put("price", ad.price)
                put("url", ad.url)
                put("dateFound", ad.dateFound)
            })
        }
        prefs.edit().putString("history_ads", arr.toString()).apply()
    }

    fun getHistory(): List<AdItem> {
        val raw = prefs.getString("history_ads", "[]") ?: "[]"
        return parseAdsJson(raw)
    }

    fun saveFavorites(list: List<AdItem>) {
        val arr = JSONArray()
        list.forEach { ad ->
            arr.put(JSONObject().apply {
                put("id", ad.id)
                put("searchId", ad.searchId)
                put("title", ad.title)
                put("price", ad.price)
                put("url", ad.url)
                put("dateFound", ad.dateFound)
            })
        }
        prefs.edit().putString("favorites", arr.toString()).apply()
    }

    fun getFavorites(): List<AdItem> {
        val raw = prefs.getString("favorites", "[]") ?: "[]"
        return parseAdsJson(raw)
    }

    private fun parseAdsJson(raw: String): List<AdItem> {
        val arr = JSONArray(raw)
        val res = mutableListOf<AdItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            res.add(
                AdItem(
                    id = o.getString("id"),
                    searchId = o.optString("searchId", ""),
                    title = o.getString("title"),
                    price = o.optInt("price", 0),
                    url = o.getString("url"),
                    dateFound = o.optString("dateFound", "")
                )
            )
        }
        return res
    }
}

// --- ПРЯМОЙ ПАРСЕР РЕАЛЬНЫХ ОБЪЯВЛЕНИЙ AVITO ---
object AvitoDirectChecker {
    fun buildUrl(rule: SearchRule): String {
        if (rule.customUrl.startsWith("http")) return rule.customUrl
        val reg = rule.region.trim().ifEmpty { "rossiya" }
        val cat = rule.category.trim()
        val path = if (cat.isNotEmpty()) "/$reg/$cat" else "/$reg"
        val q = URLEncoder.encode(rule.keywords.trim(), "UTF-8")
        return "https://www.avito.ru$path?q=$q&s=104"
    }

    fun checkRule(
        rule: SearchRule,
        knownIds: Set<String>
    ): Pair<List<AdItem>, String?> {
        val url = buildUrl(rule)
        return try {
            val response = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .ignoreHttpErrors(true)
                .timeout(15000)
                .execute()

            val code = response.statusCode()
            val body = response.body()

            if (code == 403 || code == 429 || body.contains("Доступ ограничен") || body.lowercase().contains("firewall")) {
                return Pair(
                    emptyList(),
                    "Avito ограничил доступ (Код $code / Капча). Попробуй включить/выключить режим полёта на телефоне (чтобы сменился мобильный IP) или увеличь интервал проверки."
                )
            }

            if (code != 200) {
                return Pair(emptyList(), "Ошибка ответа Avito: код $code")
            }

            val doc = response.parse()
            val items = doc.select("div[data-marker=item]")

            if (items.isEmpty() && (body.lowercase().contains("captcha") || body.lowercase().contains("робот"))) {
                return Pair(emptyList(), "Avito требует пройти проверку на робота (Капча).")
            }

            val excludeList = rule.excludeWords.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }

            val found = mutableListOf<AdItem>()
            val timeStr = SimpleDateFormat("HH:mm:ss dd.MM", Locale.getDefault()).format(Date())

            for (el in items) {
                val adId = el.attr("data-item-id").ifEmpty { el.attr("id") }
                if (adId.isEmpty() || knownIds.contains(adId)) continue

                val titleEl = el.selectFirst("h3[itemprop=name]") ?: el.selectFirst("a[data-marker=item-title]")
                val priceEl = el.selectFirst("meta[itemprop=price]") ?: el.selectFirst("[data-marker=item-price]")
                val linkEl = el.selectFirst("a[itemprop=url]") ?: el.selectFirst("a[data-marker=item-title]")

                if (titleEl == null || linkEl == null) continue

                val titleText = titleEl.text().trim()
                if (excludeList.any { bad -> titleText.lowercase().contains(bad) }) continue

                val rawPrice = priceEl?.attr("content")?.ifEmpty { priceEl.text() } ?: "0"
                val digits = rawPrice.replace(Regex("[^0-9]"), "")
                val priceVal = digits.toIntOrNull() ?: 0

                if (rule.minPrice != null && priceVal < rule.minPrice) continue
                if (rule.maxPrice != null && priceVal > rule.maxPrice) continue

                val href = linkEl.attr("href")
                val fullUrl = if (href.startsWith("/")) "https://www.avito.ru$href" else href

                found.add(
                    AdItem(
                        id = adId,
                        searchId = rule.id,
                        title = titleText,
                        price = priceVal,
                        url = fullUrl,
                        dateFound = timeStr
                    )
                )
            }
            Pair(found, null)
        } catch (e: Exception) {
            Pair(emptyList(), "Ошибка сети: проверь подключение к интернету (${e.localizedMessage})")
        }
    }
}

// --- ФОНОВАЯ СЛУЖБА 24/7 ---
class RadarForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvitoRadar::WakeLock").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildForegroundNotification("Мониторинг активен..."))
        RadarState.isServiceRunning.value = true

        serviceScope.coroutineContext.cancelChildren()
        serviceScope.launch {
            val storage = LocalStorage(applicationContext)
            var isFirstCycle = storage.getHistory().isEmpty()

            while (isActive) {
                val rules = storage.getSearches().filter { it.isActive }
                val interval = storage.getInterval().coerceAtLeast(30)

                if (rules.isEmpty()) {
                    RadarState.statusText.value = "Нет активных поисков (добавь во вкладке Поиски)"
                    RadarState.errorText.value = null
                } else {
                    val currentHistory = storage.getHistory().toMutableList()
                    val knownIds = currentHistory.map { it.id }.toMutableSet()
                    var anyError: String? = null

                    for (rule in rules) {
                        RadarState.statusText.value = "Проверка: ${rule.title}..."
                        val (newAds, err) = AvitoDirectChecker.checkRule(rule, knownIds)

                        if (err != null) {
                            anyError = err
                        } else if (newAds.isNotEmpty()) {
                            newAds.forEach { ad ->
                                knownIds.add(ad.id)
                                if (!isFirstCycle) {
                                    sendNewAdNotification(applicationContext, ad)
                                }
                            }
                            currentHistory.addAll(0, newAds)
                            storage.saveHistory(currentHistory)
                            RadarState.foundAds.value = currentHistory
                        }
                        delay(3000)
                    }

                    isFirstCycle = false
                    val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    if (anyError != null) {
                        RadarState.statusText.value = "Внимание ($timeNow)"
                        RadarState.errorText.value = anyError
                    } else {
                        RadarState.statusText.value = "ОК — проверено в $timeNow"
                        RadarState.errorText.value = null
                    }
                }
                delay(interval * 1000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RadarState.isServiceRunning.value = false
        RadarState.statusText.value = "Мониторинг остановлен"
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "RADAR_BG_CHANNEL")
            .setContentTitle("Avito Radar работает в фоне")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}

// --- ГЛАВНАЯ АКТИВНОСТЬ ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannels()
        val storage = LocalStorage(this)
        RadarState.foundAds.value = storage.getHistory()

        setContent {
            AvitoRadarTheme {
                RadarMainScreen()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val alertChannel = NotificationChannel(
                "RADAR_ALERTS",
                "Новые объявления Avito",
                NotificationManager.IMPORTANCE_HIGH
            )
            val bgChannel = NotificationChannel(
                "RADAR_BG_CHANNEL",
                "Фоновая служба радара",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(alertChannel)
            manager.createNotificationChannel(bgChannel)
        }
    }
}

@Composable
fun AvitoRadarTheme(content: @Composable () -> Unit) {
    val darkGreenScheme = darkColorScheme(
        primary = Color(0xFF00E676),
        onPrimary = Color.Black,
        secondary = Color(0xFF00C853),
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        onBackground = Color.White,
        onSurface = Color(0xFFE0E0E0),
        error = Color(0xFFFF5252)
    )
    MaterialTheme(colorScheme = darkGreenScheme, content = content)
}

@Composable
fun RadarMainScreen() {
    val context = LocalContext.current
    val storage = remember { LocalStorage(context) }

    var currentTab by remember { mutableStateOf(0) }
    var searches by remember { mutableStateOf(storage.getSearches()) }
    var favorites by remember { mutableStateOf(storage.getFavorites()) }
    var checkInterval by remember { mutableStateOf(storage.getInterval()) }

    val isRunning by RadarState.isServiceRunning.collectAsState()
    val statusText by RadarState.statusText.collectAsState()
    val errorText by RadarState.errorText.collectAsState()
    val historyAds by RadarState.foundAds.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun toggleRadarService(enable: Boolean) {
        val intent = Intent(context, RadarForegroundService::class.java)
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                val tabs = listOf(
                    Triple("Главная", Icons.Default.Radar, 0),
                    Triple("Поиски", Icons.Default.Search, 1),
                    Triple("Избранное", Icons.Default.Favorite, 2),
                    Triple("Настройки", Icons.Default.Settings, 3)
                )
                tabs.forEach { (title, icon, index) ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E676),
                            selectedTextColor = Color(0xFF00E676),
                            indicatorColor = Color(0xFF003319)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            when (currentTab) {
                0 -> HomeTab(
                    isRunning = isRunning,
                    statusText = statusText,
                    errorText = errorText,
                    ads = historyAds,
                    favorites = favorites,
                    onToggleService = { toggleRadarService(it) },
                    onToggleFav = { ad ->
                        val updated = if (favorites.any { it.id == ad.id }) {
                            favorites.filterNot { it.id == ad.id }
                        } else {
                            favorites + ad
                        }
                        favorites = updated
                        storage.saveFavorites(updated)
                    }
                )
                1 -> SearchesTab(
                    searches = searches,
                    onSaveSearches = { updated ->
                        searches = updated
                        storage.saveSearches(updated)
                    }
                )
                2 -> FavoritesTab(
                    favorites = favorites,
                    onRemoveFav = { ad ->
                        val updated = favorites.filterNot { it.id == ad.id }
                        favorites = updated
                        storage.saveFavorites(updated)
                    }
                )
                3 -> SettingsTab(
                    interval = checkInterval,
                    onSaveInterval = { newInt ->
                        checkInterval = newInt
                        storage.saveInterval(newInt)
                    },
                    onClearHistory = {
                        storage.saveHistory(emptyList())
                        RadarState.foundAds.value = emptyList()
                    }
                )
            }
        }
    }
}

// --- ВКЛАДКА 1: ГЛАВНАЯ ---
@Composable
fun HomeTab(
    isRunning: Boolean,
    statusText: String,
    errorText: String?,
    ads: List<AdItem>,
    favorites: List<AdItem>,
    onToggleService: (Boolean) -> Unit,
    onToggleFav: (AdItem) -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AVITO RADAR", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isRunning) "Активен" else "Выкл", color = if (isRunning) Color(0xFF00E676) else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggleService(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E676))
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Статус: $statusText", fontWeight = FontWeight.SemiBold, color = Color.White)
                if (errorText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(errorText, color = Color(0xFFFF5252), fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("История обнаруженных объявлений (${ads.size})", fontSize = 15.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))

        if (ads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Объявлений пока нет.\n1. Создай фильтр во вкладке «Поиски».\n2. Включи тумблер сверху справа.",
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ads, key = { it.id }) { ad ->
                    val isFav = favorites.any { it.id == ad.id }
                    AdCard(
                        ad = ad,
                        isFavorite = isFav,
                        onFavoriteClick = { onToggleFav(ad) },
                        onOpenUrl = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.url)))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AdCard(ad: AdItem, isFavorite: Boolean, onFavoriteClick: () -> Unit, onOpenUrl: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpenUrl() }
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ad.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text("${ad.price} ₽", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("Найдено: ${ad.dateFound}", color = Color.Gray, fontSize = 12.sp)
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "В избранное",
                    tint = if (isFavorite) Color(0xFF00E676) else Color.Gray
                )
            }
        }
    }
}

// --- ВКЛАДКА 2: ПОИСКИ ---
@Composable
fun SearchesTab(searches: List<SearchRule>, onSaveSearches: (List<SearchRule>) -> Unit) {
    var editingRule by remember { mutableStateOf<SearchRule?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Мои поиски", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
            Button(
                onClick = { editingRule = null; showDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Добавить")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (searches.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Список поисков пуст. Нажми «Добавить».", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(searches, key = { it.id }) { rule ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(rule.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                                Switch(
                                    checked = rule.isActive,
                                    onCheckedChange = { active ->
                                        onSaveSearches(searches.map { if (it.id == rule.id) it.copy(isActive = active) else it })
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E676))
                                )
                            }
                            if (rule.keywords.isNotBlank()) Text("Слова: ${rule.keywords}", color = Color.LightGray, fontSize = 13.sp)
                            if (rule.excludeWords.isNotBlank()) Text("Исключить: ${rule.excludeWords}", color = Color(0xFFFF8A80), fontSize = 13.sp)
                            Text(
                                "Цена: ${rule.minPrice ?: 0} — ${rule.maxPrice ?: "∞"} ₽ | Регион: ${rule.region}",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { editingRule = rule; showDialog = true }) {
                                    Text("Изменить", color = Color(0xFF00E676))
                                }
                                TextButton(onClick = { onSaveSearches(searches.filterNot { it.id == rule.id }) }) {
                                    Text("Удалить", color = Color(0xFFFF5252))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        SearchEditorDialog(
            initial = editingRule,
            onDismiss = { showDialog = false },
            onConfirm = { newRule ->
                val updated = if (editingRule == null) searches + newRule else searches.map { if (it.id == newRule.id) newRule else it }
                onSaveSearches(updated)
                showDialog = false
            }
        )
    }
}

@Composable
fun SearchEditorDialog(initial: SearchRule?, onDismiss: () -> Unit, onConfirm: (SearchRule) -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var keywords by remember { mutableStateOf(initial?.keywords ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var region by remember { mutableStateOf(initial?.region ?: "rossiya") }
    var minPrice by remember { mutableStateOf(initial?.minPrice?.toString() ?: "") }
    var maxPrice by remember { mutableStateOf(initial?.maxPrice?.toString() ?: "") }
    var excludeWords by remember { mutableStateOf(initial?.excludeWords ?: "") }
    var customUrl by remember { mutableStateOf(initial?.customUrl ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text(if (initial == null) "Новый поиск" else "Изменить поиск", color = Color(0xFF00E676)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название (напр. Питбайк Kayo)") })
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("Ключевые слова") })
                OutlinedTextField(value = excludeWords, onValueChange = { excludeWords = it }, label = { Text("Минус-слова (через запятую)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minPrice,
                        onValueChange = { minPrice = it },
                        label = { Text("Мин. цена") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxPrice,
                        onValueChange = { maxPrice = it },
                        label = { Text("Макс. цена") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(value = region, onValueChange = { region = it }, label = { Text("Регион (rossiya, moskva...)") })
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Категория (mototsikly_i_mototehnika)") })
                OutlinedTextField(value = customUrl, onValueChange = { customUrl = it }, label = { Text("Или готовая ссылка из браузера Avito") })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(
                            SearchRule(
                                id = initial?.id ?: UUID.randomUUID().toString(),
                                title = title.trim(),
                                keywords = keywords.trim(),
                                category = category.trim(),
                                region = region.trim().ifEmpty { "rossiya" },
                                minPrice = minPrice.toIntOrNull(),
                                maxPrice = maxPrice.toIntOrNull(),
                                excludeWords = excludeWords.trim(),
                                customUrl = customUrl.trim(),
                                isActive = initial?.isActive ?: true
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = Color.Gray) }
        }
    )
}

// --- ВКЛАДКА 3: ИЗБРАННОЕ ---
@Composable
fun FavoritesTab(favorites: List<AdItem>, onRemoveFav: (AdItem) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Избранное", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
        Spacer(Modifier.height(12.dp))
        if (favorites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("В избранном пока пусто.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favorites, key = { it.id }) { ad ->
                    AdCard(
                        ad = ad,
                        isFavorite = true,
                        onFavoriteClick = { onRemoveFav(ad) },
                        onOpenUrl = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.url))) }
                    )
                }
            }
        }
    }
}

// --- ВКЛАДКА 4: НАСТРОЙКИ ---
@Composable
fun SettingsTab(interval: Int, onSaveInterval: (Int) -> Unit, onClearHistory: () -> Unit) {
    val context = LocalContext.current
    var intText by remember { mutableStateOf(interval.toString()) }
    var savedMsg by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))

        OutlinedTextField(
            value = intText,
            onValueChange = { intText = it; savedMsg = false },
            label = { Text("Интервал проверки (30–60 секунд)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val valid = (intText.toIntOrNull() ?: 45).coerceIn(30, 3600)
                intText = valid.toString()
                onSaveInterval(valid)
                savedMsg = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить интервал") }

        if (savedMsg) {
            Text("Настройки сохранены!", color = Color(0xFF00E676), fontSize = 14.sp)
        }

        HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Разрешить работу в фоне без ограничений батареи", color = Color(0xFF00E676))
        }

        OutlinedButton(
            onClick = onClearHistory,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Очистить историю найденных объявлений", color = Color(0xFFFF5252))
        }
    }
}

fun sendNewAdNotification(context: Context, ad: AdItem) {
    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ad.url))
    val pending = PendingIntent.getActivity(context, ad.id.hashCode(), openIntent, PendingIntent.FLAG_IMMUTABLE)

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, "RADAR_ALERTS")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Найдено: ${ad.price} ₽")
        .setContentText(ad.title)
        .setContentIntent(pending)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    manager.notify(ad.id.hashCode(), notification)
}
