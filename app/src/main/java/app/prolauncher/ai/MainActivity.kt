package app.prolauncher.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = LauncherRepository(this)
        val aiEngine = LocalAiNoteEngine(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF08080A),
                    surface = Color(0xFF121318),
                    primary = Color(0xFF10B981),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFE5E7EB)
                )
            ) {
                ProLauncherAiApp(repository = repository, aiEngine = aiEngine)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProLauncherAiApp(
    repository: LauncherRepository,
    aiEngine: LocalAiNoteEngine
) {
    val scope = rememberCoroutineScope()
    // Page 0: App Drawer (Left), Page 1: Minimal Home (Center), Page 2: Local AI Notes & Tasks (Right)
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    var apps by remember { mutableStateOf(repository.loadInstalledApps()) }
    var notes by remember { mutableStateOf(repository.loadNotes()) }
    var todos by remember { mutableStateOf(repository.loadTodos()) }
    var pendingInterruptApp by remember { mutableStateOf<AppEntry?>(null) }

    BackHandler(enabled = pagerState.currentPage != 1 || pendingInterruptApp != null) {
        if (pendingInterruptApp != null) {
            pendingInterruptApp = null
        } else {
            scope.launch { pagerState.animateScrollToPage(1) }
        }
    }

    fun requestAppLaunch(app: AppEntry) {
        if (app.isInterruptEnabled) {
            pendingInterruptApp = app
        } else {
            repository.launchApp(app.packageName)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08080A))
            .systemBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> AppDrawerScreen(
                    apps = apps,
                    onLaunch = { requestAppLaunch(it) },
                    onToggleFavorite = {
                        repository.toggleFavorite(it.packageName)
                        apps = repository.loadInstalledApps()
                    },
                    onToggleInterrupt = {
                        repository.toggleInterrupt(it.packageName)
                        apps = repository.loadInstalledApps()
                    }
                )
                1 -> MinimalHomeScreen(
                    favorites = apps.filter { it.isFavorite }.take(7),
                    pendingTodoCount = todos.count { !it.isDone },
                    onLaunch = { requestAppLaunch(it) },
                    onNavigateDrawer = { scope.launch { pagerState.animateScrollToPage(0) } },
                    onNavigateNotes = { scope.launch { pagerState.animateScrollToPage(2) } }
                )
                2 -> LocalAiNotesScreen(
                    notes = notes,
                    todos = todos,
                    aiEngine = aiEngine,
                    onSaveNotes = {
                        notes = it
                        repository.saveNotes(it)
                    },
                    onSaveTodos = {
                        todos = it
                        repository.saveTodos(it)
                    }
                )
            }
        }

        // Mindful Interrupt Overlay (Pro Launcher style screen-time limiter)
        pendingInterruptApp?.let { targetApp ->
            MindfulInterruptOverlay(
                app = targetApp,
                onCancel = { pendingInterruptApp = null },
                onProceed = {
                    val pkg = targetApp.packageName
                    pendingInterruptApp = null
                    repository.launchApp(pkg)
                }
            )
        }
    }
}

@Composable
fun MinimalHomeScreen(
    favorites: List<AppEntry>,
    pendingTodoCount: Int,
    onLaunch: (AppEntry) -> Unit,
    onNavigateDrawer: () -> Unit,
    onNavigateNotes: () -> Unit
) {
    var currentTime by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Date()
            delay(1000L)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val dateFormat = remember { SimpleDateFormat("M월 d일 EEEE", Locale.KOREA) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Clock & Date Header
        Column {
            Text(
                text = timeFormat.format(currentTime),
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                color = Color.White
            )
            Text(
                text = dateFormat.format(currentTime),
                fontSize = 15.sp,
                color = Color(0xFF9CA3AF)
            )
            if (pendingTodoCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "오늘 남은 할 일 ${pendingTodoCount}건 · 오른쪽으로 스와이프 →",
                    fontSize = 13.sp,
                    color = Color(0xFF10B981),
                    modifier = Modifier.clickable { onNavigateNotes() }
                )
            }
        }

        // Minimalist Text App List
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (favorites.isEmpty()) {
                Text(
                    text = "왼쪽으로 스와이프하여 자주 쓰는 앱을 ★ 즐겨찾기에 추가하세요",
                    fontSize = 16.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.clickable { onNavigateDrawer() }
                )
            } else {
                favorites.forEach { app ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLaunch(app) }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = app.label,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFF3F4F6)
                        )
                        if (app.isInterruptEnabled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "⏳",
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // Bottom Minimal Navigation Hints
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "← 앱 검색 (초성)",
                fontSize = 13.sp,
                color = Color(0xFF6B7280),
                modifier = Modifier.clickable { onNavigateDrawer() }
            )
            Text(
                text = "로컬 AI 메모 & 할 일 →",
                fontSize = 13.sp,
                color = Color(0xFF6B7280),
                modifier = Modifier.clickable { onNavigateNotes() }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerScreen(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    onToggleInterrupt: (AppEntry) -> Unit
) {
    var rawQuery by remember { mutableStateOf("") }
    // Pro Launcher tip: Starting query with Space disables auto-launch temporarily
    val autoLaunchDisabled = rawQuery.startsWith(" ")
    val filtered = remember(apps, rawQuery) {
        apps.filter { KoreanChosungMatcher.matches(it.label, rawQuery) }
    }

    LaunchedEffect(filtered, rawQuery) {
        if (rawQuery.trim().isNotEmpty() && !autoLaunchDisabled && filtered.size == 1) {
            val single = filtered.first()
            if (!single.isInterruptEnabled) {
                rawQuery = ""
                onLaunch(single)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        OutlinedTextField(
            value = rawQuery,
            onValueChange = { rawQuery = it },
            placeholder = { Text("앱 이름 또는 초성 검색 (예: ㅋㅋㅇㅌ) · 공백 시작 시 자동실행 해제") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "길게 눌러 ★ 즐겨찾기 추가/해제 · 우측 ⏳ 아이콘으로 방해 앱 인터럽트 설정",
            fontSize = 12.sp,
            color = Color(0xFF6B7280)
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onLaunch(app) },
                            onLongClick = { onToggleFavorite(app) }
                        )
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.label,
                            fontSize = 18.sp,
                            color = Color(0xFFE5E7EB)
                        )
                        if (app.isFavorite) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "★", color = Color(0xFF10B981), fontSize = 14.sp)
                        }
                    }
                    Text(
                        text = if (app.isInterruptEnabled) "⏳ 인터럽트 켜짐" else "⏳",
                        fontSize = 12.sp,
                        color = if (app.isInterruptEnabled) Color(0xFFF59E0B) else Color(0xFF4B5563),
                        modifier = Modifier
                            .clickable { onToggleInterrupt(app) }
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LocalAiNotesScreen(
    notes: List<NoteItem>,
    todos: List<TodoItem>,
    aiEngine: LocalAiNoteEngine,
    onSaveNotes: (List<NoteItem>) -> Unit,
    onSaveTodos: (List<TodoItem>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var memoInput by remember { mutableStateOf("") }
    var askQuery by remember { mutableStateOf("") }
    var aiAnswer by remember { mutableStateOf<String?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var loadedModelInfo by remember { mutableStateOf<LoadedModelMetadata?>(aiEngine.activeModel) }
    var modelLoadStatus by remember { mutableStateOf("내장 한국어 NLP 활성 (모델 파일 선택 시 자동 전환)") }
    var editingNote by remember { mutableStateOf<NoteItem?>(null) }
    var editingTodo by remember { mutableStateOf<TodoItem?>(null) }

    LaunchedEffect(Unit) {
        aiEngine.autoDetectExistingModel()?.let { detected ->
            loadedModelInfo = detected
            modelLoadStatus = "자동 감지됨: ${detected.fileName} (${detected.architectureHint})"
        }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            modelLoadStatus = "모델 파일 압축 해제 및 헤더 분석 중..."
            scope.launch {
                val res = aiEngine.loadUserProvidedModel(uri)
                res.onSuccess { meta ->
                    loadedModelInfo = meta
                    val sizeMb = meta.sizeBytes / (1024 * 1024)
                    modelLoadStatus = "✅ 로드 완료: ${meta.fileName} (${sizeMb}MB · ${meta.architectureHint})"
                }.onFailure { err ->
                    modelLoadStatus = "⚠️ 모델 로드 실패: ${err.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Notes & Tasks · 로컬 AI",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = modelLoadStatus,
                    fontSize = 11.sp,
                    color = Color(0xFF10B981)
                )
            }
            OutlinedButton(
                onClick = {
                    modelPickerLauncher.launch(arrayOf("*/*"))
                }
            ) {
                Text("📂 모델 선택 (.litertlm/.gguf)", fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Quick Memo Input + AI Analyze Button
        OutlinedTextField(
            value = memoInput,
            onValueChange = { memoInput = it },
            placeholder = { Text("자유롭게 메모하세요 (예: 내일 오후 2시 기획안 제출하고 우유 사기 #중요)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (memoInput.isBlank() || isAnalyzing) return@Button
                    isAnalyzing = true
                    val raw = memoInput.trim()
                    scope.launch {
                        val parsed = aiEngine.analyzeMemo(raw)
                        val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
                        val newNote = NoteItem(
                            id = System.currentTimeMillis(),
                            content = raw,
                            summary = parsed.summary,
                            tags = parsed.tags,
                            createdAt = now,
                            engineUsed = parsed.engineUsed
                        )
                        onSaveNotes(listOf(newNote) + notes)
                        val newTodos = parsed.extractedTasks.mapIndexed { idx, t ->
                            TodoItem(
                                id = System.currentTimeMillis() + idx + 1,
                                title = t.title,
                                dueHint = t.dueHint,
                                priority = t.priority,
                                isDone = false
                            )
                        }
                        if (newTodos.isNotEmpty()) {
                            onSaveTodos(newTodos + todos)
                        }
                        memoInput = ""
                        isAnalyzing = false
                    }
                }
            ) {
                Text(if (isAnalyzing) "온디바이스 AI 분석 중..." else "✨ 로컬 AI로 메모 저장 & 할 일 추출")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Natural Language Q&A over Local Notes
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = askQuery,
                onValueChange = { askQuery = it },
                placeholder = { Text("내 메모에 질문하기 (예: 내가 뭐 사야 하지?)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        aiAnswer = aiEngine.askNotesQuestion(askQuery, notes)
                    }
                }
            ) {
                Text("AI 검색")
            }
        }

        aiAnswer?.let { ans ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = Color(0xFF14261F),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = ans,
                    fontSize = 13.sp,
                    color = Color(0xFFA7F3D0),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    text = "추출된 할 일 (${todos.count { !it.isDone }})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF10B981)
                )
            }
            items(todos, key = { it.id }) { todo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121318), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                onSaveTodos(
                                    todos.map {
                                        if (it.id == todo.id) it.copy(isDone = !it.isDone) else it
                                    }
                                )
                            }
                    ) {
                        Text(
                            text = (if (todo.isDone) "✓ " else "○ ") + todo.title,
                            fontSize = 15.sp,
                            color = if (todo.isDone) Color(0xFF6B7280) else Color(0xFFF3F4F6),
                            textDecoration = if (todo.isDone) TextDecoration.LineThrough else null
                        )
                        Text(
                            text = "기한: ${todo.dueHint} · 우선순위: ${todo.priority}",
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editingTodo = todo }) {
                            Text("수정", fontSize = 12.sp, color = Color(0xFF10B981))
                        }
                        TextButton(
                            onClick = {
                                onSaveTodos(todos.filterNot { it.id == todo.id })
                            }
                        ) {
                            Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "스마트 메모 보관함 (${notes.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF9CA3AF)
                )
            }
            items(notes, key = { it.id }) { note ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121318), RoundedCornerShape(8.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = note.content,
                        fontSize = 15.sp,
                        color = Color(0xFFF3F4F6)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "AI 요약: ${note.summary}",
                        fontSize = 12.sp,
                        color = Color(0xFF10B981)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${note.tags.joinToString(" ") { "#$it" }} · ${note.createdAt}",
                            fontSize = 11.sp,
                            color = Color(0xFF6B7280),
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editingNote = note }) {
                                Text("수정", fontSize = 12.sp, color = Color(0xFF10B981))
                            }
                            TextButton(
                                onClick = {
                                    onSaveNotes(notes.filterNot { it.id == note.id })
                                }
                            ) {
                                Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit Note Dialog (Supports re-analyzing with Local AI on save)
    editingNote?.let { targetNote ->
        var editedText by remember(targetNote) { mutableStateOf(targetNote.content) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("메모 수정") },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updatedText = editedText.trim()
                        if (updatedText.isNotEmpty()) {
                            scope.launch {
                                val reParsed = aiEngine.analyzeMemo(updatedText)
                                onSaveNotes(
                                    notes.map {
                                        if (it.id == targetNote.id) {
                                            it.copy(
                                                content = updatedText,
                                                summary = reParsed.summary,
                                                tags = reParsed.tags,
                                                engineUsed = reParsed.engineUsed + " (수정됨)"
                                            )
                                        } else it
                                    }
                                )
                                editingNote = null
                            }
                        }
                    }
                ) {
                    Text("AI 재분석 및 저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = null }) {
                    Text("취소")
                }
            }
        )
    }

    // Edit Todo Dialog
    editingTodo?.let { targetTodo ->
        var editedTitle by remember(targetTodo) { mutableStateOf(targetTodo.title) }
        var editedDue by remember(targetTodo) { mutableStateOf(targetTodo.dueHint) }
        var editedPriority by remember(targetTodo) { mutableStateOf(targetTodo.priority) }
        AlertDialog(
            onDismissRequest = { editingTodo = null },
            title = { Text("할 일 수정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = { editedTitle = it },
                        label = { Text("할 일 내용") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editedDue,
                        onValueChange = { editedDue = it },
                        label = { Text("기한/시간") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editedPriority,
                        onValueChange = { editedPriority = it },
                        label = { Text("우선순위 (높음/보통)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editedTitle.isNotBlank()) {
                            onSaveTodos(
                                todos.map {
                                    if (it.id == targetTodo.id) {
                                        it.copy(
                                            title = editedTitle.trim(),
                                            dueHint = editedDue.trim().ifEmpty { "기한 없음" },
                                            priority = editedPriority.trim().ifEmpty { "보통" }
                                        )
                                    } else it
                                }
                            )
                            editingTodo = null
                        }
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTodo = null }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun MindfulInterruptOverlay(
    app: AppEntry,
    onCancel: () -> Unit,
    onProceed: () -> Unit
) {
    var secondsLeft by remember { mutableStateOf(5) }
    LaunchedEffect(app) {
        secondsLeft = 5
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF208080A))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "잠시 숨을 고르세요",
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                color = Color.White
            )
            Text(
                text = "'${app.label}' 앱을 열기 전 의식적인 멈춤 시간입니다.\n정말 지금 필요한 작업인가요?",
                fontSize = 15.sp,
                color = Color(0xFF9CA3AF)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("닫고 집중 유지하기 (권장)")
            }
            TextButton(
                onClick = { if (secondsLeft == 0) onProceed() },
                enabled = secondsLeft == 0
            ) {
                Text(
                    if (secondsLeft > 0) "${secondsLeft}초 후 열기 가능..."
                    else "그래도 ${app.label} 열기"
                )
            }
        }
    }
}
