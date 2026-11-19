package app.prolauncher.ai
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
@Composable
fun ReaderMemoWorkspaceCard(
 ctx: Context,
 ui: ResponsiveUi,
 bookId: Long,
 bookTitle: String,
 pgLabel: String,
 currentExcerptOrPage: String,
 initialBody: String,
 pendingAppendExcerpt: String?,
 onConsumedPendingExcerpt: () -> Unit,
 isFloating: Boolean,
 splitHoriz: Boolean,
 onSelectSplitMode: (Boolean) -> Unit,
 onSelectFloatingMode: () -> Unit,
 onSaveBody: (String) -> Unit,
 onClose: () -> Unit,
 modifier: Modifier = Modifier
) {
 val sp = remember { ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE) }
 val draftKey = "reader_memo_draft_$bookId"
 var memoTfv by remember(bookId) {
  val savedDraft = sp.getString(draftKey, null)
  val seed = if (!savedDraft.isNullOrBlank()) savedDraft else initialBody
  mutableStateOf(TextFieldValue(seed, TextRange(seed.length)))
 }
 var memoNotice by remember(bookId) { mutableStateOf("📝 『$bookTitle』 전용 내장 메모 (탭 이동 없이 바로 기록)") }
 var memoDictOn by remember { mutableStateOf(false) }
 fun persistBody(newText: String, fromDict: Boolean = false, commitToNotes: Boolean = true) {
  memoTfv = TextFieldValue(newText, TextRange(newText.length))
  sp.edit().putString(draftKey, newText).apply()
  if (!fromDict && memoDictOn) syncDictationBuffer(ctx, newText)
  if (commitToNotes) onSaveBody(newText)
 }
 LaunchedEffect(initialBody, bookId) {
  if (memoTfv.text.isBlank() && initialBody.isNotBlank()) {
   memoTfv = TextFieldValue(initialBody, TextRange(initialBody.length))
  }
 }
 LaunchedEffect(pendingAppendExcerpt) {
  val ex = pendingAppendExcerpt
  if (!ex.isNullOrBlank()) {
   val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
   val block = "[$pgLabel · $stamp]" + STR_NL + ex.trim()
   val cur = memoTfv.text.trimEnd()
   val joined = if (cur.isBlank()) block else cur + STR_PARA + "────────────" + STR_NL + block
   persistBody(joined, fromDict = false, commitToNotes = true)
   memoNotice = "✅ 선택한 구절이 독서메모에 추가·저장되었습니다."
   onConsumedPendingExcerpt()
  }
 }
 DisposableEffect(Unit) {
  onDispose {
   if (memoDictOn) stopOneShotDictation()
  }
 }
 val fallbackDict = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
  val spk = if (res.resultCode == android.app.Activity.RESULT_OK) {
   res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim() ?: ""
  } else ""
  if (spk.isNotEmpty()) {
   val cur = memoTfv.text
   persistBody(if (cur.isBlank()) spk else "$cur $spk")
   memoNotice = "✅ 음성 입력 반영됨"
  }
  memoDictOn = false
 }
 fun toggleMemoDict() {
  if (memoDictOn) {
   memoDictOn = false
   stopOneShotDictation()
   onSaveBody(memoTfv.text)
   memoNotice = "⏹️ 음성 전사 중지 및 자동 저장됨"
   return
  }
  if (JarvisRuntime.on) JarvisRuntime.stop(ctx)
  memoDictOn = true
  startOneShotDictation(
   ctx = ctx,
   initText = memoTfv.text,
   onPart = { pt -> persistBody(pt, fromDict = true, commitToNotes = false) },
   onDone = { fn -> memoDictOn = false; persistBody(fn, fromDict = true, commitToNotes = true) },
   onStatus = { st -> memoNotice = st },
   onFallback = {
    memoDictOn = false
    runCatching {
     fallbackDict.launch(
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
       .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
       .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
     )
    }
   },
   onCmd = { _, tx ->
    memoDictOn = false
    persistBody(tx, fromDict = true, commitToNotes = true)
    memoNotice = "💾 음성 메모 저장 완료!"
   }
  )
 }
 val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
  if (ok) toggleMemoDict() else { memoDictOn = false; memoNotice = "⚠️ 마이크 권한이 필요합니다." }
 }
 Surface(
  color = Color(0xF50D1524),
  shape = RoundedCornerShape(12.dp),
  border = BorderStroke(1.dp, CG.copy(alpha = 0.7f)),
  modifier = modifier
 ) {
  Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
   if (!isFloating) {
    Row(Modifier.fillMaxWidth(), SB, CV) {
     Text(
      "📝 독서메모 (${if (splitHoriz) "좌우분할" else "상하분할"})",
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      color = CG,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
     )
     Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
      Text(
       if (splitHoriz) "⬒상하분할" else "◫좌우분할",
       fontSize = 11.sp,
       fontWeight = FontWeight.SemiBold,
       color = CW,
       modifier = Modifier.clickable { onSelectSplitMode(!splitHoriz) }
      )
      Text(
       "🪟플로팅",
       fontSize = 11.sp,
       fontWeight = FontWeight.SemiBold,
       color = CA,
       modifier = Modifier.clickable { onSelectFloatingMode() }
      )
      Text(
       "✕닫기",
       fontSize = 11.sp,
       color = CM,
       modifier = Modifier.clickable { onSaveBody(memoTfv.text); onClose() }
      )
     }
    }
   }
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
    ChipBtn(if (memoDictOn) "🔴전사중" else "🎙️음성", memoDictOn, CA) {
     if (ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == 0) toggleMemoDict()
     else micPerm.launch("android.permission.RECORD_AUDIO")
    }
    ChipBtn("❝현재쪽 발췌", false, CG) {
     val sample = currentExcerptOrPage.trim().take(420)
     if (sample.isNotEmpty()) {
      val q = "[$pgLabel 발췌]" + STR_NL + CH_DQ + sample + CH_DQ + STR_NL + "• 생각: "
      val cur = memoTfv.text.trimEnd()
      val nx = if (cur.isBlank()) q else cur + STR_PARA + q
      persistBody(nx)
      memoNotice = "❝ [$pgLabel] 본문을 발췌해 넣었습니다."
     }
    }
    ChipBtn("☑", false, CW) {
     val cur = memoTfv.text
     val nx = if (cur.isEmpty() || cur.endsWith(STR_NL)) cur + "☐ " else cur + STR_NL + "☐ "
     persistBody(nx)
    }
    ChipBtn("•", false, CW) {
     val cur = memoTfv.text
     val nx = if (cur.isEmpty() || cur.endsWith(STR_NL)) cur + "• " else cur + STR_NL + "• "
     persistBody(nx)
    }
    ChipBtn("📅", false, CM) {
     val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
     val cur = memoTfv.text
     val nx = if (cur.isEmpty() || cur.endsWith(" ") || cur.endsWith(STR_NL)) "$cur[$now] " else "$cur [$now] "
     persistBody(nx)
    }
    ChipBtn("💾저장", true, CG) {
     sp.edit().putString(draftKey, memoTfv.text).apply()
     onSaveBody(memoTfv.text)
     memoNotice = "✅ 『$bookTitle』 독서노트에 저장 완료 (${memoTfv.text.length}자)"
    }
   }
   Text(memoNotice, fontSize = 10.sp, color = CA, maxLines = 1, overflow = TextOverflow.Ellipsis)
   OutlinedTextField(
    value = memoTfv,
    onValueChange = { nv ->
     val chg = nv.text != memoTfv.text
     memoTfv = nv
     if (chg) {
      sp.edit().putString(draftKey, nv.text).apply()
      if (memoDictOn) syncDictationBuffer(ctx, nv.text)
      onSaveBody(nv.text)
     }
    },
    placeholder = {
     Text("이 책의 독서 메모를 자유롭게 작성하세요 (탭 전환 없이 자동 이어쓰기·저장)", fontSize = ui.subSp, color = CD)
    },
    singleLine = false,
    modifier = Modifier.weight(1f).fillMaxWidth()
   )
  }
 }
}
@Composable
fun ReaderFloatingMemoWindow(
 ctx: Context,
 ui: ResponsiveUi,
 maxWPx: Float,
 maxHPx: Float,
 floatW: Dp,
 floatH: Dp,
 floatWPx: Float,
 floatHPx: Float,
 floatX: Float,
 floatY: Float,
 floatMin: Boolean,
 onMove: (Float, Float) -> Unit,
 onToggleMin: () -> Unit,
 onSwitchToSplit: (Boolean) -> Unit,
 onClose: () -> Unit,
 bookId: Long,
 bookTitle: String,
 pgLabel: String,
 currentExcerptOrPage: String,
 initialBody: String,
 pendingAppendExcerpt: String?,
 onConsumedPendingExcerpt: () -> Unit,
 onSaveBody: (String) -> Unit
) {
 val clampedX = floatX.coerceIn(4f, (maxWPx - floatWPx - 4f).coerceAtLeast(4f))
 val clampedY = floatY.coerceIn(8f, (maxHPx - floatHPx - 8f).coerceAtLeast(8f))
 Surface(
  color = Color(0xF70E1628),
  shape = RoundedCornerShape(14.dp),
  shadowElevation = 12.dp,
  border = BorderStroke(1.2.dp, CA.copy(alpha = 0.88f)),
  modifier = Modifier
   .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
   .width(floatW)
   .height(floatH)
 ) {
  Column(Modifier.fillMaxSize()) {
   Row(
    Modifier
     .fillMaxWidth()
     .background(Color(0xFF17253E))
     .pointerInput(maxWPx, maxHPx, floatWPx, floatHPx) {
      detectDragGestures { change, dragAmount ->
       change.consume()
       val nx = (floatX + dragAmount.x).coerceIn(4f, (maxWPx - floatWPx - 4f).coerceAtLeast(4f))
       val ny = (floatY + dragAmount.y).coerceIn(8f, (maxHPx - floatHPx - 8f).coerceAtLeast(8f))
       onMove(nx, ny)
      }
     }
     .padding(horizontal = 10.dp, vertical = 7.dp),
    SB,
    CV
   ) {
    Text("✥ 이동 · 📝 독서메모", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CA, maxLines = 1)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
     if (!floatMin) {
      Text("⬒상하", fontSize = 11.sp, color = CG, modifier = Modifier.clickable { onSwitchToSplit(false) })
      Text("◫좌우", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { onSwitchToSplit(true) })
     }
     Text(if (floatMin) "🔼펼침" else "🗕접기", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { onToggleMin() })
     Text("✕", fontSize = 11.sp, color = Color(0xFFF87171), modifier = Modifier.clickable { onClose() })
    }
   }
   if (!floatMin) {
    ReaderMemoWorkspaceCard(
     ctx = ctx,
     ui = ui,
     bookId = bookId,
     bookTitle = bookTitle,
     pgLabel = pgLabel,
     currentExcerptOrPage = currentExcerptOrPage,
     initialBody = initialBody,
     pendingAppendExcerpt = pendingAppendExcerpt,
     onConsumedPendingExcerpt = onConsumedPendingExcerpt,
     isFloating = true,
     splitHoriz = false,
     onSelectSplitMode = onSwitchToSplit,
     onSelectFloatingMode = {},
     onSaveBody = onSaveBody,
     onClose = onClose,
     modifier = Modifier.fillMaxSize()
    )
   }
  }
 }
}
@Composable
fun FolderManageDialog(
 title: String,
 folders: List<String>,
 selectedFolder: String,
 ui: ResponsiveUi,
 onAddFolder: (String) -> Unit,
 onRenameFolder: (String, String) -> Unit,
 onDeleteFolder: (String) -> Unit,
 onBackupAllInTab: () -> Unit,
 onBackupCurrentFolder: (() -> Unit)? = null,
 onRestoreTab: () -> Unit,
 statusText: String? = null,
 onDismiss: () -> Unit
) {
 var newName by remember { mutableStateOf("") }
 var renameTarget by remember { mutableStateOf<String?>(null) }
 var renameInput by remember { mutableStateOf("") }
 AlertDialog(
  onDismissRequest = onDismiss,
  title = { Text(title, fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CG) },
  text = {
   Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("📁 폴더 생성·이름변경·삭제", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
    Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
     OutlinedTextField(
      value = newName,
      onValueChange = { newName = it },
      placeholder = { Text("새 폴더 이름 입력", fontSize = ui.subSp) },
      singleLine = true,
      modifier = Modifier.weight(1f)
     )
     Button(onClick = {
      val t = newName.trim()
      if (t.isNotEmpty()) {
       onAddFolder(t)
       newName = ""
      }
     }) { Text("➕추가", fontSize = ui.subSp) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
     folders.forEach { f ->
      Surface(
       color = Color(0xFF10192B),
       shape = RoundedCornerShape(9.dp),
       border = BorderStroke(0.7.dp, Color(0xFF253552)),
       modifier = Modifier.fillMaxWidth()
      ) {
       if (renameTarget == f) {
        Row(Modifier.padding(6.dp), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
         OutlinedTextField(value = renameInput, onValueChange = { renameInput = it }, singleLine = true, modifier = Modifier.weight(1f))
         Button(onClick = {
          val nv = renameInput.trim()
          if (nv.isNotEmpty()) onRenameFolder(f, nv)
          renameTarget = null
         }) { Text("변경", fontSize = ui.subSp) }
         TextButton(onClick = { renameTarget = null }) { Text("취소", fontSize = ui.subSp, color = CM) }
        }
       } else {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), SB, CV) {
         Text("📁 $f", fontSize = ui.bodySp, fontWeight = FontWeight.SemiBold, color = CW, modifier = Modifier.weight(1f))
         Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
          Text("✏️이름변경", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable { renameTarget = f; renameInput = f })
          if (folders.size > 1) {
           Text("🗑️삭제", fontSize = ui.subSp, color = Color(0xFFF87171), modifier = Modifier.clickable { onDeleteFolder(f) })
          }
         }
        }
       }
      }
     }
    }
    Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1E2D47)))
    Text("💾 이 탭 개별 백업 / 무충돌 병합 복원", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
    Text("복원 시 기존 자료를 덮어쓰지 않고 중복 없이 새 항목만 안전하게 추가합니다.", fontSize = 10.sp, color = CM)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
     ChipBtn("📤 탭 전체 백업(.json)", true, CG) { onBackupAllInTab() }
     if (onBackupCurrentFolder != null && selectedFolder.isNotBlank() && selectedFolder !in listOf("전체", "★", "☑")) {
      ChipBtn("📤 [$selectedFolder] 폴더만 백업", false, CW) { onBackupCurrentFolder() }
     }
     ChipBtn("♻️ 파일 병합 복원", false, CA) { onRestoreTab() }
    }
    statusText?.let { st ->
     Text(st, fontSize = ui.subSp, color = CG)
    }
   }
  },
  confirmButton = { Button(onClick = onDismiss) { Text("닫기") } }
 )
}
@Composable
fun HomeAppFolderDialog(
 ctx: Context,
 repo: LauncherRepository,
 allApps: List<AppEntry>,
 folders: List<AppFolder>,
 ui: ResponsiveUi,
 onUpdateFolders: (List<AppFolder>) -> Unit,
 onDismiss: () -> Unit
) {
 var selFolderId by remember(folders) { mutableStateOf(folders.firstOrNull()?.id ?: 0L) }
 var newFolderName by remember { mutableStateOf("") }
 var renameId by remember { mutableStateOf<Long?>(null) }
 var renameVal by remember { mutableStateOf("") }
 var appSearch by remember { mutableStateOf("") }
 var statusMsg by remember { mutableStateOf<String?>(null) }
 var pendingExportScope by remember { mutableStateOf<String?>(null) }
 val backupExp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
  if (uri != null) {
   runCatching {
    val json = LauncherBackupManager.exportBackupJson(ctx, repo, "HOME", pendingExportScope)
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    statusMsg = "✅ 홈·앱폴더 백업 파일 저장 완료!"
   }.onFailure { statusMsg = "⚠️ 백업 저장 실패: ${it.message}" }
  }
 }
 val backupImp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if (uri != null) {
   runCatching {
    val raw = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    statusMsg = LauncherBackupManager.importAndMergeBackupJson(ctx, repo, raw)
    onUpdateFolders(FolderRegistry.loadAppFolders(ctx, allApps))
   }.onFailure { statusMsg = "⚠️ 복원 실패: ${it.message}" }
  }
 }
 val activeFolder = folders.find { it.id == selFolderId } ?: folders.firstOrNull()
 val filteredApps = remember(allApps, appSearch) {
  if (appSearch.isBlank()) allApps else allApps.filter { KoreanChosungMatcher.matches(it.label, appSearch) }
 }
 AlertDialog(
  onDismissRequest = onDismiss,
  title = { Text("📁 홈·앱서랍 폴더 관리 및 백업", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CG) },
  text = {
   Column(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
     OutlinedTextField(
      value = newFolderName,
      onValueChange = { newFolderName = it },
      placeholder = { Text("새 앱 폴더 추가 (예: 금융, 쇼핑)", fontSize = ui.subSp) },
      singleLine = true,
      modifier = Modifier.weight(1f)
     )
     Button(onClick = {
      val t = newFolderName.trim()
      if (t.isNotEmpty() && folders.none { it.name == t }) {
       val nf = AppFolder(System.currentTimeMillis(), t, emptyList())
       val nx = folders + nf
       FolderRegistry.saveAppFolders(ctx, nx)
       onUpdateFolders(nx)
       selFolderId = nf.id
       newFolderName = ""
      }
     }) { Text("➕생성", fontSize = ui.subSp) }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
     folders.forEach { f ->
      ChipBtn("📁${f.name}(${f.packages.size})", activeFolder?.id == f.id, CG) { selFolderId = f.id }
     }
    }
    activeFolder?.let { af ->
     if (renameId == af.id) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
       OutlinedTextField(value = renameVal, onValueChange = { renameVal = it }, singleLine = true, modifier = Modifier.weight(1f))
       Button(onClick = {
        val nv = renameVal.trim()
        if (nv.isNotEmpty()) {
         val nx = folders.map { if (it.id == af.id) it.copy(name = nv) else it }
         FolderRegistry.saveAppFolders(ctx, nx)
         onUpdateFolders(nx)
        }
        renameId = null
       }) { Text("저장", fontSize = ui.subSp) }
      }
     } else {
      Row(Modifier.fillMaxWidth(), SB, CV) {
       Text("선택 폴더: 📁 ${af.name}", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
       Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
        Text("✏️이름변경", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable { renameId = af.id; renameVal = af.name })
        if (folders.size > 1) {
         Text("🗑️폴더삭제", fontSize = ui.subSp, color = Color(0xFFF87171), modifier = Modifier.clickable {
          val nx = folders.filterNot { it.id == af.id }
          FolderRegistry.saveAppFolders(ctx, nx)
          onUpdateFolders(nx)
          selFolderId = nx.firstOrNull()?.id ?: 0L
         })
        }
       }
      }
     }
     OutlinedTextField(
      value = appSearch,
      onValueChange = { appSearch = it },
      placeholder = { Text("앱 검색·초성 검색 (체크하여 폴더에 담기)", fontSize = ui.subSp) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth()
     )
     LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      items(filteredApps, key = { it.packageName }) { app ->
       val inFolder = app.packageName in af.packages
       Surface(
        color = if (inFolder) CG.copy(alpha = 0.16f) else Color(0xFF101827),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.7.dp, if (inFolder) CG else Color(0xFF223049)),
        modifier = Modifier.fillMaxWidth().clickable {
         val nxPkgs = if (inFolder) af.packages - app.packageName else af.packages + app.packageName
         val nx = folders.map { if (it.id == af.id) it.copy(packages = nxPkgs.distinct()) else it }
         FolderRegistry.saveAppFolders(ctx, nx)
         onUpdateFolders(nx)
        }
       ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), SB, CV) {
         Text((if (inFolder) "☑ " else "☐ ") + app.label, fontSize = ui.bodySp, color = CW)
         if (app.isFavorite) Text("★", fontSize = ui.subSp, color = CA)
        }
       }
      }
     }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
     ChipBtn("📤 홈·앱폴더 백업", true, CG) {
      pendingExportScope = null
      backupExp.launch("prolauncher_home_folders_backup.json")
     }
     ChipBtn("♻️ 홈·앱폴더 병합복원", false, CA) {
      backupImp.launch(arrayOf("*/*"))
     }
    }
    statusMsg?.let { Text(it, fontSize = ui.subSp, color = CG) }
   }
  },
  confirmButton = { Button(onClick = onDismiss) { Text("완료") } }
 )
}
@Composable
fun UnifiedBackupSettingsPanel(ctx: Context, repo: LauncherRepository, ui: ResponsiveUi) {
 val rev = LauncherBackupManager.backupRev
 val appFolders = remember(rev) { FolderRegistry.loadAppFolders(ctx) }
 val writerDocs = remember(rev) { loadWriterDocs(ctx) }
 val writerFolders = remember(rev) { FolderRegistry.loadWriterFolders(ctx) }
 val books = remember(rev) { repo.loadBooks() }
 val bookFolders = remember(rev) { SmartMemoClassifier.loadBookCats(ctx) }
 val notes = remember(rev) { repo.loadNotes() }
 val memoFolders = remember(rev) { SmartMemoClassifier.loadMemoCats(ctx) }
 val aiSessions = remember(rev) { FolderRegistry.loadAiSessions(ctx) }
 val aiFolders = remember(rev) { FolderRegistry.loadAiFolders(ctx) }
 var pendingScope by remember { mutableStateOf("FULL") }
 var statusMsg by remember { mutableStateOf(LauncherBackupManager.lastReport) }
 val scopeCo = rememberCoroutineScope()
 val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
  if (uri != null) {
   val sc = pendingScope
   scopeCo.launch(Dispatchers.IO) {
    val res = runCatching {
     val json = LauncherBackupManager.exportBackupJson(ctx, repo, sc)
     ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
     "✅ [$sc] 백업 파일(.json) 내보내기 완료!"
    }.getOrElse { "⚠️ 백업 실패: ${it.message}" }
    withContext(Dispatchers.Main) { statusMsg = res }
   }
  }
 }
 val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if (uri != null) {
   scopeCo.launch(Dispatchers.IO) {
    val raw = runCatching {
     ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }.getOrDefault("")
    val msg = withContext(Dispatchers.Main) {
     LauncherBackupManager.importAndMergeBackupJson(ctx, repo, raw)
    }
    withContext(Dispatchers.Main) { statusMsg = msg }
   }
  }
 }
 fun triggerExport(sc: String, fileName: String) {
  pendingScope = sc
  exportLauncher.launch(fileName)
 }
 Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
  Surface(color = Color(0xFF0E1A2B), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CG.copy(alpha = 0.75f)), modifier = Modifier.fillMaxWidth()) {
   Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("📦 런처 전체 통합 백업 및 무충돌 병합 복원", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CG)
    Text(
     "5개 탭(홈 앱폴더 · 문서 스튜디오 · 독서대 도서/책갈피 · 메모장 · AI 대화방)의 모든 데이터와 폴더 구조를 한 번에 백업하거나 기존 자료 손실 없이 안전하게 합쳐 복원합니다.",
     fontSize = ui.subSp,
     color = CM
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
     Button(
      onClick = { triggerExport("FULL", "prolauncher_full_backup_v10_5.json") },
      modifier = Modifier.weight(1f),
      colors = ButtonDefaults.buttonColors(containerColor = CG, contentColor = Color(0xFF062019))
     ) { Text("📤 전체 통합 백업", fontSize = ui.subSp, fontWeight = FontWeight.Bold) }
     Button(
      onClick = { importLauncher.launch(arrayOf("*/*")) },
      modifier = Modifier.weight(1f),
      colors = ButtonDefaults.buttonColors(containerColor = CA, contentColor = Color(0xFF1E1303))
     ) { Text("♻️ 백업 파일 복원(병합)", fontSize = ui.subSp, fontWeight = FontWeight.Bold) }
    }
    statusMsg?.let { st ->
     Surface(color = Color(0xFF09121E), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
      Text(st, fontSize = ui.subSp, color = CG, modifier = Modifier.padding(8.dp))
     }
    }
   }
  }
  val itemsInfo = listOf(
   Triple("🏠 홈 · 앱서랍 폴더", "폴더 ${appFolders.size}개 · 즐겨찾기 ${repo.getFavoritePackages().size}개", "HOME" to "prolauncher_home_backup.json"),
   Triple("✍️ 문서 작성 스튜디오", "폴더 ${writerFolders.size}개 · 마크다운 문서 ${writerDocs.size}건", "WRITER" to "prolauncher_writer_backup.json"),
   Triple("📖 독서대 (도서·책갈피·독서노트)", "폴더 ${bookFolders.size}개 · 도서 ${books.size}권", "READER" to "prolauncher_reader_backup.json"),
   Triple("📝 메모장 (체크리스트·일정)", "폴더 ${memoFolders.size}개 · 메모 ${notes.size}건", "MEMO" to "prolauncher_memo_backup.json"),
   Triple("🦙 AI 대화 보관함", "폴더 ${aiFolders.size}개 · 저장 대화 ${aiSessions.size}건", "AI" to "prolauncher_ai_chats_backup.json")
  )
  itemsInfo.forEach { (label, stat, pair) ->
   val (sc, fn) = pair
   Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, Color(0xFF23324B)), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), SB, CV) {
     Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(label, fontSize = ui.bodySp, fontWeight = FontWeight.Bold, color = CW)
      Text(stat, fontSize = ui.subSp, color = CM)
     }
     Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
      ChipBtn("📤개별백업", false, CG) { triggerExport(sc, fn) }
      ChipBtn("♻️병합복원", false, CA) { importLauncher.launch(arrayOf("*/*")) }
     }
    }
   }
  }
 }
}
fun buildHighlightedBookPage(text: String, query: String, isNight: Boolean): AnnotatedString {
 val q = query.trim()
 if (q.isEmpty() || text.isEmpty()) return AnnotatedString(text)
 val ranges = BookPassageSearcher.findMatchRangesInText(text, q, maxHits = 60)
 if (ranges.isEmpty()) return AnnotatedString(text)
 val hlBg = if (isNight) Color(0x99D97706) else Color(0x88FACC15)
 val hlFg = if (isNight) Color(0xFFFFFBEB) else Color(0xFF1C1917)
 return buildAnnotatedString {
  append(text)
  ranges.forEach { r ->
   val s = r.first.coerceIn(0, text.length)
   val e = (r.last + 1).coerceIn(s, text.length)
   if (s < e) addStyle(SpanStyle(background = hlBg, color = hlFg), s, e)
  }
 }
}
@Composable
fun ReaderTopGlobalSearchBar(
 books: List<BookItem>,
 activeBook: BookItem?,
 smartReflow: Boolean,
 query: String,
 onQueryChange: (String) -> Unit,
 scopeAllBooks: Boolean,
 onScopeChange: (Boolean) -> Unit,
 ui: ResponsiveUi,
 onSelectHit: (BookSearchHit) -> Unit,
 onClose: () -> Unit
) {
 val targetBooks = remember(books, activeBook, scopeAllBooks) {
  if (scopeAllBooks || activeBook == null) books else listOf(activeBook)
 }
 var hits by remember { mutableStateOf<List<BookSearchHit>>(emptyList()) }
 var searching by remember { mutableStateOf(false) }
 LaunchedEffect(query, targetBooks, smartReflow) {
  val q = query.trim()
  if (q.isEmpty()) {
   hits = emptyList()
   searching = false
  } else {
   searching = true
   val computed = withContext(Dispatchers.Default) {
    BookPassageSearcher.searchAllBooks(targetBooks, q, smartReflow = smartReflow, maxTotalHits = 80)
   }
   hits = computed
   searching = false
  }
 }
 val matchedBookCount = remember(hits) { hits.map { it.bookId }.distinct().size }
 Surface(
  color = Color(0xF50E1628),
  shape = RoundedCornerShape(13.dp),
  border = BorderStroke(1.dp, CA.copy(alpha = 0.8f)),
  modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
 ) {
  Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Row(Modifier.fillMaxWidth(), SB, CV) {
    Text("🔎 독서대 구절·본문 통합 검색", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
     if (query.isNotEmpty()) {
      Text("지우기", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { onQueryChange("") })
     }
     Text("✕ 닫기", fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.clickable { onClose() })
    }
   }
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
    ChipBtn("📚 독서대 전체 도서(${books.size}권)", scopeAllBooks, CA) { onScopeChange(true) }
    if (activeBook != null) {
     ChipBtn("📖 현재 책만(${activeBook.title.take(10)})", !scopeAllBooks, CG) { onScopeChange(false) }
    }
   }
   OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    placeholder = { Text("다른 기기에서 읽던 문장·단어·책제목 입력...", fontSize = ui.subSp) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth()
   )
   if (query.trim().isEmpty()) {
    Text("💡 기억나는 단어나 문장 일부를 입력하면 서재 내 모든 도서 본문에서 해당 구절이 있는지 찾아 바로 그 페이지로 이동합니다.", fontSize = 10.sp, color = CM)
   } else if (searching) {
    Text("⏳ 도서 본문에서 '${query.trim()}' 위치를 탐색 중입니다...", fontSize = ui.subSp, color = CA)
   } else if (hits.isEmpty()) {
    Text("❌ ${if (scopeAllBooks) "등록된 모든 도서(${books.size}권)" else "현재 도서"}에서 '${query.trim()}' 구절을 찾지 못했습니다.", fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF87171))
   } else {
    Text("✅ ${matchedBookCount}권에서 총 ${hits.size}곳 발견! 항목을 탭하면 해당 페이지로 즉시 이동합니다.", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
     items(hits, key = { "${it.bookId}_${it.charOffset}" }) { h ->
      Surface(
       color = Color(0xFF131E33),
       shape = RoundedCornerShape(9.dp),
       border = BorderStroke(0.7.dp, CG.copy(alpha = 0.45f)),
       modifier = Modifier.fillMaxWidth().clickable { onSelectHit(h) }
      ) {
       Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), SB, CV) {
         Text("📖 ${h.bookTitle}", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
         Text("📁${h.category} · 약 ${h.approxPct}%", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = CA)
        }
        Text(h.snippet, fontSize = 11.sp, color = Color(0xFFD1FAE5), maxLines = 2, overflow = TextOverflow.Ellipsis)
       }
      }
     }
    }
   }
  }
 }
}
@Composable
fun ReaderInBookSearchBar(
 book: BookItem,
 fullText: String,
 vPages: List<PageSlice>,
 curVIdx: Int,
 query: String,
 onQueryChange: (String) -> Unit,
 ui: ResponsiveUi,
 onJumpToCharOffset: (Int) -> Unit,
 onClose: () -> Unit
) {
 val hits = remember(book.id, fullText, query) {
  if (query.trim().isEmpty()) emptyList()
  else BookPassageSearcher.searchBookBody(book, fullText, query, maxHits = 80)
 }
 var activeHitIdx by remember(query, book.id) { mutableStateOf(0) }
 fun pageNumberOf(off: Int): Int {
  val idx = vPages.indexOfLast { it.s <= off }
  return (if (idx >= 0) idx else 0) + 1
 }
 Surface(
  color = Color(0xF50E1628),
  shape = RoundedCornerShape(12.dp),
  border = BorderStroke(1.dp, CG.copy(alpha = 0.75f)),
  modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
 ) {
  Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Row(Modifier.fillMaxWidth(), SB, CV) {
    Text("🔎 현재 책 본문 검색 · 『${book.title.take(14)}』 (현재 ${curVIdx + 1}/${vPages.size}쪽)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
     if (query.isNotEmpty()) {
      Text("지우기", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { onQueryChange("") })
     }
     Text("✕ 닫기", fontSize = ui.subSp, color = CA, modifier = Modifier.clickable { onClose() })
    }
   }
   Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    OutlinedTextField(
     value = query,
     onValueChange = onQueryChange,
     placeholder = { Text("이 책에서 찾고 싶은 문장·단어 입력...", fontSize = ui.subSp) },
     singleLine = true,
     modifier = Modifier.weight(1f)
    )
    if (hits.isNotEmpty()) {
     OutlinedButton(
      onClick = {
       val prev = if (activeHitIdx - 1 < 0) hits.lastIndex else activeHitIdx - 1
       activeHitIdx = prev
       onJumpToCharOffset(hits[prev].charOffset)
      },
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
     ) { Text("◀", fontSize = ui.subSp, color = CG) }
     OutlinedButton(
      onClick = {
       val nxt = (activeHitIdx + 1) % hits.size
       activeHitIdx = nxt
       onJumpToCharOffset(hits[nxt].charOffset)
      },
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
     ) { Text("▶", fontSize = ui.subSp, color = CA) }
    }
   }
   if (query.trim().isNotEmpty()) {
    if (hits.isEmpty()) {
     Text("❌ 이 책 본문에 '${query.trim()}' 구절이 없습니다.", fontSize = ui.subSp, color = Color(0xFFF87171))
    } else {
     val curSafe = activeHitIdx.coerceIn(0, hits.lastIndex)
     Text("✅ 이 책에서 총 ${hits.size}곳 발견 (${curSafe + 1}/${hits.size}번째 · 탭하여 바로 이동)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CG)
     LazyColumn(Modifier.fillMaxWidth().heightIn(max = 155.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      items(hits.size) { i ->
       val h = hits[i]
       val pNo = pageNumberOf(h.charOffset)
       val sel = i == curSafe
       Surface(
        color = if (sel) CG.copy(alpha = 0.18f) else Color(0xFF121C2E),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.7.dp, if (sel) CG else Color(0xFF24344F)),
        modifier = Modifier.fillMaxWidth().clickable {
         activeHitIdx = i
         onJumpToCharOffset(h.charOffset)
        }
       ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), SB, CV) {
         Text("p.${pNo}쪽 (${h.approxPct}%)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CA, modifier = Modifier.padding(end = 8.dp))
         Text(h.snippet, fontSize = 11.sp, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
       }
      }
     }
    }
   }
  }
 }
}
