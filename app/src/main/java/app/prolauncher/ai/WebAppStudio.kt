package app.prolauncher.ai
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@Composable
fun WebAppStudioView(onClose: () -> Unit, onSaveToMemo: (String) -> Unit) {
 val ctx = LocalContext.current; val aiCfg = remember { loadAiCfg(ctx) }; val ui = rememberUi(aiCfg.uiScale); val scope = rememberCoroutineScope()
 val sp = remember { ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE) }
 var projects by remember { mutableStateOf(loadWebApps(ctx)) }; var folders by remember { mutableStateOf(loadWebAppFolders(ctx)) }
 val initP = remember { val lid = sp.getLong("webapp_active_id_v1", 9001L); projects.find { it.id == lid } ?: projects.firstOrNull() }
 var curAppId by remember { mutableStateOf(initP?.id ?: 9001L) }; var curFolder by remember { mutableStateOf(initP?.folder ?: "유틸리티") }
 var titleTfv by remember { val t = sp.getString("webapp_active_title_v1", initP?.title ?: "더치페이 계산기") ?: "새 웹앱"; mutableStateOf(TextFieldValue(t, TextRange(t.length))) }
 var codeTfv by remember { val c = sp.getString("webapp_active_code_v1", initP?.htmlCode ?: SEED_CALC) ?: STARTER_HTML; mutableStateOf(TextFieldValue(c, TextRange(c.length))) }
 var viewMode by remember { mutableStateOf(sp.getInt("webapp_view_mode_v1", 1).coerceIn(0, 2)) }
 var shelfOpen by remember { mutableStateOf(false) }; var guideOpen by remember { mutableStateOf(false) }; var tokenOpen by remember { mutableStateOf(false) }
 var maxTok by remember { mutableStateOf(sp.getInt("webapp_ai_max_tokens_v1", 3072).coerceIn(512, 16384)) }
 var codeFontSp by remember { mutableStateOf(sp.getInt("webapp_code_font_v1", 13).coerceIn(10, 22)) }; var reloadTick by remember { mutableStateOf(0) }
 var aiMode by remember { mutableStateOf(0) }; var aiReqMode by remember { mutableStateOf("MODIFY") }; var splitHoriz by remember { mutableStateOf(false) }; var floatMin by remember { mutableStateOf(false) }
 var floatX by remember { mutableStateOf(16f) }; var floatY by remember { mutableStateOf(100f) }
 var aiPromptIn by remember { mutableStateOf("") }; var aiAns by remember { mutableStateOf("") }; var aiBusy by remember { mutableStateOf(false) }; var aiJob by remember { mutableStateOf<Job?>(null) }
 var pendingAiHtml by remember { mutableStateOf<String?>(null) }
 var prevCodeBackup by remember { mutableStateOf<String?>(null) }
 var lastGenMode by remember { mutableStateOf("MODIFY") }
 var statusMsg by remember { mutableStateOf("🌐 상단 드롭다운 웹앱 스튜디오 · 실행 & 소형 AI 개발") }

 fun setModeP(m: Int) { viewMode = m.coerceIn(0, 2); sp.edit().putInt("webapp_view_mode_v1", viewMode).apply() }
 fun setTokP(tk: Int) { val c = tk.coerceIn(512, 16384); maxTok = c; sp.edit().putInt("webapp_ai_max_tokens_v1", c).apply(); statusMsg = "🪙 최대 출력 토큰: ${c} 설정됨" }
 fun setCode(s: String) { codeTfv = TextFieldValue(s, TextRange(s.length)); sp.edit().putString("webapp_active_code_v1", s).apply() }
 fun saveApp() {
  val t = titleTfv.text.trim().ifEmpty { Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(codeTfv.text)?.groupValues?.getOrNull(1)?.trim()?.take(26)?.ifEmpty { "내 웹앱" } ?: "내 웹앱" }
  if (titleTfv.text.isBlank()) titleTfv = TextFieldValue(t, TextRange(t.length))
  val id = if (curAppId > 0L && projects.any { it.id == curAppId }) curAppId else System.currentTimeMillis()
  curAppId = id; val fld = curFolder.ifBlank { "유틸리티" }
  if (!folders.contains(fld)) { folders = folders + fld; saveWebAppFolders(ctx, folders) }
  projects = (listOf(WebAppProject(id, t, codeTfv.text, SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date()), fld)) + projects.filterNot { it.id == id }).also { saveWebApps(ctx, it) }
  sp.edit().putLong("webapp_active_id_v1", id).putString("webapp_active_title_v1", t).putString("webapp_active_code_v1", codeTfv.text).apply()
  reloadTick++; statusMsg = "💾 [$fld] 『$t』 저장 완료!"
 }
 fun openApp(p: WebAppProject, runNow: Boolean = true) {
  curAppId = p.id; curFolder = p.folder; titleTfv = TextFieldValue(p.title, TextRange(p.title.length)); setCode(p.htmlCode)
  pendingAiHtml = null; prevCodeBackup = null; aiReqMode = "MODIFY"
  sp.edit().putLong("webapp_active_id_v1", p.id).putString("webapp_active_title_v1", p.title).apply()
  shelfOpen = false; reloadTick++; setModeP(if (runNow) 1 else 0); statusMsg = "▶️ 『${p.title}』 준비 완료"
 }
 fun applyPendingAiCode() {
  val ex = pendingAiHtml ?: extractCleanHtmlFromAi(aiAns).takeIf { it.contains("<") } ?: return
  prevCodeBackup = codeTfv.text
  setCode(ex)
  if (lastGenMode == "NEW" || titleTfv.text == "새 웹앱") {
   Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(ex)?.groupValues?.getOrNull(1)?.trim()?.take(24)?.takeIf { it.isNotEmpty() }?.let { titleTfv = TextFieldValue(it, TextRange(it.length)) }
  }
  pendingAiHtml = null
  saveApp()
  setModeP(1)
  statusMsg = "✅ 요청하신 코드 반영 및 실행 완료! ([↩️이전코드 복원] 가능)"
 }
 fun undoAiCode() {
  val bk = prevCodeBackup ?: return
  setCode(bk); prevCodeBackup = null; reloadTick++
  statusMsg = "↩️ 수정 전 코드로 복원되었습니다."
 }
 val htmlExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { u -> if (u != null) runCatching { ctx.contentResolver.openOutputStream(u)?.use { it.write(codeTfv.text.toByteArray()) }; saveApp(); statusMsg = "⬇️ .html 저장 완료!" } }
 val htmlImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) runCatching { val tx = ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: ""; val g = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(tx)?.groupValues?.getOrNull(1)?.trim()?.take(26)?.ifEmpty { "불러온 웹앱" } ?: "불러온 웹앱"; curAppId = System.currentTimeMillis(); titleTfv = TextFieldValue(g, TextRange(g.length)); setCode(tx); saveApp(); setModeP(1) } }
 fun stopAi() { activeGenId++; if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }; aiJob?.cancel(); aiBusy = false; aiAns = aiAns.removeSuffix(" ▍") }
 fun launchAi(mode: String, req: String) {
  if (mode != "CONTINUE" && req.isBlank()) {
   statusMsg = "⚠️ 수정하거나 만들 내용을 입력창에 먼저 적어주세요!"
   return
  }
  stopAi(); val g = ++activeGenId; aiBusy = true; pendingAiHtml = null
  if (mode != "CONTINUE") lastGenMode = mode
  val cfg = loadAiCfg(ctx)
  val sysP = "모바일 단일파일 HTML5 웹앱 전문 개발자입니다. 설명 없이 <!DOCTYPE html>로 시작해 </html>로 끝나는 완전한 인라인 CSS/JS 코드만 출력하세요."
  val seed = extractCleanHtmlFromAi(aiAns).takeIf { mode == "CONTINUE" && it.contains("<") } ?: codeTfv.text
  val uP = when (mode) {
   "MODIFY" -> "[현재 HTML]\n${codeTfv.text.take(6500)}\n\n[수정 요청]\n$req\n전체 <!DOCTYPE html>...</html> 코드를 출력하세요."
   "CONTINUE" -> "아래 끊긴 HTML의 마지막 부분 바로 뒤부터 중복 없이 </html>까지 이어서 완성하세요:\n\n${seed.takeLast(1400)}"
   else -> "다음 기능의 모바일 단일 파일 HTML5 웹앱(localStorage 활용)을 <!DOCTYPE html>부터 </html>까지 생략 없이 작성하세요: $req"
  }
  val pfx = if (mode == "CONTINUE") seed else ""
  aiAns = "⚡ 웹앱 생성 중 (최대 ${maxTok} 토큰)... ▍"
  aiJob = scope.launch {
   val ans = withContext(Dispatchers.IO) { callLlmHttp(sysP, "", emptyList(), uP, cfg.apiUrl, cfg.apiModel, cfg.apiKey, { pt -> if (g == activeGenId && pt.isNotBlank()) uiP { aiAns = (if (pfx.isNotEmpty()) pfx + "\n" + pt else pt) + " ▍" } }, g, cfg.temp.coerceIn(0.1f, 0.7f), maxOf(cfg.ctxSize, maxTok + 1024).coerceIn(2048, 16384), maxTok, cfg.cpuMode, 0) }
   if (g == activeGenId) {
    aiAns = (if (pfx.isNotEmpty() && !ans.isNullOrBlank()) pfx + "\n" + extractCleanHtmlFromAi(ans) else (ans ?: "").ifBlank { aiAns.removeSuffix(" ▍") }).ifBlank { "⚠️ 생성 실패: AI 세팅이나 토큰을 확인하세요." }
    aiBusy = false; val ex = extractCleanHtmlFromAi(aiAns)
    if (ex.contains("<html", true) || ex.contains("<!DOC" + "TYPE", true)) {
     val isStarterBlank = codeTfv.text.trim() == STARTER_HTML.trim() && lastGenMode == "NEW"
     if (isStarterBlank) {
      prevCodeBackup = codeTfv.text; setCode(ex)
      Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(ex)?.groupValues?.getOrNull(1)?.trim()?.take(24)?.takeIf { it.isNotEmpty() }?.let { titleTfv = TextFieldValue(it, TextRange(it.length)) }
      reloadTick++; if (viewMode == 0) setModeP(2); statusMsg = "✅ 새 웹앱 초안 반영 완료 (${ex.length}자)"
     } else {
      pendingAiHtml = ex
      statusMsg = "✅ 요청하신 코드가 준비되었습니다! [✅ 코드 반영&실행]을 눌러 적용하세요."
     }
    }
   }
  }
 }
 val hasEndHtml = remember(aiAns) { extractCleanHtmlFromAi(aiAns).isNotBlank() && aiAns.lowercase().contains("/html>") }
 val aiBody: @Composable () -> Unit = {
  WebAppAiPane(
   ui = ui, maxTok = maxTok, tokenOpen = tokenOpen, onToggleTok = { tokenOpen = !tokenOpen },
   reqMode = aiReqMode, onSetReqMode = { aiReqMode = it },
   aiPromptIn = aiPromptIn, onPrompt = { aiPromptIn = it },
   aiAns = aiAns, aiBusy = aiBusy, hasEndHtml = hasEndHtml,
   hasPendingCode = pendingAiHtml != null, canUndoCode = prevCodeBackup != null,
   onLaunch = ::launchAi, onStop = ::stopAi,
   onApplyRun = ::applyPendingAiCode, onDiscardPending = { pendingAiHtml = null; statusMsg = "코드 반영을 취소했습니다." },
   onUndoCode = ::undoAiCode, onEmptyWarn = { statusMsg = it }
  )
 }

 BoxWithConstraints(Modifier.fillMaxSize().background(CBG)) {
  val boxW = this.maxWidth; val boxH = this.maxHeight; val density = LocalDensity.current
  val maxWPx = with(density) { boxW.toPx() }; val maxHPx = with(density) { boxH.toPx() }
  val floatW = if (floatMin) 195.dp else (boxW * 0.88f).coerceIn(255.dp, 370.dp)
  val floatH = if (floatMin) 44.dp else (boxH * 0.48f).coerceIn(240.dp, 360.dp)
  val floatWPx = with(density) { floatW.toPx() }; val floatHPx = with(density) { floatH.toPx() }

  Column(Modifier.fillMaxSize().padding(horizontal = ui.hPad, vertical = ui.vPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Row(Modifier.fillMaxWidth(), SB, CV) {
    Column(Modifier.weight(1f)) {
     Text("🌐 웹앱 개발·실행 스튜디오", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis)
     Text(statusMsg, fontSize = ui.subSp, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
     ChipBtn("🪙${maxTok}", tokenOpen, CA) { tokenOpen = !tokenOpen }
     TextButton(onClick = { guideOpen = !guideOpen }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("❓", fontSize = ui.subSp, color = CA) }
     TextButton(onClick = { stopAi(); onClose() }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("✕ 홈으로", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CM) }
    }
   }
   if (guideOpen) Surface(color = CS, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, CG.copy(alpha = 0.7f)), modifier = Modifier.fillMaxWidth()) {
    Text("💡 추천 소형 AI: 로컬 GGUF는 Qwen2.5-Coder-1.5B/3B·Gemma-3-4B, 추천 토큰은 미니 1,536 / 표준 ⭐3,072(기본) / 복합 4,096~8,192 (4,096 초과 시 자동 이어쓰기)", fontSize = 11.sp, lineHeight = 16.sp, color = CW, modifier = Modifier.padding(10.dp))
   }
   if (tokenOpen) WebAppTokenCard(maxTok, ui, ::setTokP) { tokenOpen = false }
   WebHBar {
    ChipBtn("▶️ 앱사용", viewMode == 1, CG) { setModeP(1) }
    ChipBtn("💻 코드편집", viewMode == 0, CW) { setModeP(0) }
    ChipBtn("◫ 동시보기", viewMode == 2, CA) { setModeP(2) }
    ChipBtn("🔧 AI 수정요청", aiMode != 0 && aiReqMode == "MODIFY", CG) { aiReqMode = "MODIFY"; if (aiMode == 0) aiMode = 1; floatMin = false; statusMsg = "🔧 아래 AI 입력창에 원하는 코드 수정사항을 적어주세요." }
    ChipBtn("📂 보관함(${projects.size})", shelfOpen, CA) { shelfOpen = !shelfOpen }
    ChipBtn("💾 저장", false, CG) { saveApp() }
    if (prevCodeBackup != null) ChipBtn("↩️ 되돌리기", false, CA) { undoAiCode() }
    ChipBtn("🔄 새로고침", false, CW) { reloadTick++ }
    ChipBtn("◫ 분할AI", aiMode == 1, CG) { aiMode = if (aiMode == 1) 0 else 1 }
    ChipBtn("🪟 플로팅AI", aiMode == 2, CA) { aiMode = if (aiMode == 2) 0 else 2; floatMin = false }
    ChipBtn("➕ 새웹앱", false, CW) { saveApp(); curAppId = System.currentTimeMillis(); titleTfv = TextFieldValue("새 웹앱", TextRange(4)); setCode(STARTER_HTML); pendingAiHtml = null; prevCodeBackup = null; aiReqMode = "NEW"; reloadTick++; setModeP(2); if (aiMode == 0) aiMode = 1; statusMsg = "✨ 새로 만들 웹앱 설명을 입력창에 적고 [제작요청 ↑]을 눌러주세요." }
    ChipBtn("📥 .html열기", false, CW) { htmlImport.launch(arrayOf("text/html", "text/*", "*/*")) }
    ChipBtn("⬇️ .html저장", false, CG) { htmlExport.launch((titleTfv.text.trim().ifEmpty { "webapp" }) + ".html") }
    ChipBtn("📤 메모보관", false, CM) { onSaveToMemo("🌐 [웹앱: ${titleTfv.text.trim()}]\n```html\n${codeTfv.text.take(1500)}\n```"); statusMsg = "✅ 메모 탭에 저장됨" }
   }
   if (viewMode == 1 && projects.isNotEmpty() && !shelfOpen) {
    WebHBar { Text("⚡내 앱:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CM); projects.forEach { p -> ChipBtn(p.title, p.id == curAppId, CG) { openApp(p, true) } } }
   } else {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
     OutlinedTextField(value = titleTfv, onValueChange = { titleTfv = it; sp.edit().putString("webapp_active_title_v1", it.text).apply() }, placeholder = { Text("웹앱 이름", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
     ChipBtn("📁$curFolder", false, CA) { val idx = folders.indexOf(curFolder); curFolder = if (idx >= 0 && folders.isNotEmpty()) folders[(idx + 1) % folders.size] else "유틸리티" }
     ChipBtn("🔤${codeFontSp}", false, CW) { codeFontSp = if (codeFontSp >= 20) 12 else codeFontSp + 2; sp.edit().putInt("webapp_code_font_v1", codeFontSp).apply() }
    }
   }
   if (shelfOpen) WebAppShelfPanel(projects, folders, curAppId, ui, { nf -> if (!folders.contains(nf)) { folders = folders + nf; saveWebAppFolders(ctx, folders); curFolder = nf } }, ::openApp) { did -> projects = projects.filterNot { it.id == did }.also { saveWebApps(ctx, it) }; if (curAppId == did) projects.firstOrNull()?.let { openApp(it, true) } }
   val codeField: @Composable (Modifier) -> Unit = { m -> Surface(color = CS, shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, CBDR), modifier = m) { OutlinedTextField(value = codeTfv, onValueChange = { codeTfv = it; sp.edit().putString("webapp_active_code_v1", it.text).apply() }, textStyle = TextStyle(fontSize = codeFontSp.sp, lineHeight = (codeFontSp * 1.4f).sp, fontFamily = FontFamily.Monospace, color = CW), modifier = Modifier.fillMaxSize()) } }
   val webField: @Composable (Modifier) -> Unit = { m -> Surface(color = CS, shape = RoundedCornerShape(10.dp), border = BorderStroke(0.9.dp, CG.copy(alpha = 0.6f)), modifier = m) { LiveWebAppRunner(codeTfv.text, reloadTick, Modifier.fillMaxSize()) } }
   val renderWorkspace: @Composable (Modifier) -> Unit = { mod ->
    Column(mod, verticalArrangement = Arrangement.spacedBy(6.dp)) {
     if (viewMode != 1) WebHBar { listOf("HTML기본틀" to STARTER_HTML, "<button>" to "\n<button onclick=\"\">버튼</button>", "카드박스" to "\n<div class=\"card\">내용</div>", "localStorage" to "\nlocalStorage.setItem('k', JSON.stringify(v));", "<script>" to "\n<script>\n</script>").forEach { (l, sn) -> ChipBtn(l, false, CW) { setCode(if (l == "HTML기본틀") sn else codeTfv.text + sn) } } }
     when (viewMode) {
      1 -> webField(Modifier.weight(1f).fillMaxWidth())
      0 -> codeField(Modifier.weight(1f).fillMaxWidth())
      else -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { codeField(Modifier.weight(0.48f).fillMaxWidth()); webField(Modifier.weight(0.52f).fillMaxWidth()) }
     }
    }
   }
   val splitAiCard: @Composable (Modifier) -> Unit = { m ->
    Surface(color = CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CG.copy(alpha = 0.7f)), modifier = m) {
     Column(Modifier.fillMaxSize()) {
      Row(Modifier.fillMaxWidth().background(CS2).padding(horizontal = 10.dp, vertical = 6.dp), SB, CV) {
       Text("🦙 웹앱 AI (${if (aiReqMode == "NEW") "새제작" else "수정"} · ${maxTok}t)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG)
       Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
        Text(if (splitHoriz) "↕상하" else "↔좌우", fontSize = 10.sp, color = CA, modifier = Modifier.clickable { splitHoriz = !splitHoriz })
        Text("🪟플로팅", fontSize = 10.sp, color = CW, modifier = Modifier.clickable { aiMode = 2; floatMin = false })
        Text("✕", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { aiMode = 0 })
       }
      }
      aiBody()
     }
    }
   }
   if (aiMode == 1) {
    if (splitHoriz) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { renderWorkspace(Modifier.weight(0.54f).fillMaxHeight()); splitAiCard(Modifier.weight(0.46f).fillMaxHeight()) }
    else Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { renderWorkspace(Modifier.weight(0.55f).fillMaxWidth()); splitAiCard(Modifier.weight(0.45f).fillMaxWidth()) }
   } else renderWorkspace(Modifier.weight(1f).fillMaxWidth())
  }
  if (aiMode == 2) {
   val cx = floatX.coerceIn(4f, (maxWPx - floatWPx - 4f).coerceAtLeast(4f)); val cy = floatY.coerceIn(8f, (maxHPx - floatHPx - 8f).coerceAtLeast(8f))
   Surface(color = CS, shape = RoundedCornerShape(14.dp), shadowElevation = 12.dp, border = BorderStroke(1.2.dp, CA), modifier = Modifier.offset { IntOffset(cx.roundToInt(), cy.roundToInt()) }.width(floatW).height(floatH)) {
    Column(Modifier.fillMaxSize()) {
     Row(Modifier.fillMaxWidth().background(CS2).pointerInput(maxWPx, maxHPx, floatWPx, floatHPx) { detectDragGestures { ch, d -> ch.consume(); floatX = (floatX + d.x).coerceIn(4f, (maxWPx - floatWPx - 4f).coerceAtLeast(4f)); floatY = (floatY + d.y).coerceIn(8f, (maxHPx - floatHPx - 8f).coerceAtLeast(8f)) } }.padding(horizontal = 10.dp, vertical = 8.dp), SB, CV) {
      Text("✥ 드래그 · 🦙 웹앱 AI (${if (aiReqMode == "NEW") "새제작" else "수정"} · ${maxTok}t)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CA)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
       Text("◫분할", fontSize = 10.sp, color = CG, modifier = Modifier.clickable { aiMode = 1 })
       Text(if (floatMin) "🔼펼침" else "🔽접기", fontSize = 10.sp, color = CW, modifier = Modifier.clickable { floatMin = !floatMin })
       Text("✕", fontSize = 11.sp, color = Color(0xFFF87171), modifier = Modifier.clickable { aiMode = 0 })
      }
     }
     if (!floatMin) aiBody()
    }
   }
  }
 }
}
