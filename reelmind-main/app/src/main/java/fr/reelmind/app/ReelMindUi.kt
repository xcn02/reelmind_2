package fr.reelmind.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val blue = Color(0xFF397FDD)
private val paleBlue = Color(0xFF7CB5FF)
private val darkBackground = Color(0xFF10151E)

@Composable
fun ReelMindScreen(vm: ReelModel, activity: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tout") }
    var selected by remember { mutableStateOf<Reel?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var newCaption by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var reelChatId by remember { mutableStateOf<Long?>(null) }
    var showCategories by remember { mutableStateOf(false) }
    var showAllCategories by remember { mutableStateOf(false) }
    var categoryRevision by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var themePreference by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val systemDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val isDark = when (themePreference) { "dark" -> true; "light" -> false; else -> systemDark }
    val scheme = if (isDark) darkColorScheme(primary = paleBlue, background = darkBackground, surface = Color(0xFF1B2532), surfaceVariant = Color(0xFF253344))
                 else lightColorScheme(primary = blue, background = Color(0xFFF7F9FD), surface = Color.White, surfaceVariant = Color(0xFFEAF0F8))
    val categories = remember(categoryRevision) { CategoryStore.list(activity) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.aiError) { vm.aiError?.let { snack.showSnackbar(it); vm.aiError = null } }
    LaunchedEffect(feedback) { if (feedback.isNotBlank()) { snack.showSnackbar(feedback); feedback = "" } }
    LaunchedEffect(vm.pendingUrl) {
        vm.pendingUrl?.let { newUrl = it; newCaption = vm.pendingCaption; showAdd = true; vm.pendingUrl = null; vm.pendingCaption = "" }
    }
    // The detail composable persists its draft on disposal; returning never loses edits.
    BackHandler(showChat) { showChat = false; vm.endLibraryChat() }
    BackHandler(reelChatId != null) { reelChatId = null; vm.endChat() }
    BackHandler(selected != null && reelChatId == null) { selected = null }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Retour") }
                        Text(if (selected == null) "ReelMind" else "Fiche", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (selected != null) {
                            val index = vm.reels.indexOfFirst { it.id == selected!!.id }
                            IconButton(onClick = { if (index > 0) selected = vm.reels[index - 1] }, enabled = index > 0) {
                                Icon(Icons.Default.ChevronLeft, "Fiche précédente")
                            }
                            IconButton(onClick = { if (index >= 0 && index < vm.reels.lastIndex) selected = vm.reels[index + 1] },
                                enabled = index >= 0 && index < vm.reels.lastIndex) {
                                Icon(Icons.Default.ChevronRight, "Fiche suivante")
                            }
                        }
                        if (selected == null && tab != 2) IconButton(onClick = { tab = 2 }) { Icon(Icons.Default.Settings, "Paramètres") }
                    }
                }
            },
            bottomBar = {
                if (selected == null) NavigationBar {
                    listOf(Triple("Accueil", Icons.Default.Home, 0), Triple("Recherche", Icons.Default.Search, 1), Triple("Réglages", Icons.Default.Settings, 2)).forEach { (label, icon, id) ->
                        NavigationBarItem(selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
            },
            floatingActionButton = {
                if (selected == null && tab != 2) FloatingActionButton(onClick = { newUrl = ""; newCaption = ""; showAdd = true },
                    containerColor = blue, contentColor = Color.White) { Icon(Icons.Default.Add, "Ajouter un Reel") }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                if (selected != null) {
                    val reel = vm.reels.firstOrNull { it.id == selected!!.id } ?: selected!!
                    val editBaseline = remember(reel.id) { reel }
                    var swipeDistance by remember(reel.id) { mutableFloatStateOf(0f) }
                    Box(Modifier.fillMaxSize().pointerInput(reel.id, vm.reels.size) {
                        detectHorizontalDragGestures(onDragStart = { swipeDistance = 0f },
                            onDragEnd = {
                                val current = vm.reels.indexOfFirst { it.id == reel.id }
                                val threshold = 105.dp.toPx()
                                if (kotlin.math.abs(swipeDistance) > threshold && current >= 0) {
                                    val next = if (swipeDistance < 0) current + 1 else current - 1
                                    if (next in vm.reels.indices) selected = vm.reels[next]
                                }
                                swipeDistance = 0f
                            }, onHorizontalDrag = { change, amount ->
                                change.consume(); swipeDistance += amount
                            })
                    }) {
                    DetailV51(reel, categories, onSave = { vm.saveEditable(it, editBaseline) },
                        onSaveSummary = { vm.saveSummary(reel, it) },
                        onDelete = { vm.remove(reel); selected = null },
                        onSummarize = vm::summarize, busy = vm.processingId == reel.id,
                        onExport = { activity.shareText(exportMarkdown(reel)) }, onOpen = { activity.openUrl(reel.url) },
                        onImport = { mime -> activity.chooseMedia(mime) { uri -> vm.importMedia(reel, uri) } },
                        onRetry = { vm.retry(reel) }, onChatOpen = { vm.startChat(reel.id); reelChatId = reel.id },
                        onManageCategories = { showCategories = true },
                        onShareLink = { activity.shareText(reel.url) },
                        onDownload = { vm.downloadVideo(reel) { feedback = it } })
                    }
                } else if (tab == 2) {
                    SettingsV51(vm, activity, themePreference, { value -> themePreference = value; prefs.edit().putString("theme", value).apply() }, onManageCategories = { showCategories = true })
                } else {
                    val shown = vm.reels.filter { reel ->
                        (filter == "Tout" || reel.category == filter) &&
                        listOf(reel.title, reel.summary, reel.note, reel.category, reel.sourceText, reel.originalCaption, reel.mediaText, reel.author, reel.url).any { it.contains(search.trim(), ignoreCase = true) }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                        if (tab == 0) {
                            item { SearchEntry(search, { search = it; filter = "Tout"; tab = 1 }, compact = true) }
                            item { SectionTitle("Récemment enregistrés") }
                            item {
                                if (vm.reels.isEmpty()) Text("Partage ton premier Reel depuis Instagram.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(vm.reels.take(8), key = { it.id }) { reel -> RecentCard(reel, onClick = { selected = reel }) }
                                }
                            }
                            item { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { SectionTitle("Catégories") }; IconButton(onClick = { showCategories = true }) { Icon(Icons.Default.Settings, "Organiser les catégories", tint = MaterialTheme.colorScheme.primary) } } }
                            item { CategoryGrid(categories, vm.reels, showAllCategories, onMore = { showAllCategories = !showAllCategories }, onPick = { filter = it; tab = 1 }) }
                            item { SectionTitle("À redécouvrir") }
                            item {
                                vm.reels.filter { it.summary.isNotBlank() }.drop(3).firstOrNull()?.let { reel ->
                                    ReelCard(reel, { selected = reel }, { vm.update(reel.copy(favorite = !reel.favorite)) })
                                } ?: Text("Les fiches plus anciennes réapparaîtront ici.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item { SectionTitle("Toutes les fiches") }
                        } else {
                            item { Text("Rechercher", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                            item { SearchEntry(search, { search = it }) }
                            item { LibraryAiEntry { vm.endLibraryChat(); showChat = true } }
                            item { Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (search.isBlank()) "Explorer les fiches" else "Résultats de recherche", Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold)
                                Text("${shown.size} fiches", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall)
                            } }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items((listOf("Tout") + categories.map { it.name } + vm.reels.map { it.category }.filter { name -> categories.none { cat -> cat.name == name } }).distinct()) { item ->
                                        FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) })
                                    }
                                }
                            }
                        }
                        if (vm.isLoading) item { CircularProgressIndicator() }
                        else if (shown.isEmpty()) item { Text("Aucun résultat.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else items(shown, key = { it.id }) { reel -> ReelCard(reel, { selected = reel }, { vm.update(reel.copy(favorite = !reel.favorite)) }) }
                    }
                }
            }
        }
        if (showCategories) CategoryManager(categories,
            onDismiss = { showCategories = false },
            onReclassify = { vm.reclassifyLocal() ; feedback = "Classement local lancé (sans IA)" },
            onChanged = { updated, oldName, newName ->
                CategoryStore.replaceAll(activity, updated)
                if (oldName != null && newName != null && oldName != newName) vm.changeCategory(oldName, newName)
                categoryRevision++
            })
        reelChatId?.let { chatId ->
            val chatReel = vm.reels.firstOrNull { it.id == chatId }
            if (chatReel != null) ReelChatDialog(chatReel, vm.messages, vm.chatAnswering,
                onDismiss = { reelChatId = null; vm.endChat() }, onAsk = { vm.askReel(chatReel, it) })
        }
        if (showChat) LibraryChatDialog(vm, onDismiss = { showChat = false; vm.endLibraryChat() })
        if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Enregistrer une publication") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(newUrl, { newUrl = it }, label = { Text("Lien Instagram") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newCaption, { newCaption = it }, label = { Text("Légende facultative") }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onClick = {
            val clean = newUrl.trim().substringBefore('?').trimEnd('/')
            if (runCatching { ExtractionCoordinator.validateInstagramUrl(clean) }.isFailure) feedback = "Lien Instagram non reconnu"
            else { vm.addShared(clean, newCaption) { feedback = if (it) "Publication enregistrée" else "Publication déjà enregistrée" }; showAdd = false }
        }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } })
    }
}

@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

@Composable private fun SearchEntry(value: String, onChange: (String) -> Unit, compact: Boolean = false) {
    OutlinedTextField(value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        placeholder = { Text(if (compact) "Rechercher un Reel…" else "Titre, auteur, contenu, transcription…") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotBlank()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Effacer") } })
}

@Composable private fun LibraryAiEntry(onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text("Recherche IA", fontWeight = FontWeight.SemiBold)
                Text("Poser une question sur mes Reels", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun LibraryChatDialog(vm: ReelModel, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val sessionMessages = remember { mutableStateListOf<Pair<Boolean, String>>() }
    var pendingQuestion by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(vm.libraryAnswer, vm.libraryAsking) {
        if (!vm.libraryAsking && pendingQuestion != null && vm.libraryAnswer.isNotBlank()) {
            sessionMessages.add(false to vm.libraryAnswer)
            pendingQuestion = null
        }
    }
    LaunchedEffect(sessionMessages.size, vm.libraryAsking) {
        if (sessionMessages.isNotEmpty()) listState.animateScrollToItem(sessionMessages.size - 1)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Retour") }
                    Column(Modifier.weight(1f)) {
                        Text("Recherche IA", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text("Conversation temporaire · Bibliothèque", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sessionMessages.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 50.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Interroger ma bibliothèque", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text("Pose une question sur les fiches déjà analysées. La discussion ne sera pas conservée.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf("Quels conseils reviennent le plus ?", "Résume mes Reels sur la finance").forEach { suggestion ->
                                OutlinedButton(onClick = {
                                    val history = sessionMessages.joinToString("\n") { (mine, txt) -> "${if (mine) "Utilisateur" else "Assistant"}: $txt" }
                                    sessionMessages.add(true to suggestion)
                                    pendingQuestion = suggestion
                                    vm.askLibrary(suggestion, history)
                                }, enabled = !vm.libraryAsking) { Text(suggestion) }
                            }
                        }
                    }
                    items(sessionMessages.size) { i ->
                        val (mine, content) = sessionMessages[i]
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(18.dp),
                                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.widthIn(max = 320.dp)) {
                                Text(content, Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
                            }
                        }
                    }
                    if (vm.libraryAsking) item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Analyse des fiches…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Poser une question…") }, maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank() && !vm.libraryAsking) {
                                val question = input.trim()
                                val history = sessionMessages.joinToString("\n") { (mine, txt) -> "${if (mine) "Utilisateur" else "Assistant"}: $txt" }
                                sessionMessages.add(true to question); pendingQuestion = question
                                vm.askLibrary(question, history); input = ""; keyboard?.hide()
                            }
                        }))
                    IconButton(onClick = {
                        val question = input.trim()
                        if (question.isNotBlank() && !vm.libraryAsking) {
                            val history = sessionMessages.joinToString("\n") { (mine, txt) -> "${if (mine) "Utilisateur" else "Assistant"}: $txt" }
                            sessionMessages.add(true to question); pendingQuestion = question
                            vm.askLibrary(question, history); input = ""; keyboard?.hide()
                        }
                    }, enabled = input.isNotBlank() && !vm.libraryAsking) {
                        Icon(Icons.Default.Send, "Envoyer", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable private fun RecentCard(reel: Reel, onClick: () -> Unit) {
    Column(Modifier.width(168.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            if (reel.thumbnailUrl.startsWith("https://")) AsyncImage(reel.thumbnailUrl, null, Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayCircleOutline, null, Modifier.size(38.dp)) }
        }
        Text(reel.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(reel.category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun CategoryGrid(categories: List<ReelCategory>, reels: List<Reel>, all: Boolean,
    onMore: () -> Unit, onPick: (String) -> Unit) {
    val visible = if (all) categories else categories.take(5)
    val cells: List<ReelCategory?> = visible.map { it } + if (!all && categories.size > 5) listOf(null) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { cat ->
                    Card(onClick = { if (cat == null) onMore() else onPick(cat.name) },
                        modifier = Modifier.weight(1f).height(86.dp),
                        shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(32.dp).background(
                                    if (cat == null) MaterialTheme.colorScheme.primaryContainer else Color(cat.color).copy(alpha = 0.13f),
                                    RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                    if (cat == null) Icon(Icons.Default.MoreHoriz, null, tint = MaterialTheme.colorScheme.primary)
                                    else Text(cat.icon, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(cat?.name ?: "Plus", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (cat == null) "Toutes les catégories" else
                                "${reels.count { it.category == cat.name }} fiches",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (all && categories.size > 5) TextButton(onClick = onMore) { Text("Afficher moins") }
    }
}

@Composable private fun ReelCard(reel: Reel, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (reel.thumbnailUrl.startsWith("https://")) AsyncImage(reel.thumbnailUrl, null, Modifier.size(100.dp), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    Text(reel.category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text(reel.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (reel.author.isNotBlank()) Text("@${reel.author.removePrefix("@")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) { Icon(if (reel.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (reel.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(reel.summary.ifBlank { when(reel.processingStatus) { "failed" -> "Échec du traitement — ouvrir pour réessayer"; "blocked" -> "Contenu indisponible — ajout manuel possible"; "extracting" -> "Extraction en cours…"; "summarizing" -> "Résumé en cours…"; else -> "À analyser" } }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onCheckedChange = onChange)
    }
}

@Composable private fun SettingsV51(vm: ReelModel, activity: MainActivity, theme: String, onTheme: (String) -> Unit, onManageCategories: () -> Unit) {
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var showKey by remember { mutableStateOf(false) }
    var audioProvider by remember { mutableStateOf(prefs.getString("ex_audio_provider", "Groq") ?: "Groq") }
    var audioModel by remember { mutableStateOf(GeminiModels.resolve(prefs.getString("gemini_audio_model", GeminiModels.DEFAULT))) }
    var imageModel by remember { mutableStateOf(GeminiModels.resolve(prefs.getString("gemini_image_model", GeminiModels.DEFAULT))) }
    var geminiKey by remember { mutableStateOf(prefs.getString("key_Gemini", "").orEmpty()) }
    var groqKey by remember { mutableStateOf(prefs.getString("key_Groq", "").orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Apparence")
        Text("Thème de l'application", style = MaterialTheme.typography.labelLarge)
        listOf("system" to "Automatique (Android)", "light" to "Clair", "dark" to "Sombre").forEach { (value, name) ->
            Row(Modifier.fillMaxWidth().clickable { onTheme(value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == value, onClick = { onTheme(value) }); Text(name)
            }
        }
        HorizontalDivider()
        SectionTitle("Intelligence artificielle")
        SettingSwitch("Résumés automatiques", vm.autoProcess) { vm.saveAutoProcess(activity, it) }
        Text("Fournisseur des résumés")
        ChoiceMenu(vm.provider, listOf("Gemini", "Groq", "OpenAI", "Ollama")) { vm.saveProvider(activity, it) }
        Text("Modèle de résumé", style = MaterialTheme.typography.labelLarge)
        if (vm.provider == "Gemini") ChoiceMenu(vm.modelName, listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash", "Personnalisé")) { if (it != "Personnalisé") vm.saveModel(activity, it) }
        OutlinedTextField(vm.modelName, { vm.saveModel(activity, it) }, label = { Text("Identifiant exact du modèle") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (vm.provider == "Ollama") OutlinedTextField(vm.ollamaUrl, { vm.saveOllamaUrl(activity, it) }, label = { Text("URL HTTPS Ollama") }, modifier = Modifier.fillMaxWidth())
        else OutlinedTextField(vm.apiKey, { vm.saveApiKey(activity, it) }, label = { Text("Clé API ${vm.provider}") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Masquer" else "Voir") } })
        Text("Clés supplémentaires pour les traitements de médias", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(geminiKey, { geminiKey = it; prefs.edit().putString("key_Gemini", it).apply(); if (vm.provider == "Gemini") vm.saveApiKey(activity, it) }, label = { Text("Clé Gemini (images / audio)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(groqKey, { groqKey = it; prefs.edit().putString("key_Groq", it).apply(); if (vm.provider == "Groq") vm.saveApiKey(activity, it) }, label = { Text("Clé Groq (transcription)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        HorizontalDivider()
        ExtractionSettingsPanel(activity)
        HorizontalDivider()
        SectionTitle("Transcription et images")
        Text("Fournisseur de transcription audio")
        ChoiceMenu(audioProvider, listOf("Groq", "Gemini", "Désactivée")) { audioProvider = it; prefs.edit().putString("ex_audio_provider", it).putBoolean("ex_audio", it != "Désactivée").apply() }
        if (audioProvider == "Gemini") OutlinedTextField(audioModel, { audioModel = it; prefs.edit().putString("gemini_audio_model", it).apply() }, label = { Text("Modèle Gemini pour l'audio") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(imageModel, { imageModel = it; prefs.edit().putString("gemini_image_model", it).apply() }, label = { Text("Modèle Gemini pour les images") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Les médias importés pour analyse sont supprimés après traitement. Les données textuelles produites restent dans la fiche.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("Organisation")
        OutlinedButton(onClick = onManageCategories, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Gérer les catégories") }
        HorizontalDivider()
        SectionTitle("Bibliothèque et outils")
        OutlinedButton(onClick = activity::requestNotifications, modifier = Modifier.fillMaxWidth()) { Text("Activer les notifications") }
        OutlinedButton(onClick = vm::requeueUnfinished, modifier = Modifier.fillMaxWidth()) { Text("Relancer les fiches sans résumé") }
        OutlinedButton(onClick = { activity.shareText(exportLibraryMarkdown(vm.reels)) }, modifier = Modifier.fillMaxWidth()) { Text("Exporter la bibliothèque (Markdown)") }
        Text("Les clés sont stockées localement dans les préférences Android, sans chiffrement applicatif supplémentaire.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun ChoiceMenu(selected: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected, modifier = Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); expanded = false }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun CategoryManager(categories: List<ReelCategory>, onDismiss: () -> Unit,
    onReclassify: () -> Unit,
    onChanged: (List<ReelCategory>, String?, String?) -> Unit) {
    var editing by remember { mutableStateOf<ReelCategory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ReelCategory?>(null) }
    val latestCategories by rememberUpdatedState(categories)
    val latestOnChanged by rememberUpdatedState(onChanged)
    val rowStep = with(LocalDensity.current) { 54.dp.toPx() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Gérer les catégories") }, text = {
        LazyColumn(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(categories, key = { it.name }) { cat ->
                var dragAccum by remember(cat.name) { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth().height(50.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                    // Drag from the grip; the rest of the row remains independently clickable.
                    Box(Modifier.size(42.dp).pointerInput(cat.name, rowStep) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragAccum = 0f },
                            onDragEnd = { dragAccum = 0f },
                            onDragCancel = { dragAccum = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragAccum += amount.y
                                if (kotlin.math.abs(dragAccum) >= rowStep) {
                                    val current = latestCategories
                                    val from = current.indexOfFirst { it.name == cat.name }
                                    val direction = if (dragAccum > 0) 1 else -1
                                    val to = from + direction
                                    if (from >= 0 && to in current.indices) {
                                        val revised = current.toMutableList()
                                        revised.add(to, revised.removeAt(from))
                                        latestOnChanged(revised, null, null)
                                        dragAccum -= rowStep * direction
                                    } else dragAccum = 0f
                                }
                            }
                        )
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.DragHandle, "Maintenir et déplacer ${cat.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(cat.icon, modifier = Modifier.padding(end = 8.dp))
                    Text(cat.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { editing = cat }, modifier = Modifier.size(40.dp),
                        enabled = cat.name != CategoryStore.UNCLASSIFIED) {
                        Icon(Icons.Default.Edit, "Modifier", modifier = Modifier.size(21.dp))
                    }
                    IconButton(onClick = { deleting = cat }, modifier = Modifier.size(40.dp),
                        enabled = cat.name != CategoryStore.UNCLASSIFIED) {
                        Icon(Icons.Default.DeleteOutline, "Supprimer", modifier = Modifier.size(21.dp))
                    }
                }
            }
            item { Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une catégorie")
            } }
            item { OutlinedButton(onClick = onReclassify, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text("Classer sans IA")
            } }
            item { Text("Toutes les catégories, y compris « À classer », sont repositionnables. Maintiens la poignée ≡ pour les déplacer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
    if (adding || editing != null) CategoryEditor(categories, editing, onDismiss = { adding = false; editing = null }) { item ->
        val old = editing?.name
        onChanged(if (old == null) categories + item else categories.map { if (it.name == old) item else it }, old, item.name)
        adding = false; editing = null
    }
    deleting?.let { cat ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Supprimer ${cat.name} ?") },
            text = { Text("Les fiches seront conservées et déplacées dans « À classer ».") },
            confirmButton = { TextButton(onClick = {
                onChanged(categories.filterNot { it.name == cat.name }, cat.name, "À classer")
                deleting = null
            }) { Text("Supprimer") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } })
    }
}

@Composable private fun CategoryEditor(categories: List<ReelCategory>, original: ReelCategory?, onDismiss: () -> Unit,
    onSave: (ReelCategory) -> Unit) {
    var name by remember(original) { mutableStateOf(original?.name.orEmpty()) }
    var icon by remember(original) { mutableStateOf(original?.icon ?: "📁") }
    var color by remember(original) { mutableStateOf(original?.color ?: 0xFF4285DBL) }
    var keywords by remember(original) { mutableStateOf(original?.keywords?.joinToString(", ").orEmpty()) }
    val icons = listOf("📁", "📚", "🎯", "💡", "⭐", "⚡", "🔥", "💎", "🏆", "🎨", "🎵", "🎮", "🍕", "✈️", "💪", "💰", "📝", "🔬", "🐾", "🏠", "🚗", "🌿")
    val colors = listOf(0xFF4285DBL, 0xFF18A999L, 0xFF9163D5L, 0xFFE89140L, 0xFFD65C85L, 0xFF36A66BL)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (original == null) "Ajouter une catégorie" else "Modifier la catégorie") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(icon, style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedTextField(name, { name = it.take(35) }, modifier = Modifier.fillMaxWidth(), label = { Text("Nom de la catégorie") })
            OutlinedTextField(keywords, { keywords = it.take(280) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Mots-clés (séparés par des virgules)") },
                supportingText = { Text("Jusqu'à 10 mots-clés. Correspondance locale gratuite, sans appel IA.") })
            Text("Choisir une icône")
            icons.chunked(7).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { value -> Surface(onClick = { icon = value }, shape = RoundedCornerShape(10.dp), color = if (icon == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(value, Modifier.padding(7.dp))
                } }
            } }
            Text("Couleur")
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                colors.forEach { value -> Box(Modifier.size(34.dp).background(Color(value), RoundedCornerShape(30.dp)).clickable { color = value }, contentAlignment = Alignment.Center) { if (color == value) Icon(Icons.Default.Check, null, tint = Color.White) } }
            }
        }
    }, confirmButton = { Button(onClick = { onSave(ReelCategory(name.trim(), icon, color, keywords.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10))) }, enabled = name.isNotBlank() && !name.trim().equals(CategoryStore.UNCLASSIFIED, true) &&
                categories.none { it.name.equals(name.trim(), true) && it.name != original?.name }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

/** Render structured model answers in readable sections without changing the stored raw summary. */
@Composable private fun StructuredSummary(raw: String) {
    val header = Regex("(?i)^(Résumé|Points clés|Étapes|Lieux|Références|Matériel|Ingrédients|Conseils)\\s*:")
    val groups = mutableListOf<Pair<String, StringBuilder>>()
    for (line in raw.lines()) {
        val match = header.find(line.trim())
        if (match != null) {
            groups.add(match.groupValues[1] to StringBuilder(line.trim().substring(match.value.length).trim()))
        } else if (groups.isNotEmpty()) {
            groups.last().second.append("\n").append(line)
        } else if (line.isNotBlank()) {
            groups.add("Résumé" to StringBuilder(line))
        }
    }
    if (groups.isEmpty()) { Text(raw); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { (label, body) ->
            val description = body.toString().trim()
            if (description.isNotBlank()) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun DetailV51(reel: Reel, categories: List<ReelCategory>, onSave: (Reel) -> Unit,
    onSaveSummary: (String) -> Unit, onDelete: () -> Unit,
    onSummarize: (Reel) -> Unit, busy: Boolean, onExport: () -> Unit, onOpen: () -> Unit,
    onImport: (String) -> Unit, onRetry: () -> Unit, onChatOpen: () -> Unit, onManageCategories: () -> Unit,
    onShareLink: () -> Unit, onDownload: () -> Unit) {
    var title by remember(reel.id) { mutableStateOf(reel.title) }
    var category by remember(reel.id) { mutableStateOf(reel.category) }
    var note by remember(reel.id) { mutableStateOf(reel.note) }
    var source by remember(reel.id) { mutableStateOf(reel.sourceText) }
    var summary by remember(reel.id) { mutableStateOf(reel.summary) }
    var captionExpanded by remember { mutableStateOf(false) }
    var transcriptExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mediaMenu by remember { mutableStateOf(false) }
    val latestSave by rememberUpdatedState(onSave)
    val draft = reel.copy(title = title, note = note, category = category, sourceText = source)
    LaunchedEffect(reel.id, title, note, category, source) {
        if (title != reel.title || note != reel.note || category != reel.category || source != reel.sourceText) {
            delay(550)
            latestSave(draft)
        }
    }
    val lastDraft by rememberUpdatedState(draft)
    DisposableEffect(reel.id) {
        onDispose {
            if (lastDraft.title != reel.title || lastDraft.note != reel.note || lastDraft.category != reel.category || lastDraft.sourceText != reel.sourceText)
                latestSave(lastDraft)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (reel.thumbnailUrl.startsWith("https://")) AsyncImage(reel.thumbnailUrl, "Miniature", Modifier.fillMaxWidth().height(205.dp), contentScale = ContentScale.Crop)
        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Titre") })
        Text(reel.author.ifBlank { "Instagram" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f), contentPadding = PaddingValues(5.dp)) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text("Télécharger", maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onShareLink, modifier = Modifier.weight(1f), contentPadding = PaddingValues(5.dp)) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Text("Partager", maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f), contentPadding = PaddingValues(5.dp)) {
                Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp)); Text("Instagram", maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ChoiceMenu(category, categories.map { it.name }.distinct()) { category = it } }
            IconButton(onClick = onManageCategories) { Icon(Icons.Default.Settings, "Gérer les catégories") }
        }
        Text("Résumé IA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (reel.summary.isNotBlank()) StructuredSummary(reel.summary)
        else Text("Pas encore de résumé. Il faut une transcription ou des images analysées.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(onClick = onChatOpen, enabled = reel.summary.isNotBlank() || reel.mediaText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary)
                Text("Poser une question sur ce Reel", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Analyse", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(when (reel.processingStatus) { "done" -> "Terminée"; "failed" -> "Échec"; "extracting" -> "Extraction…"; "summarizing" -> "Résumé IA…"; "blocked" -> "Extraction / configuration"; else -> "En attente" }, style = MaterialTheme.typography.labelMedium)
        }
        if (reel.processingError.isNotBlank()) Text(reel.processingError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (reel.extractionDiagnostic.isNotBlank()) {
            Text("Diagnostic extraction : ${reel.extractionDiagnostic}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (reel.originalCaption.isNotBlank()) Text("Légende extraite", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (reel.mediaText.isNotBlank()) Text("Transcription enregistrée (${reel.mediaText.length} caractères)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Ajoute une image, un audio ou une vidéo pour compléter l'analyse. Le fichier temporaire sera supprimé après traitement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Réanalyser") }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { mediaMenu = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Média") }
                DropdownMenu(expanded = mediaMenu, onDismissRequest = { mediaMenu = false }) {
                    listOf("Image" to "image/*", "Audio" to "audio/*", "Vidéo" to "video/*").forEach { (label, mime) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { mediaMenu = false; onImport(mime) })
                    }
                }
            }
        }
        if (reel.originalCaption.isNotBlank()) {
            TextButton(onClick = { captionExpanded = !captionExpanded }) { Text("Légende originale"); Icon(if (captionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
            if (captionExpanded) Text(reel.originalCaption)
        }
        if (reel.mediaText.isNotBlank()) {
            TextButton(onClick = { transcriptExpanded = !transcriptExpanded }) { Text("Transcription"); Icon(if (transcriptExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
            if (transcriptExpanded) Text(reel.mediaText)
        }
        TextButton(onClick = { sourceExpanded = !sourceExpanded }) { Text("Texte source / édition"); Icon(if (sourceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
        if (sourceExpanded) {
            OutlinedTextField(source, { source = it }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            OutlinedTextField(summary, { summary = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Corriger le résumé") })
            if (summary != reel.summary) Button(onClick = { onSaveSummary(summary) }) { Text("Enregistrer le résumé corrigé") }
            Button(onClick = { onSummarize(reel.copy(title = title, category = category, sourceText = source)) }, enabled = source.isNotBlank() && !busy) { Text("Générer le résumé") }
        }
        HorizontalDivider()
        Text("Mes notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(note, { note = it }, placeholder = { Text("Ajouter une remarque personnelle…") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Text("Modifications enregistrées automatiquement", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Partager / exporter la fiche") }
        TextButton(onClick = { confirmDelete = true }) { Text("Supprimer la fiche") }
        Spacer(Modifier.height(20.dp))
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Supprimer cette fiche ?") }, confirmButton = { TextButton(onClick = onDelete) { Text("Supprimer") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } })
}


/** Full-screen ephemeral chat. Leaving this dialog discards the whole conversation. */
@Composable private fun ReelChatDialog(reel: Reel, messages: List<ChatMessage>, busy: Boolean,
    onDismiss: () -> Unit, onAsk: (String) -> Unit) {
    var input by remember(reel.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Quitter le chat") }
                    Column(Modifier.weight(1f)) {
                        Text("Discuter avec ce Reel", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium)
                        Text(reel.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Fermer") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (messages.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text("Pose une question sur ce Reel", fontWeight = FontWeight.SemiBold)
                            Text("Les réponses utilisent sa transcription et son analyse. " +
                                "Cette discussion sera effacée en quittant.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(messages) { msg ->
                        val mine = msg.role == "user"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(18.dp),
                                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.widthIn(max = 310.dp)) {
                                Text(msg.content, Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (busy) item {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            Text("Réponse en cours…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Écris ta question…") }, maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank() && !busy) { onAsk(input.trim()); input = ""; keyboard?.hide() }
                        }))
                    IconButton(onClick = { onAsk(input.trim()); input = ""; keyboard?.hide() },
                        enabled = input.isNotBlank() && !busy) {
                        Icon(Icons.Default.Send, "Envoyer", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
