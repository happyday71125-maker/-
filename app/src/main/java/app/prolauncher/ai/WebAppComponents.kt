package app.prolauncher.ai
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

@Composable
fun WebHBar(content: @Composable RowScope.() -> Unit) {
 Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV, content = content)
}

@Composable
fun WebAppTokenCard(maxTok: Int, ui: ResponsiveUi, onSetTok: (Int) -> Unit, onClose: () -> Unit) {
 var tokDraft by remember(maxTok) { mutableStateOf(maxTok.toString()) }
 Surface(color = CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA.copy(alpha = 0.75f)), modifier = Modifier.fillMaxWidth()) {
  Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Row(Modifier.fillMaxWidth(), SB, CV) {
    Text("🎛️ 웹앱 AI 최대 출력 토큰 수동 설정: ${maxTok} 토큰", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
    Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { onClose() })
   }
   Text("• 소형 로컬 AI(1.5B~4B GGUF) 추천값: ⭐3,072 토큰 / 4,096 초과 시 자동 이어쓰기로 완성", fontSize = 10.sp, color = CM)
   WebHBar {
    listOf(1536 to "⚡1,536(미니·1B)", 3072 to "⭐3,072(표준·소형AI추천)", 4096 to "🚀4,096(복합앱)", 8192 to "👑8,192(대형·이어쓰기)", 12288 to "🔥12,288(풀스택)").forEach { (tk, lb) ->
     ChipBtn(lb, maxTok == tk, if (tk == 3072) CG else CA) { onSetTok(tk) }
    }
   }
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
    ChipBtn("-512", false, CW) { onSetTok(maxTok - 512) }
    ChipBtn("+512", false, CG) { onSetTok(maxTok + 512) }
    ChipBtn("+1024", false, CA) { onSetTok(maxTok + 1024) }
    OutlinedTextField(value = tokDraft, onValueChange = { tokDraft = it.filter { c -> c.isDigit() }.take(5) }, singleLine = true, textStyle = TextStyle(fontSize = 12.sp, color = CW), modifier = Modifier.weight(1f))
    Button(onClick = { tokDraft.toIntOrNull()?.let { onSetTok(it) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = CG, contentColor = CON_ACCENT)) { Text("적용", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
   }
   Slider(value = maxTok.coerceIn(512, 12288).toFloat(), onValueChange = { onSetTok(((it / 256f).roundToInt() * 256).coerceIn(512, 12288)) }, valueRange = 512f..12288f, steps = 45, modifier = Modifier.fillMaxWidth().height(22.dp))
  }
 }
}

@Composable
fun WebAppShelfPanel(projects: List<WebAppProject>, folders: List<String>, curAppId: Long, ui: ResponsiveUi, onAddFolder: (String) -> Unit, onOpen: (WebAppProject, Boolean) -> Unit, onDelete: (Long) -> Unit) {
 var shelfFilter by remember { mutableStateOf("전체") }
 var newFolderIn by remember { mutableStateOf("") }
 val flt = remember(projects, shelfFilter) { if (shelfFilter == "전체") projects else projects.filter { it.folder == shelfFilter } }
 Surface(color = CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA.copy(alpha = 0.7f)), modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
  Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   WebHBar { (listOf("전체") + folders).distinct().forEach { f -> ChipBtn(if (f == "전체") "📁전체(${projects.size})" else "📁$f", shelfFilter == f, CG) { shelfFilter = f } } }
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
    OutlinedTextField(value = newFolderIn, onValueChange = { newFolderIn = it }, placeholder = { Text("새 폴더 추가...", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
    ChipBtn("➕폴더", true, CG) { val nf = newFolderIn.trim(); if (nf.isNotEmpty()) { onAddFolder(nf); newFolderIn = "" } }
   }
   LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f, fill = false)) {
    items(flt, key = { it.id }) { p ->
     Surface(color = if (p.id == curAppId) CG.copy(alpha = 0.16f) else CS2, shape = RoundedCornerShape(8.dp), border = BorderStroke(0.7.dp, if (p.id == curAppId) CG else CBDR), modifier = Modifier.fillMaxWidth().clickable { onOpen(p, true) }) {
      Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), SB, CV) {
       Column(Modifier.weight(1f)) { Text("🌐 ${p.title}", fontSize = ui.bodySp, fontWeight = FontWeight.SemiBold, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("📁${p.folder} · ${p.updatedAt} · ${p.htmlCode.length}자", fontSize = 10.sp, color = CM) }
       Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
        Text("💻편집", fontSize = 11.sp, color = CA, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpen(p, false) })
        if (projects.size > 1) Text("🗑️", fontSize = 13.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable { onDelete(p.id) })
       }
      }
     }
    }
   }
  }
 }
}

@Composable
fun WebAppAiPane(
 ui: ResponsiveUi, maxTok: Int, tokenOpen: Boolean, onToggleTok: () -> Unit,
 reqMode: String, onSetReqMode: (String) -> Unit,
 aiPromptIn: String, onPrompt: (String) -> Unit,
 aiAns: String, aiBusy: Boolean, hasEndHtml: Boolean,
 hasPendingCode: Boolean, canUndoCode: Boolean,
 onLaunch: (String, String) -> Unit, onStop: () -> Unit,
 onApplyRun: () -> Unit, onDiscardPending: () -> Unit, onUndoCode: () -> Unit, onEmptyWarn: (String) -> Unit
) {
 val focusReq = remember { FocusRequester() }
 Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
  WebHBar {
   ChipBtn("🔧현재코드 수정모드", reqMode == "MODIFY", CG) { onSetReqMode("MODIFY"); runCatching { focusReq.requestFocus() } }
   ChipBtn("✨새 웹앱 제작모드", reqMode == "NEW", CA) { onSetReqMode("NEW"); runCatching { focusReq.requestFocus() } }
   ChipBtn("🪙토큰:${maxTok}", tokenOpen, CA) { onToggleTok() }
   if (aiAns.contains("<") && !aiBusy && !hasEndHtml) ChipBtn("➕끊긴코드 이어쓰기", false, CW) { onLaunch("CONTINUE", "") }
   if (canUndoCode && !aiBusy) ChipBtn("↩️이전코드 복원", false, CA) { onUndoCode() }
  }
  if (hasPendingCode && !aiBusy) {
   Surface(color = CG.copy(alpha = 0.15f), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), SB, CV) {
     Text("✅ 요청하신 코드가 준비됨 · 확인 후 반영하세요", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
     Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
      ChipBtn("✅ 코드 반영&실행", true, CG) { onApplyRun() }
      ChipBtn("취소", false, CM) { onDiscardPending() }
     }
    }
   }
  }
  Surface(color = CS2, shape = RoundedCornerShape(9.dp), border = BorderStroke(0.8.dp, CBDR), modifier = Modifier.weight(1f).fillMaxWidth()) {
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
    val w = if (!aiBusy && aiAns.contains("<html", true) && !hasEndHtml) "⚠️ 코드가 </html> 전에 멈췄습니다. [➕끊긴코드 이어쓰기] 또는 토큰 상향을 이용해 보세요.\n\n" else ""
    val guide = if (reqMode == "MODIFY") {
     "🔧 [현재 코드 수정 모드] (최대 ${maxTok} 토큰)\n• 임의로 코드를 바꾸지 않습니다. 바꾸고 싶은 기능·디자인·버그 수정 사항을 아래 입력창에 직접 적고 [수정요청 ↑]을 눌러주세요.\n• 완료 후 [✅ 코드 반영&실행]을 눌러야 실제 코드에 적용됩니다."
    } else {
     "✨ [새 웹앱 제작 모드] (최대 ${maxTok} 토큰)\n• 새로 만들고 싶은 웹앱의 기능을 아래 입력창에 적고 [제작요청 ↑]을 눌러주세요."
    }
    Text(w + aiAns.ifBlank { guide }, fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = CW)
   }
  }
  Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
   OutlinedTextField(
    value = aiPromptIn,
    onValueChange = onPrompt,
    placeholder = { Text(if (reqMode == "MODIFY") "수정할 내용을 구체적으로 입력 후 [수정요청 ↑]..." else "새로 만들 웹앱 설명 입력 후 [제작요청 ↑]...", fontSize = ui.subSp) },
    singleLine = true,
    modifier = Modifier.weight(1f).focusRequester(focusReq)
   )
   if (aiBusy) Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) { Text("⏹️", fontSize = 12.sp) }
   else Button(
    onClick = {
     val q = aiPromptIn.trim()
     if (q.isEmpty()) {
      onEmptyWarn(if (reqMode == "MODIFY") "⚠️ 먼저 어떤 부분을 어떻게 수정할지 입력창에 적어주세요!" else "⚠️ 먼저 새로 만들 웹앱의 기능을 입력창에 적어주세요!")
      runCatching { focusReq.requestFocus() }
     } else {
      onPrompt("")
      onLaunch(reqMode, q)
     }
    },
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    colors = ButtonDefaults.buttonColors(containerColor = if (reqMode == "MODIFY") CG else CA, contentColor = CON_ACCENT)
   ) { Text(if (reqMode == "MODIFY") "수정요청 ↑" else "제작요청 ↑", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) }
  }
 }
}
