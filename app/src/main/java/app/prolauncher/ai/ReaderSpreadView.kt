package app.prolauncher.ai
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import kotlin.math.roundToInt

object ReaderLayoutCache {
    val pagesCache = object : LinkedHashMap<String, List<PageSlice>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PageSlice>>?): Boolean = size > 12
    }
}

private tailrec fun Context.findReaderHostActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findReaderHostActivity()
    else -> null
}

class RenderedBookPage(
    val annotated: AnnotatedString,
    val dispToRaw: IntArray,
    val footnotes: List<FootnoteSpan>
) {
    fun rawOffsetOf(dispOff: Int): Int {
        if (dispToRaw.isEmpty()) return 0
        return dispToRaw[dispOff.coerceIn(0, dispToRaw.lastIndex)]
    }
}

data class ActiveFootnotePopup(
    val numLabel: String,
    val rawToken: String,
    val noteBody: String?,
    val defCharOffset: Int,
    val contextSentence: String
)

fun buildWrappedHighlightedBookPage(
    rawText: String,
    colWPx: Int,
    fontSizePx: Float,
    curPt: Int,
    useSerif: Boolean,
    justifyOn: Boolean,
    paraIndentOn: Boolean,
    pageStartsAtPara: Boolean,
    continuesMidPara: Boolean,
    query: String,
    isNight: Boolean,
    pageStartOff: Int,
    bookHighlights: List<BookHighlight>
): RenderedBookPage {
    if (rawText.isEmpty()) return RenderedBookPage(AnnotatedString(""), intArrayOf(0), emptyList())
    val wpMap = EbookTextReflower.wrapPageWithIndentAndMap(
        rawText,
        colWPx,
        fontSizePx,
        useSerif,
        paraIndentOn,
        pageStartsAtPara,
        continuesMidPara
    )
    val wrapped = wpMap.display
    val rawToDisp = wpMap.rawToDisp
    val fnSpans = EbookTextReflower.findFootnoteSpans(rawText)
    val q = query.trim()
    val qRanges = if (q.isEmpty()) emptyList() else BookPassageSearcher.findMatchRangesInText(rawText, q, maxHits = 60)
    val pageEndOff = pageStartOff + rawText.length
    val pageHls = if (bookHighlights.isEmpty()) emptyList() else bookHighlights.filter { it.endOff > pageStartOff && it.startOff < pageEndOff }
    if (!justifyOn && fnSpans.isEmpty() && qRanges.isEmpty() && pageHls.isEmpty()) {
        return RenderedBookPage(AnnotatedString(wrapped), wpMap.dispToRaw, fnSpans)
    }
    fun mapRangeStart(rs0: Int): Int = rawToDisp[rs0.coerceIn(0, rawText.length)].coerceIn(0, wrapped.length)
    fun mapRangeEnd(re0: Int): Int {
        val safeE = re0.coerceIn(0, rawText.length)
        if (safeE <= 0) return 0
        return (rawToDisp[safeE - 1] + 1).coerceIn(0, wrapped.length)
    }
    val hlBg = if (isNight) Color(0x99D97706) else Color(0x88FACC15)
    val hlFg = if (isNight) Color(0xFFFFFBEB) else Color(0xFF1C1917)
    val fnColor = if (isNight) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val dispFnRanges = if (fnSpans.isEmpty()) emptyList() else fnSpans.map { fn ->
        Triple(mapRangeStart(fn.start), mapRangeEnd(fn.end), fn)
    }
    val built = buildAnnotatedString {
        append(wrapped)
        if (justifyOn && colWPx > 80 && fontSizePx >= 12f) {
            val paint = EbookTextReflower.createBookPaint(fontSizePx, useSerif)
            val justifyTargetW = (colWPx.toFloat() - fontSizePx * 0.40f).coerceAtLeast(60f)
            val n = wrapped.length
            var ls = 0
            while (ls < n) {
                var le = ls
                while (le < n && wrapped[le] != CH_NL) le++
                var effEnd = le
                while (effEnd > ls && wrapped[effEnd - 1] == ' ') effEnd--
                val isSoftWrapped = wpMap.softWrappedLineStarts.contains(ls)
                if (isSoftWrapped && (effEnd - ls) >= 6) {
                    var lineW = paint.measureText(wrapped, ls, effEnd)
                    for ((dfs, dfe, fn) in dispFnRanges) {
                        if (!fn.isUnicodeSup && !fn.isLineStartRef && dfe > ls && dfs < effEnd) {
                            val fs = maxOf(ls, dfs)
                            val fe = minOf(effEnd, dfe)
                            if (fe > fs) lineW -= paint.measureText(wrapped, fs, fe) * 0.36f
                        }
                    }
                    val slackPx = justifyTargetW - lineW
                    if (slackPx > 1.2f) {
                        val spaceIndices = ArrayList<Int>(6)
                        for (k in (ls + 1) until (effEnd - 1)) {
                            if (wrapped[k] == ' ') spaceIndices.add(k)
                        }
                        val interiorCnt = (effEnd - ls - 2).coerceAtLeast(1)
                        val spaceCnt = spaceIndices.size
                        val baseSpaceShare = if (spaceCnt > 0) minOf(slackPx * 0.58f, spaceCnt * fontSizePx * 0.56f) else 0f
                        val remAfterBase = (slackPx - baseSpaceShare).coerceAtLeast(0f)
                        val charBudget = minOf(remAfterBase, interiorCnt * fontSizePx * 0.28f)
                        val extraSpaceTopUp = if (spaceCnt > 0) minOf(remAfterBase - charBudget, spaceCnt * fontSizePx * 0.42f).coerceAtLeast(0f) else 0f
                        val totalSpacePx = baseSpaceShare + extraSpaceTopUp
                        val spaceAddPx = if (spaceCnt > 0) totalSpacePx / spaceCnt else 0f
                        val charAddPx = charBudget / interiorCnt
                        val charEm = charAddPx / fontSizePx
                        if (charEm >= 0.002f && (effEnd - 1) > (ls + 1)) {
                            addStyle(SpanStyle(letterSpacing = charEm.em), ls + 1, effEnd - 1)
                        }
                        if (spaceAddPx >= 0.3f) {
                            val spEm = ((charAddPx + spaceAddPx) / fontSizePx).em
                            for (spIdx in spaceIndices) {
                                addStyle(SpanStyle(letterSpacing = spEm), spIdx, spIdx + 1)
                            }
                        }
                    }
                }
                ls = le + 1
            }
        }
        for ((s, e, fn) in dispFnRanges) {
            if (s < e) {
                if (fn.isLineStartRef || fn.isUnicodeSup) {
                    addStyle(SpanStyle(fontSize = (curPt * 0.85f).sp, fontWeight = FontWeight.Bold, color = fnColor), s, e)
                } else {
                    addStyle(
                        SpanStyle(
                            fontSize = (curPt * 0.62f).sp,
                            baselineShift = BaselineShift(0.32f),
                            fontWeight = FontWeight.Bold,
                            color = fnColor
                        ),
                        s,
                        e
                    )
                }
            }
        }
        pageHls.forEach { h ->
            val rStart = (h.startOff - pageStartOff).coerceIn(0, rawText.length)
            val rEnd = (h.endOff - pageStartOff).coerceIn(rStart, rawText.length)
            if (rStart < rEnd) {
                val ls = mapRangeStart(rStart)
                val le = mapRangeEnd(rEnd).coerceAtLeast(ls)
                if (ls < le) {
                    val bgC = when (h.colorIdx) {
                        1 -> if (isNight) Color(0x6610B981) else Color(0x666EE7B7)
                        2 -> if (isNight) Color(0x66EC4899) else Color(0x66F9A8D4)
                        else -> if (isNight) Color(0x66F59E0B) else Color(0x77FDE047)
                    }
                    addStyle(SpanStyle(background = bgC, textDecoration = TextDecoration.Underline), ls, le)
                }
            }
        }
        qRanges.forEach { r ->
            val rStart = r.first.coerceIn(0, rawText.length)
            val rEnd = (r.last + 1).coerceIn(rStart, rawText.length)
            if (rStart < rEnd) {
                val s = mapRangeStart(rStart)
                val e = mapRangeEnd(rEnd).coerceAtLeast(s)
                if (s < e) addStyle(SpanStyle(background = hlBg, color = hlFg), s, e)
            }
        }
    }
    return RenderedBookPage(built, wpMap.dispToRaw, fnSpans)
}

@Composable
fun ReaderPointBtn(label: String, active: Boolean, accent: Color = CG, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (active) accent.copy(alpha = 0.22f) else CS2,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (active) 1.2.dp else 0.8.dp, if (active) accent else CBDR),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(Modifier.padding(vertical = 7.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) accent else CW,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

fun splitSpreadIntoSegments(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val sentenceSplit = Regex("(?<=[.!?。])\\s+|(?<=다\\.|요\\.|죠\\.|까\\?)\\s+")
    val out = mutableListOf<String>()
    for (ln0 in raw.lines()) {
        val ln = ln0.trim()
        if (ln.isEmpty()) continue
        val parts = if (ln.length <= 110) listOf(ln) else ln.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        for (p in parts) {
            if (p.length <= 170) {
                out.add(p)
            } else {
                var rem = p
                while (rem.length > 170) {
                    val cut = rem.lastIndexOf(' ', 160).takeIf { it >= 60 } ?: 160
                    out.add(rem.substring(0, cut).trim())
                    rem = rem.substring(cut).trim()
                }
                if (rem.isNotEmpty()) out.add(rem)
            }
        }
    }
    return if (out.isEmpty()) listOf(raw.trim()) else out
}

@Composable
fun ReaderView(
    books: List<BookItem>, cfg: LauncherConfig, repo: LauncherRepository,
    onSaveBooks: (List<BookItem>) -> Unit, onCfg: (LauncherConfig) -> Unit,
    onHome: () -> Unit, onBookNote: (String, String, Boolean) -> Unit = { _, _, _ -> },
    notes: List<NoteItem> = emptyList(), onUpdateNotes: (List<NoteItem>) -> Unit = {}
) {
    val ctx = LocalContext.current
    val aiCfg = remember { loadAiCfg(ctx) }
    val ui = rememberUi(aiCfg.uiScale)
    val scope = rememberCoroutineScope()
    val conf = LocalConfiguration.current
    val readerPrefs = remember { ctx.getSharedPreferences("pl_ai_v2_prefs", Context.MODE_PRIVATE) }
    EmojiHelpDialog()
    SideEffect { ReaderTtsRuntime.syncState(books, repo, onSaveBooks) }
    var twoPageMode by remember { mutableStateOf(readerPrefs.getBoolean("reader_two_page", false)) }
    var smartReflowOn by remember { mutableStateOf(readerPrefs.getBoolean("reader_smart_reflow", true)) }
    var readerJustifyOn by remember { mutableStateOf(readerPrefs.getBoolean("reader_justify_on", true)) }
    var readerParaIndentOn by remember { mutableStateOf(readerPrefs.getBoolean("reader_para_indent_on", true)) }
var readerSwipeTurnOn by remember { mutableStateOf(readerPrefs.getBoolean("reader_swipe_turn_on", true)) }
DisposableEffect(Unit) { onDispose { readerSwipePagerLock = false } }
    var bCats by remember { mutableStateOf(SmartMemoClassifier.loadBookCats(ctx)) }
    var selCat by remember { mutableStateOf("전체") }
    var catBarOpen by remember { mutableStateOf(false) }
    var folderDialogOpen by remember { mutableStateOf(false) }
    val allCats = remember(bCats, books) { (listOf("전체") + (bCats + books.map { it.category.ifBlank { "기타" } }).distinct()) }
    var bms by remember { mutableStateOf(loadBookmarks(ctx)) }
var allHighlights by remember { mutableStateOf(loadBookHighlights(ctx)) }
var highlightMode by remember { mutableStateOf(false) }
    var hlColorIdx by remember { mutableStateOf(0) }
    var hlListOpen by remember { mutableStateOf(false) }
        var tocOpen by remember { mutableStateOf(false) }
        var tocFilterQuery by remember { mutableStateOf("") }
    var lineHeightIdx by remember { mutableStateOf(readerPrefs.getInt("reader_line_height_idx", 1).coerceIn(0, 2)) }
    // Built-in Reader Memo & AI Floating states — exact MarkdownWriterStudio free-drag MutableState architecture!
    var readerMemoOpen by remember { mutableStateOf(false) }
var readerMemoMode by remember { mutableStateOf(readerPrefs.getInt("reader_memo_last_mode", 1).coerceIn(1, 2)) }
var memoSplitHoriz by remember { mutableStateOf(readerPrefs.getBoolean("reader_memo_split_horiz", false)) }
var memoFloatMin by remember { mutableStateOf(false) }
var memoFloatCompact by remember { mutableStateOf(readerPrefs.getBoolean("reader_memo_compact", false)) }
val memoFloatXState = remember { mutableStateOf(readerPrefs.getFloat("reader_memo_fx", 16f)) }
val memoFloatYState = remember { mutableStateOf(readerPrefs.getFloat("reader_memo_fy", 108f)) }
var readerAiMode by remember { mutableStateOf(readerPrefs.getInt("reader_ai_last_mode", 1).coerceIn(1, 2)) }
var aiFloatMin by remember { mutableStateOf(false) }
var aiFloatCompact by remember { mutableStateOf(readerPrefs.getBoolean("reader_ai_compact", false)) }
val aiFloatXState = remember { mutableStateOf(readerPrefs.getFloat("reader_ai_fx", 20f)) }
val aiFloatYState = remember { mutableStateOf(readerPrefs.getFloat("reader_ai_fy", 240f)) }
var livePgLabel by remember { mutableStateOf("1쪽") }
var liveSpreadText by remember { mutableStateOf("") }
var liveActiveExcerpt by remember { mutableStateOf("") }
var commitExcerptHook by remember { mutableStateOf<() -> String>({ "" }) }
var pendingMemoExcerpt by remember { mutableStateOf<String?>(null) }
var pendingReaderExportFolder by remember { mutableStateOf<String?>(null) }
var readerBackupMsg by remember { mutableStateOf<String?>(null) }
fun setReaderMemoModePersist(mode: Int, horiz: Boolean = memoSplitHoriz) {
val m = mode.coerceIn(1, 2)
readerMemoMode = m; memoSplitHoriz = horiz; memoFloatMin = false
readerPrefs.edit().putInt("reader_memo_last_mode", m).putBoolean("reader_memo_split_horiz", horiz).apply()
}
fun setReaderAiModePersist(mode: Int) {
val m = mode.coerceIn(1, 2)
readerAiMode = m; aiFloatMin = false
readerPrefs.edit().putInt("reader_ai_last_mode", m).apply()
}
fun openReaderMemoInLastMode(appendExcerpt: String? = null) {
if (!appendExcerpt.isNullOrBlank()) pendingMemoExcerpt = appendExcerpt
memoFloatMin = false; readerMemoOpen = true
}
var bmOpen by remember { mutableStateOf(false) }
var selId by remember {
val savedId = readerPrefs.getLong("reader_last_book_id", -1L)
val initId = books.find { it.id == savedId }?.id ?: books.firstOrNull()?.id ?: 1001L
mutableStateOf(initId)
}
LaunchedEffect(selId) { readerPrefs.edit().putLong("reader_last_book_id", selId).apply() }
var pendingJump by remember { mutableStateOf<Pair<Long, Int>?>(null) }
var shelf by remember { mutableStateOf(false) }
var cfgOpen by remember { mutableStateOf(false) }
var rCfgTab by remember { mutableStateOf(0) }
var ttsBarMin by remember { mutableStateOf(false) }
var sliderOpen by remember { mutableStateOf(false) }
var searchOpen by remember { mutableStateOf(false) }
var searchQuery by remember { mutableStateOf("") }
var searchAllBooks by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(readerPrefs.getString("reader_book_sort", "CUSTOM") ?: "CUSTOM") }
    var reorderMode by remember { mutableStateOf(false) }
    var bookQuery by remember { mutableStateOf("") }
    var newCat by remember { mutableStateOf("") }
    var bookMeta by remember { mutableStateOf(loadBookMetaMap(ctx)) }
    LaunchedEffect(LauncherBackupManager.backupRev) {
        if (LauncherBackupManager.backupRev > 0) {
            bCats = SmartMemoClassifier.loadBookCats(ctx)
            bms = loadBookmarks(ctx)
            bookMeta = loadBookMetaMap(ctx)
            onSaveBooks(repo.loadBooks())
            onUpdateNotes(repo.loadNotes())
        }
    }
    val readerBackupExp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val flt = pendingReaderExportFolder
            scope.launch(Dispatchers.IO) {
                val msg = runCatching {
                    val json = LauncherBackupManager.exportBackupJson(ctx, repo, "READER", flt)
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    "✅ 독서대 백업 저장 완료 (${if (flt == null) "전체 도서·책갈피" else "[$flt] 폴더"})"
                }.getOrElse { "⚠️ 독서대 백업 실패: ${it.message}" }
                withContext(Dispatchers.Main) { readerBackupMsg = msg; ReaderTtsRuntime.status = msg }
            }
        }
    }
    val readerBackupImp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val raw = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" }.getOrDefault("")
                val msg = withContext(Dispatchers.Main) { LauncherBackupManager.importAndMergeBackupJson(ctx, repo, raw) }
                withContext(Dispatchers.Main) { readerBackupMsg = msg; ReaderTtsRuntime.status = msg }
            }
        }
    }
    fun triggerReaderBackup(folderFlt: String? = null) {
        pendingReaderExportFolder = folderFlt
        val fn = if (folderFlt.isNullOrBlank()) "prolauncher_reader_backup.json" else "prolauncher_reader_${folderFlt}.json"
        readerBackupExp.launch(fn)
    }
    fun metaOf(id: Long, defOrd: Int = 0) = bookMeta[id] ?: BookSortMeta(sortOrder = defOrd)
    fun setBookSort(m: String) { sortMode = m; readerPrefs.edit().putString("reader_book_sort", m).apply() }
    fun toggleBookPin(targetId: Long) { val mp = bookMeta.toMutableMap(); val pr = mp[targetId] ?: BookSortMeta(); mp[targetId] = pr.copy(pinned = !pr.pinned); bookMeta = mp; saveBookMetaMap(ctx, mp) }
    fun markBookRead(targetId: Long, off: Int? = null) { bookMeta = touchBookReadAt(ctx, targetId, off) }
    fun moveBookOrder(targetId: Long, actType: Int) {
        val cur = books.mapIndexed { idx, bk -> idx to bk }.sortedWith(Comparator { p1, p2 ->
            val m1 = metaOf(p1.second.id, p1.first); val m2 = metaOf(p2.second.id, p2.first)
            if (m1.pinned != m2.pinned) (if (m1.pinned) -1 else 1) else m1.sortOrder.compareTo(m2.sortOrder).let { if (it != 0) it else p1.first.compareTo(p2.first) }
        }).map { it.second }.toMutableList()
        val idx0 = cur.indexOfFirst { it.id == targetId }; if (idx0 < 0) return
        val item = cur.removeAt(idx0)
        val dst = when (actType) { -2 -> 0; -1 -> (idx0 - 1).coerceAtLeast(0); 1 -> (idx0 + 1).coerceAtMost(cur.size); else -> cur.size }
        cur.add(dst, item)
        val mp = bookMeta.toMutableMap()
        cur.forEachIndexed { i, bk -> val pr = mp[bk.id] ?: BookSortMeta(); mp[bk.id] = pr.copy(sortOrder = i) }
        bookMeta = mp; saveBookMetaMap(ctx, mp); setBookSort("CUSTOM")
    }
    var readerAiOpen by remember { mutableStateOf(false) }
    var readerAiQ by remember { mutableStateOf("") }
    var readerAiAns by remember { mutableStateOf("") }
    var readerAiBusy by remember { mutableStateOf(false) }
    var readerAiNotice by remember { mutableStateOf<String?>(null) }
    var readerAiJob by remember { mutableStateOf<Job?>(null) }
    val act = books.find { it.id == selId } ?: books.firstOrNull()
    fun askPageAi(askP: String, bT: String, pLabel: String, pTx: String, isPart: Boolean) {
        if (pTx.isBlank()) return
        readerAiJob?.cancel(); if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }
        val g = ++activeGenId; readerAiBusy = true; readerAiNotice = null
        val tag = if (isPart) "선택 구절(${pTx.length}자)" else "${pLabel} 본문"
        readerAiAns = "⚡ [$tag] 실시간 분석 중... ▍"
        readerAiJob = scope.launch {
            val cc = loadAiCfg(ctx)
            val hdr = if (isPart) "도서: 『$bT』 ($pLabel 중 선택 구절)\n[선택 구절]\n${pTx.take(1200)}" else "도서: 『$bT』 ($pLabel)\n[본문]\n${pTx.take(1200)}"
            val ans = withContext(Dispatchers.IO) {
                callLlmHttp("도서 본문만을 근거로 한국어로 명쾌하게 해설하세요.", "", emptyList(), "$hdr\n\n[요청]\n$askP", cc.apiUrl, cc.apiModel, cc.apiKey, { lv ->
                    if (g == activeGenId && lv.isNotBlank()) uiP { readerAiAns = lv + " ▍" }
                }, g, cc.temp, cc.ctxSize, cc.maxTok.coerceAtLeast(512), cc.cpuMode, cc.histTurns)
            }
            if (g == activeGenId) {
                readerAiAns = (ans ?: "").ifBlank { readerAiAns.removeSuffix(" ▍").ifBlank { "⚠️ 해설을 생성하지 못했습니다." } }
                readerAiBusy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch(Dispatchers.IO) {
            repo.importBookFromUri(u)?.let { b0 ->
                val b = if (selCat != "전체") b0.copy(category = selCat) else b0
                withContext(Dispatchers.Main) { onSaveBooks(listOf(b) + books); selId = b.id; shelf = false }
            }
        }
    }
    val bg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFFF4ECD8); "PAPER" -> Color(0xFFFAF8F5); else -> Color(0xFF07090E) }
    val pageSurf = when (cfg.readerTheme) { "SEPIA" -> Color(0xFFF8F1DF); "PAPER" -> Color(0xFFFFFFFF); else -> Color(0xFF0C111B) }
    val fg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF2C221E); "PAPER" -> Color(0xFF1C1917); else -> Color(0xFFE5E7EB) }
    val mut = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF6E5A4F); "PAPER" -> Color(0xFF78716C); else -> CM }
    val spineBase = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF3E2D21); "PAPER" -> Color(0xFF292524); else -> Color(0xFF000000) }
    val curPt = cfg.readerFontSizeSp.coerceIn(12, 32)
    val topTtsVisible = ReaderTtsRuntime.on && !ttsBarMin
    val topStatusVisible = !ReaderTtsRuntime.on && ReaderTtsRuntime.status?.startsWith("✅") == true

    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
val rootBoxW = this.maxWidth
val rootBoxH = this.maxHeight
val density = LocalDensity.current
val rootMaxWPx = with(density) { rootBoxW.toPx() }
val rootMaxHPx = with(density) { rootBoxH.toPx() }
val memoFloatW = if (memoFloatMin) 190.dp else if (memoFloatCompact) (rootBoxW * 0.66f).coerceIn(210.dp, 290.dp) else (rootBoxW * 0.84f).coerceIn(245.dp, 360.dp)
val memoFloatH = if (memoFloatMin) 44.dp else (rootBoxH * 0.46f).coerceIn(225.dp, 340.dp)
val memoFloatWPx = with(density) { memoFloatW.toPx() }
val memoFloatHPx = with(density) { memoFloatH.toPx() }
val aiFloatW = if (aiFloatMin) 190.dp else if (aiFloatCompact) (rootBoxW * 0.66f).coerceIn(210.dp, 290.dp) else (rootBoxW * 0.84f).coerceIn(245.dp, 360.dp)
val aiFloatH = if (aiFloatMin) 44.dp else (rootBoxH * 0.45f).coerceIn(220.dp, 335.dp)
val aiFloatWPx = with(density) { aiFloatW.toPx() }
val aiFloatHPx = with(density) { aiFloatH.toPx() }
val readerOuterHPad = (ui.hPad * 0.55f).coerceAtLeast(6.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = readerOuterHPad, vertical = ui.vPad)) {
            val renderReaderTopBar: @Composable (@Composable RowScope.() -> Unit) -> Unit = { centerSlot ->
Row(
Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
horizontalArrangement = Arrangement.spacedBy(4.dp),
verticalAlignment = CV
) {
                    TextButton(onClick = { shelf = !shelf; bmOpen = false }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp), modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)) { Text(if (shelf) "📖" else "📚${books.size}", fontSize = ui.bodySp, color = fg, fontWeight = FontWeight.Bold, softWrap = false) }
                       OutlinedButton(onClick = { bmOpen = !bmOpen; if (bmOpen) shelf = false }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp), modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 32.dp)) { Text("🔖${bms.size}", fontSize = ui.subSp, color = if (bmOpen) CA else fg, softWrap = false) }
                    OutlinedButton(onClick = { twoPageMode = !twoPageMode; readerPrefs.edit().putBoolean("reader_two_page", twoPageMode).apply() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp), modifier = Modifier.defaultMinSize(minWidth = 34.dp, minHeight = 32.dp)) { Text(if (twoPageMode) "📖" else "📄", fontSize = ui.subSp, color = if (twoPageMode) CG else fg, softWrap = false) }
                    centerSlot())
                    OutlinedButton(
                        onClick = {
                            if (!searchOpen) searchAllBooks = shelf || act == null
                            searchOpen = !searchOpen
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("🔎", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (searchOpen || searchQuery.isNotBlank()) CA else fg, softWrap = false) }
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
                    ) { Text("📂", fontSize = ui.subSp, color = fg, softWrap = false) }
                    OutlinedButton(
                        onClick = { cfgOpen = !cfgOpen },
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
                    ) { Text("⚙️${curPt}", fontSize = ui.subSp, color = if (cfgOpen) CG else fg, softWrap = false) }
                    TextButton(onClick = onHome, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) { Text("🏠", fontSize = (ui.subSp.value + 1).sp, color = mut, softWrap = false) }
                }
            }
        if (shelf || act == null) { renderReaderTopBar {} }
if (searchOpen && (shelf || act == null)) {
val actPreviewFull = remember(act?.id, act?.pages, smartReflowOn) { act?.let { buildBookReflowSource(it.pages, smartReflow = smartReflowOn, preservePoetry = it.category.contains("시")).fullText } ?: "" }
ReaderUnifiedSearchBar(
books = books, activeBook = act, activeBookFullText = actPreviewFull, activeBookPages = emptyList(), activeCurVIdx = 0, smartReflow = smartReflowOn,
query = searchQuery, onQueryChange = { searchQuery = it }, scopeAllBooks = true, onScopeChange = { searchAllBooks = it }, ui = ui,
onSelectHit = { hit -> selId = hit.bookId; shelf = false; bmOpen = false; searchAllBooks = false; markBookRead(hit.bookId, hit.charOffset); pendingJump = hit.bookId to hit.charOffset; searchOpen = false },
onClose = { searchOpen = false }
)
}
        if (topTtsVisible) {
            SideEffect { SupertonicTtsEngine.init(ctx) }
            val bNow = books.find { it.id == ReaderTtsRuntime.bookId }
            Surface(color = CS, shape = RoundedCornerShape(50), border = BorderStroke(0.8.dp, CG.copy(alpha = 0.7f)), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), SB, CV) {
                    Text("🔊 ${bNow?.title?.take(10) ?: "도서"} ${ReaderTtsRuntime.pageIdx + 1}/${bNow?.pages?.size ?: 1} · ${String.format(java.util.Locale.US, "%.2fx", SupertonicTtsEngine.speed)} · 💎${SupertonicTtsEngine.steps}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                        Text("➖", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() - 1).coerceIn(12, 36) / 20f) })
                        Text("➕", fontSize = 11.sp, color = CA, modifier = Modifier.clickable { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() + 1).coerceIn(12, 36) / 20f) })
                        Text("🎛️", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { rCfgTab = 1; cfgOpen = !cfgOpen })
                        Text("🔽숨김", fontSize = 10.sp, color = CM, modifier = Modifier.clickable { ttsBarMin = true })
                        Text("⏹️", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF87171), modifier = Modifier.clickable { ReaderTtsRuntime.stop(ctx) })
                    }
                }
            }
        } else if (topStatusVisible) {
            Surface(color = CS, shape = RoundedCornerShape(8.dp), border = BorderStroke(0.8.dp, CA), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), SB, CV) {
                    Text(ReaderTtsRuntime.status ?: "", fontSize = ui.subSp, color = CA, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { ReaderTtsRuntime.status = null }.padding(start = 8.dp))
                }
            }
        }
        if (bmOpen) {
            Surface(color = CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(max = ui.panelMaxH)) {
                Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), SB, CV) { Text("🔖 북마크 목록", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA); Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { bmOpen = false }) }
                    if (bms.isEmpty()) Text("저장된 북마크가 없습니다.", fontSize = ui.subSp, color = CM)
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(bms, key = { "${it.bookId}_${it.pageIndex}_${it.charOffset}" }) { bm ->
                            Surface(color = CS, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
                                val targetB = books.find { it.id == bm.bookId }
                                val targetOff = if (bm.charOffset >= 0) bm.charOffset else targetB?.let { buildBookReflowSource(it.pages).rawStartOf(bm.pageIndex) } ?: 0
                                markBookRead(bm.bookId, targetOff)
                                selId = bm.bookId
                                pendingJump = bm.bookId to targetOff
                                onSaveBooks(books.map { if (it.id == bm.bookId) it.copy(currentPage = bm.pageIndex) else it })
                                if (ReaderTtsRuntime.on && ReaderTtsRuntime.bookId == bm.bookId) ReaderTtsRuntime.jumpToPage(ctx, bm.pageIndex)
                                shelf = false; bmOpen = false
                            }) {
                                Row(Modifier.padding(10.dp), SB, CV) {
                                    Column(Modifier.weight(1f)) {
                                        Text("🔖 ${bm.bookTitle} · 섹션 ${bm.pageIndex + 1}", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW)
                                        Text(bm.snippet, fontSize = ui.subSp, color = CM, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("✕", fontSize = (ui.subSp.value + 1).sp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
                                        val nx = bms.filterNot { it.bookId == bm.bookId && it.pageIndex == bm.pageIndex && it.charOffset == bm.charOffset }
                                        bms = nx; saveBookmarks(ctx, nx)
                                    }.padding(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cfgOpen) {
            SideEffect { SupertonicTtsEngine.init(ctx) }
            Surface(
                color = CS,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CG.copy(alpha = 0.75f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(max = ui.panelMaxH + 110.dp)
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), SB, CV) {
                        Text("⚙️ 독서대 전용 설정", fontSize = (ui.subSp.value + 1.5f).sp, fontWeight = FontWeight.Bold, color = CW)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) { Text("❓도움말", fontSize = ui.subSp, color = CA, modifier = Modifier.clickable { showEmojiHelp = true }.padding(4.dp)); Text("✕ 닫기", fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.clickable { cfgOpen = false }.padding(4.dp)) }
                    }
                    SettingsTabBar(listOf("📖 화면·글꼴", "🔊 TTS 낭독", "📁 폴더·백업"), rCfgTab) { rCfgTab = it }
                    when (rCfgTab) {
                        0 -> {
                            Surface(color = CS2, shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, CBDR), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("구글북 스타일 연속 페이지 조판 & 테마", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        ReaderPointBtn("📄 1쪽 보기", !twoPageMode, CG, Modifier.weight(1f)) {
                                            twoPageMode = false
                                            readerPrefs.edit().putBoolean("reader_two_page", false).apply()
                                        }
                                        ReaderPointBtn("📖 2쪽 연속 펼침", twoPageMode, CG, Modifier.weight(1f)) {
                                            twoPageMode = true
                                            readerPrefs.edit().putBoolean("reader_two_page", true).apply()
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        listOf("SEPIA" to "📜 세피아", "PAPER" to "📄 종이", "NIGHT" to "🌙 야간").forEach { (k, l) ->
                                            ReaderPointBtn(l, cfg.readerTheme == k, CA, Modifier.weight(1f)) { onCfg(cfg.copy(readerTheme = k)) }
                                        }
                                    }
                                    Box(Modifier.fillMaxWidth().height(0.8.dp).background(CBDR))
                                    Row(Modifier.fillMaxWidth(), SB, CV) {
                                        Text("🔤 글자 포인트: ${curPt}pt", fontSize = (ui.subSp.value + 1f).sp, fontWeight = FontWeight.Bold, color = CW, modifier = Modifier.weight(1f))
                                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) { ChipBtn(if (smartReflowOn) "✨줄바꿈보정" else "📄원문줄바꿈", smartReflowOn, CA) { smartReflowOn = !smartReflowOn; readerPrefs.edit().putBoolean("reader_smart_reflow", smartReflowOn).apply() }; ChipBtn(if (cfg.readerUseSerif) "✒️ 명조체" else "🔤 고딕체", true, CG) { onCfg(cfg.copy(readerUseSerif = !cfg.readerUseSerif)) } }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        ReaderPointBtn("➖ 2pt", false, CW, Modifier.weight(1f)) { onCfg(cfg.copy(readerFontSizeSp = (curPt - 2).coerceAtLeast(12))) }
                                        ReaderPointBtn("➖ 1pt", false, CW, Modifier.weight(1f)) { onCfg(cfg.copy(readerFontSizeSp = (curPt - 1).coerceAtLeast(12))) }
                                        ReaderPointBtn("➕ 1pt", false, CA, Modifier.weight(1f)) { onCfg(cfg.copy(readerFontSizeSp = (curPt + 1).coerceAtMost(32))) }
                                        ReaderPointBtn("➕ 2pt", false, CA, Modifier.weight(1f)) { onCfg(cfg.copy(readerFontSizeSp = (curPt + 2).coerceAtMost(32))) }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        listOf(12, 14, 16, 18).forEach { pt ->
                                            ReaderPointBtn("${pt}pt", curPt == pt, CG, Modifier.weight(1f)) { onCfg(cfg.copy(readerFontSizeSp = pt)) }
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        listOf(20, 22, 25, 28).forEach { pt ->
                                            ReaderPointBtn("${pt}pt", curPt == pt, CG, Modifier.weight(1f)) { onCfg(cfg.copy(readerFontSizeSp = pt)) }
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        ReaderPointBtn("📐 양쪽정렬 (도서형)", readerJustifyOn, CG, Modifier.weight(1f)) {
                                            readerJustifyOn = true
                                            readerPrefs.edit().putBoolean("reader_justify_on", true).apply()
                                        }
                                        ReaderPointBtn("⬅️ 왼쪽정렬", !readerJustifyOn, CG, Modifier.weight(1f)) {
                                            readerJustifyOn = false
                                            readerPrefs.edit().putBoolean("reader_justify_on", false).apply()
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        ReaderPointBtn(
                                            if (readerParaIndentOn) "⇥ 첫줄들여쓰기 ON" else "⇤ 첫줄들여쓰기 OFF",
                                            readerParaIndentOn,
                                            CG,
                                            Modifier.weight(1f)
                                        ) {
                                            readerParaIndentOn = !readerParaIndentOn
                                            readerPrefs.edit().putBoolean("reader_para_indent_on", readerParaIndentOn).apply()
                                        }
                                        ReaderPointBtn(if (readerSwipeTurnOn) "👆 스와이프 넘김 ON" else "👆 스와이프 넘김 OFF", readerSwipeTurnOn, CG, Modifier.weight(1f)) { readerSwipeTurnOn = !readerSwipeTurnOn; readerPrefs.edit().putBoolean("reader_swipe_turn_on", readerSwipeTurnOn).apply() }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        listOf(0 to "줄간격 좁게", 1 to "줄간격 보통", 2 to "줄간격 넓게").forEach { (idx, lbl) ->
                                            ReaderPointBtn(lbl, lineHeightIdx == idx, CA, Modifier.weight(1f)) {
                                                lineHeightIdx = idx
                                                readerPrefs.edit().putInt("reader_line_height_idx", idx).apply()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            Surface(color = CS2, shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, CBDR), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("음성 엔진 & 초경량 모델 받기 (화면 꺼져도 연속 낭독 유지)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
                                    Text(SupertonicTtsEngine.statusText, fontSize = ui.subSp, color = CM)
                                    SupertonicTtsEngine.dlProgress?.let { dp -> Text(dp, fontSize = ui.subSp, color = CG) }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        ChipBtn(if (SupertonicTtsEngine.enabled) "🔊 Supertonic ON" else "📱 기본 TTS", SupertonicTtsEngine.enabled, CG) { SupertonicTtsEngine.setMode(ctx, !SupertonicTtsEngine.enabled) }
                                        ChipBtn("⬇️🪶 INT8 초경량(105MB)", SupertonicTtsEngine.modelTier == "INT8", CG) { SupertonicTtsEngine.downloadSupertonicTier(ctx, "INT8") }
                                        ChipBtn("⬇️⚡ FP16(195MB)", SupertonicTtsEngine.modelTier == "FP16", CA) { SupertonicTtsEngine.downloadSupertonicTier(ctx, "FP16") }
                                        ChipBtn("⬇️👑 FP32(398MB)", SupertonicTtsEngine.modelTier == "FP32", CW) { SupertonicTtsEngine.downloadSupertonicTier(ctx, "FP32") }
                                    }
                                    Box(Modifier.fillMaxWidth().height(0.8.dp).background(CBDR))
                                    SupertonicControlSection(ctx, ui, compact = false)
                                }
                            }
                        }
                        else -> {
                            Surface(color = CS2, shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, CBDR), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), SB, CV) {
                                        Text("📁 독서대 폴더 편집 및 도서 백업/복원", fontSize = ui.subSp, color = CA, fontWeight = FontWeight.Bold)
                                        Text("⚙️상세폴더관리", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable { folderDialogOpen = true })
                                    }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        bCats.forEach { c -> ChipBtn(if (bCats.size > 1) "📁$c ✕" else "📁$c") { if (bCats.size > 1) { val u = bCats.filterNot { it == c }; bCats = u; SmartMemoClassifier.saveBookCats(ctx, u); if (selCat == c) selCat = "전체" } } }
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 독서대 폴더 이름 (예: 전공PDF, 소설)", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
                                        Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !bCats.contains(t)) { val u = bCats + t; bCats = u; SmartMemoClassifier.saveBookCats(ctx, u); newCat = "" } }) { Text("➕", fontSize = ui.subSp) }
                                    }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                        ChipBtn("📤 전체 도서·책갈피 백업(.json)", true, CG) { triggerReaderBackup(null) }
                                        if (selCat != "전체") ChipBtn("📤 [$selCat] 폴더만 백업", false, CW) { triggerReaderBackup(selCat) }
                                        ChipBtn("♻️ 도서 병합 복원(기존 유지+추가)", false, CA) { readerBackupImp.launch(arrayOf("*/*")) }
                                    }
                                    readerBackupMsg?.let { Text(it, fontSize = ui.subSp, color = CG) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (shelf || act == null) {
            SideEffect {
                readerSwipePagerLock = false
            }
            ReaderShelfSection(
                ctx = ctx, ui = ui, books = books, bCats = bCats, allCats = allCats,
                selCat = selCat, onSelCat = { selCat = it }, onOpenCatCfg = { folderDialogOpen = true },
                cfgOpen = cfgOpen, rCfgTab = rCfgTab, sortMode = sortMode,
                onSetSort = { setBookSort(it); if (it != "CUSTOM") reorderMode = false },
                reorderMode = reorderMode, onToggleReorder = { reorderMode = !reorderMode; if (reorderMode) setBookSort("CUSTOM") },
                bookQuery = bookQuery, onBookQuery = { bookQuery = it }, bookMeta = bookMeta, bms = bms,
                fg = fg, mut = mut, onSaveBooks = onSaveBooks,
                onOpenBook = { bk, withTts -> markBookRead(bk.id); selId = bk.id; shelf = false; if (withTts) { ttsBarMin = false; ReaderTtsRuntime.start(ctx, bk, bk.currentPage) } },
                onTogglePin = { toggleBookPin(it) }, onMoveOrder = { bid, actT -> moveBookOrder(bid, actT) },
                onDeleteBook = { bid -> val nx = books.filterNot { it.id == bid }; onSaveBooks(nx); if (selId == bid) selId = nx.firstOrNull()?.id ?: 0L }, onBackupReader = { flt -> triggerReaderBackup(flt) }, onRestoreReader = { readerBackupImp.launch(arrayOf("*/*")) },
                onOpenBookAtOffset = { bk, off, q ->
                    searchQuery = q
                    selId = bk.id
                    shelf = false
                    markBookRead(bk.id, off)
                    pendingJump = bk.id to off
                }
            )
        } else {
            val isPoetryBook = act.category.contains("시"); val reflowSrc = remember(act.id, act.pages, smartReflowOn, isPoetryBook) { buildBookReflowSource(act.pages, smartReflow = smartReflowOn, preservePoetry = isPoetryBook) }
            val ttsHere = ReaderTtsRuntime.on && ReaderTtsRuntime.bookId == act.id
            var curCharOff by remember(act.id) {
                val latestSaved = loadBookMetaMap(ctx)[act.id]?.charOffset ?: bookMeta[act.id]?.charOffset ?: -1
                val initOff = if (latestSaved in 0..reflowSrc.fullText.length) latestSaved else reflowSrc.rawStartOf(act.currentPage)
                mutableStateOf(initOff)
            }
            LaunchedEffect(pendingJump, act.id) {
                val pj = pendingJump
                if (pj != null && pj.first == act.id) {
                    curCharOff = pj.second.coerceIn(0, reflowSrc.fullText.length)
                    pendingJump = null
                }
            }
            LaunchedEffect(ttsHere, ReaderTtsRuntime.pageIdx) {
                if (ttsHere) {
                    val s0 = reflowSrc.rawStartOf(ReaderTtsRuntime.pageIdx)
                    val e0 = reflowSrc.rawEndOf(ReaderTtsRuntime.pageIdx)
                    if (curCharOff < s0 || curCharOff >= e0) {
                        curCharOff = s0
                        markBookRead(act.id, s0)
                    }
                }
            }
            val lineHeightMult = when (lineHeightIdx) { 0 -> 1.38f; 2 -> 1.78f; else -> 1.58f }
            val fontSizePx = with(density) { curPt.sp.toPx() }
            val lineHeightPx = with(density) { (curPt * lineHeightMult).sp.toPx() }
            val pageTextStyle = remember(curPt, cfg.readerUseSerif, fg, lineHeightMult) {
                TextStyle(
                    fontSize = curPt.sp,
                    lineHeight = (curPt * lineHeightMult).sp,
                    lineHeightStyle = LineHeightStyle(
                         alignment = LineHeightStyle.Alignment.Center,
                         trim = LineHeightStyle.Trim.Both
                     ),
                    fontFamily = if (cfg.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif,
                    lineBreak = LineBreak.Simple,
                    hyphens = Hyphens.None,
                    color = fg
                )
            }
            val realDm = remember(conf.orientation) {
                val dm = android.util.DisplayMetrics()
                runCatching {
                    val dispMgr = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                    @Suppress("DEPRECATION")
                    dispMgr?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
                }
                if (dm.widthPixels <= 0 || dm.heightPixels <= 0) dm.setTo(ctx.resources.displayMetrics)
                dm
            }
            val isPhysicalLandscape = realDm.widthPixels > realDm.heightPixels
            val realFullWPx = realDm.widthPixels.toFloat()
            val realFullHPx = realDm.heightPixels.toFloat()
            val hostAct = ctx.findReaderHostActivity() ?: mainActRef
            val inMultiWindow = hostAct?.isInMultiWindowMode == true
            val imeBottomPx = WindowInsets.ime.getBottom(density)
            val isKeyboardOpen = imeBottomPx > with(density) { 80.dp.toPx() }
            val isWindowShrunk = inMultiWindow ||
                (realFullHPx > 400f && rootMaxHPx < realFullHPx * 0.80f) ||
                (realFullWPx > 300f && rootMaxWPx < realFullWPx * 0.85f)
            val fsWKey = "reader_fs_w_${isPhysicalLandscape}_${twoPageMode}"
            val fsHKey = "reader_fs_h_${isPhysicalLandscape}_${twoPageMode}"
            val outerPad = if (twoPageMode) 10.dp else 12.dp
            val spineW = if (twoPageMode) 20.dp else 0.dp
            val measuredBookWPxState = remember(isPhysicalLandscape, twoPageMode) { mutableStateOf(readerPrefs.getFloat(fsWKey, 0f)) }
            val measuredTextHPxState = remember(isPhysicalLandscape, twoPageMode) { mutableStateOf(readerPrefs.getFloat(fsHKey, 0f)) }
            val cardInnerChromePx = with(density) { 45.dp.toPx() }
            val outerPadPx = with(density) { outerPad.toPx() }
            val spineWPx = with(density) { spineW.toPx() }
            val fallbackFullBookWPx = (realFullWPx - with(density) { (readerOuterHPad * 2).toPx() }).coerceAtLeast(220f)
            val fallbackFullTextHPx = (realFullHPx - with(density) { (ui.vPad * 2 + 216.dp).toPx() }).coerceAtLeast(180f)
            val activeBookWPx = if (measuredBookWPxState.value >= 200f) measuredBookWPxState.value else fallbackFullBookWPx
            val fixedColWPx = (if (twoPageMode) ((activeBookWPx - outerPadPx * 2f - spineWPx) / 2f) else (activeBookWPx - outerPadPx * 2f)).roundToInt().coerceAtLeast(80)
            val fixedTextHPx = if (measuredTextHPxState.value >= 120f) measuredTextHPxState.value else fallbackFullTextHPx
            val fixedTextH = with(density) { fixedTextHPx.toDp() }.coerceAtLeast(180.dp)

            val effectiveIndentOn = readerParaIndentOn && !isPoetryBook
            val pageCacheKey = "v6ka_${act.id}_${reflowSrc.fullText.length}_${reflowSrc.fullText.hashCode()}_${fixedColWPx}_${fixedTextHPx.roundToInt()}_${curPt}_${cfg.readerUseSerif}_${lineHeightIdx}_${smartReflowOn}_${effectiveIndentOn}"
            var lastStablePages by remember(act.id) {
                val prior = ReaderLayoutCache.pagesCache.entries.lastOrNull { it.key.startsWith("v6ka_${act.id}_") }?.value
                    ?: EbookTextReflower.paginateBookKeepAll(reflowSrc.fullText.take(6000), fixedColWPx, fixedTextHPx, fontSizePx, lineHeightPx, cfg.readerUseSerif, effectiveIndentOn)
                mutableStateOf(prior)
            }
            val syncPages = remember(pageCacheKey) {
                ReaderLayoutCache.pagesCache[pageCacheKey] ?: if (reflowSrc.fullText.length <= 120000) {
                    EbookTextReflower.paginateBookKeepAll(reflowSrc.fullText, fixedColWPx, fixedTextHPx, fontSizePx, lineHeightPx, cfg.readerUseSerif, effectiveIndentOn)
                        .also { ReaderLayoutCache.pagesCache[pageCacheKey] = it }
                } else null
            }
            LaunchedEffect(pageCacheKey) {
                if (syncPages != null) {
                    lastStablePages = syncPages
                } else {
                    val computed = withContext(Dispatchers.Default) {
                        EbookTextReflower.paginateBookKeepAll(reflowSrc.fullText, fixedColWPx, fixedTextHPx, fontSizePx, lineHeightPx, cfg.readerUseSerif, effectiveIndentOn)
                    }
                    if (computed.isNotEmpty()) {
                        ReaderLayoutCache.pagesCache[pageCacheKey] = computed
                        lastStablePages = computed
                    }
                }
            }
            val vPages = syncPages ?: lastStablePages
            val stepPg = if (twoPageMode) 2 else 1
            val rawVIdx = remember(vPages, curCharOff) {
                val found = vPages.indexOfLast { it.s <= curCharOff }
                if (found >= 0) found else 0
            }
            val vIdx = (if (twoPageMode) (rawVIdx / 2) * 2 else rawVIdx).coerceIn(0, (vPages.size - 1).coerceAtLeast(0))
            val leftSlice = vPages.getOrElse(vIdx) { PageSlice(0, 0) }
            val leftPageTx = remember(reflowSrc.fullText, leftSlice) {
                reflowSrc.fullText.substring(leftSlice.s.coerceIn(0, reflowSrc.fullText.length), leftSlice.e.coerceIn(0, reflowSrc.fullText.length)).trimEnd('\n', '\r')
            }
            val hasRightPage = twoPageMode && (vIdx + 1 < vPages.size)
            val rightSlice = if (hasRightPage) vPages[vIdx + 1] else null
            val rightPageTx = remember(reflowSrc.fullText, rightSlice) {
                if (rightSlice == null) "" else reflowSrc.fullText.substring(rightSlice.s.coerceIn(0, reflowSrc.fullText.length), rightSlice.e.coerceIn(0, reflowSrc.fullText.length)).trimEnd('\n', '\r')
            }
            val spreadText = remember(leftPageTx, rightPageTx, hasRightPage) {
                if (hasRightPage) (leftPageTx + "\n" + rightPageTx).trim() else leftPageTx
            }
            val spreadEndIdx = if (hasRightPage) vIdx + 2 else vIdx + 1
            val pgLabel = if (hasRightPage) "${vIdx + 1}–${vIdx + 2} / ${vPages.size}쪽" else "${vIdx + 1} / ${vPages.size}쪽"
            val progressPct = ((spreadEndIdx * 100) / vPages.size.coerceAtLeast(1)).coerceIn(1, 100)
            val curRawIdx = reflowSrc.rawIdxOf(leftSlice.s)
            val curSpreadEndChar = rightSlice?.e ?: leftSlice.e
            val isBm = bms.any {
                it.bookId == act.id && (if (it.charOffset >= 0) it.charOffset in leftSlice.s..curSpreadEndChar else it.pageIndex == curRawIdx)
            }
            val segments = remember(act.id, spreadText) { splitSpreadIntoSegments(spreadText) }
            var selectMode by remember(act.id) { mutableStateOf(false) }
            var selSet by remember(act.id) { mutableStateOf(setOf<Int>()) }
            var appliedExcerpt by remember(act.id) { mutableStateOf("") }
            var editExcerptOpen by remember(act.id) { mutableStateOf(false) }
            var editDraftTfv by remember(act.id) { mutableStateOf(TextFieldValue("")) }
            var scissorMsg by remember(act.id) { mutableStateOf<String?>(null) }
            fun clearExcerptSelection() {
                selSet = emptySet()
                appliedExcerpt = ""
                editExcerptOpen = false
                editDraftTfv = TextFieldValue("")
                scissorMsg = null
            }
            fun toggleScissorsButton() {
                selectMode = !selectMode
                if (!selectMode) {
                    editExcerptOpen = false
                    scissorMsg = null
                }
            }
            val checkedText = remember(segments, selSet) {
                if (selSet.isEmpty()) "" else segments.filterIndexed { i, _ -> i in selSet }.joinToString("\n")
            }
            val activeExcerpt = appliedExcerpt.trim().ifEmpty { checkedText.trim() }
            val hasSel = activeExcerpt.isNotBlank()
            val selBannerVisible = !selectMode && hasSel
            val sliderBarVisible = sliderOpen && vPages.size > stepPg
            val aiTargetText = if (hasSel) activeExcerpt else spreadText
            fun commitDraftIfEditing(): String {
                if (editExcerptOpen) {
                    val d = editDraftTfv.text.trim()
                    appliedExcerpt = d
                    editExcerptOpen = false
                    if (d.isNotEmpty()) scissorMsg = "✅ 수정 구절(${d.length}자) 반영 완료! 원하는 기능 버튼을 눌러 실행하세요."
                    return d.ifEmpty { checkedText.trim() }
                }
                return activeExcerpt
            }
SideEffect {
livePgLabel = pgLabel
liveSpreadText = spreadText
liveActiveExcerpt = activeExcerpt
commitExcerptHook = ::commitDraftIfEditing
}
            val liveVIdxHolder = remember(act.id) { intArrayOf(0) }
            liveVIdxHolder[0] = vIdx
            fun goToVPage(targetV: Int, baseIdx: Int = vIdx) {
                if (vPages.isEmpty()) return
                val aligned = if (twoPageMode) (targetV / 2) * 2 else targetV
                val maxStart = if (twoPageMode) ((vPages.size - 1).coerceAtLeast(0) / 2) * 2 else (vPages.size - 1).coerceAtLeast(0)
                val nextV = aligned.coerceIn(0, maxStart)
                if (nextV == baseIdx) return
                liveVIdxHolder[0] = nextV
                val newOff = vPages[nextV].s
                val newRaw = reflowSrc.rawIdxOf(newOff)
                curCharOff = newOff
                readerAiAns = ""
                selectMode = false
                clearExcerptSelection()
                markBookRead(act.id, newOff)
                if (act.currentPage != newRaw) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = newRaw) else it })
                if (ttsHere) {
                    val remRaw = reflowSrc.fullText.substring(newOff.coerceIn(0, reflowSrc.fullText.length), reflowSrc.rawEndOf(newRaw).coerceIn(0, reflowSrc.fullText.length)).trim()
                    ReaderTtsRuntime.jumpToPage(ctx, newRaw, remRaw)
                }
            }
            fun jumpToCharOffset(targetOff: Int) {
                val safeOff = targetOff.coerceIn(0, reflowSrc.fullText.length)
                val newRaw = reflowSrc.rawIdxOf(safeOff)
                curCharOff = safeOff
                markBookRead(act.id, safeOff)
                if (act.currentPage != newRaw) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = newRaw) else it })
                if (ttsHere) {
                    val remRaw = reflowSrc.fullText.substring(safeOff, reflowSrc.rawEndOf(newRaw).coerceIn(0, reflowSrc.fullText.length)).trim()
                    ReaderTtsRuntime.jumpToPage(ctx, newRaw, remRaw)
                }
            }
            val isNightTheme = cfg.readerTheme == "NIGHT"
            val bookHls = remember(allHighlights, act.id) { allHighlights.filter { it.bookId == act.id } }
            fun toggleHighlightTap(pageText: String, pageStartOff: Int, charIdxInPage: Int) {
             val r = findSentenceRangeAt(pageText, charIdxInPage) ?: return
             val gs = pageStartOff + r.first
             val ge = pageStartOff + r.last + 1
             val hit = allHighlights.find { it.bookId == act.id && it.startOff < ge && it.endOff > gs }
             val nx = if (hit != null) allHighlights.filterNot { it.id == hit.id } else allHighlights + BookHighlight(System.currentTimeMillis(), act.id, gs, ge, hlColorIdx)
             allHighlights = nx; saveBookHighlights(ctx, nx)
            }
            val tocEntries = remember(act.id, reflowSrc.fullText, isPoetryBook) { EbookTextReflower.extractBookTocEntries(ctx, act.id, reflowSrc.fullText, isPoetryBook) }
            val curTocIdx = remember(tocEntries, leftSlice.s) { tocEntries.indexOfLast { it.charOffset <= leftSlice.s + 8 } }
            val curTocEntry = if (curTocIdx in tocEntries.indices) tocEntries[curTocIdx] else null
            val curChapPct = remember(tocEntries, curTocIdx, curSpreadEndChar, reflowSrc.fullText.length) { if (curTocIdx !in tocEntries.indices) progressPct else { val cs = tocEntries[curTocIdx].charOffset; val ce = tocEntries.getOrNull(curTocIdx + 1)?.charOffset ?: reflowSrc.fullText.length; val span = (ce - cs).coerceAtLeast(1); (((curSpreadEndChar - cs).coerceIn(1, span) * 100) / span).coerceIn(1, 100) } }
            var activeFootnotePopup by remember(act.id) { mutableStateOf<ActiveFootnotePopup?>(null) }
            var footnoteReturnOff by remember(act.id) { mutableStateOf<Int?>(null) }
            LaunchedEffect(vIdx, act.id) {
                activeFootnotePopup = null
            }
            val swipeTurnActive = !shelf && readerSwipeTurnOn && !selectMode && !tocOpen && !hlListOpen && !folderDialogOpen
            SideEffect { readerSwipePagerLock = swipeTurnActive }
            fun handleReaderBodyTap(pageRender: RenderedBookPage, pageText: String, pageStartOff: Int, dispOff: Int) {
                val rawOff = pageRender.rawOffsetOf(dispOff)
                val tappedFn = pageRender.footnotes
                    .filter { fn ->
                        !fn.isLineStartRef && rawOff in (fn.start - 2)..(fn.end + 2) &&
                            !pageText.substring(minOf(rawOff, fn.start).coerceAtLeast(0), maxOf(rawOff, fn.end).coerceAtMost(pageText.length)).contains(CH_NL)
                    }
                    .minByOrNull { fn ->
                        if (rawOff in fn.start until fn.end) 0 else minOf(abs(rawOff - fn.start), abs(rawOff - fn.end))
                    }
                if (tappedFn != null) {
                    val s = tappedFn.start.coerceIn(0, pageText.length)
                    val e = tappedFn.end.coerceIn(s, pageText.length)
                    val tok = pageText.substring(s, e)
                    val numLbl = EbookTextReflower.extractFootnoteNumber(tok) ?: tok
                    val sentR = findSentenceRangeAt(pageText, s)
                    val ctxSent = if (sentR != null) {
                        pageText.substring(sentR.first.coerceIn(0, pageText.length), (sentR.last + 1).coerceIn(0, pageText.length)).replace(CH_NL, ' ').trim()
                    } else {
                        pageText.substring((s - 45).coerceAtLeast(0), (e + 45).coerceAtMost(pageText.length)).replace(CH_NL, ' ').trim()
                    }
                    val found = EbookTextReflower.lookupFootnoteDefinition(reflowSrc.fullText, pageStartOff + s, tok)
                    activeFootnotePopup = ActiveFootnotePopup(
                        numLabel = numLbl,
                        rawToken = tok,
                        noteBody = found?.noteText,
                        defCharOffset = found?.charOffset ?: -1,
                        contextSentence = ctxSent
                    )
                } else if (highlightMode) {
                    toggleHighlightTap(pageText, pageStartOff, rawOff)
                }
            }
            val leftStartsAtPara = leftSlice.s <= 0 || (leftSlice.s <= reflowSrc.fullText.length && reflowSrc.fullText[leftSlice.s - 1] == CH_NL)
            val rightStartsAtPara = rightSlice != null && (rightSlice.s <= 0 || (rightSlice.s <= reflowSrc.fullText.length && reflowSrc.fullText[rightSlice.s - 1] == CH_NL))
            val leftContinuesMidPara = leftSlice.e < reflowSrc.fullText.length && leftSlice.e > leftSlice.s && reflowSrc.fullText[leftSlice.e - 1] != CH_NL
            val rightContinuesMidPara = rightSlice != null && rightSlice.e < reflowSrc.fullText.length && rightSlice.e > rightSlice.s && reflowSrc.fullText[rightSlice.e - 1] != CH_NL
            renderReaderTopBar {
             Surface(color = CG.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp), border = BorderStroke(0.8.dp, CG.copy(alpha = 0.65f)), modifier = Modifier.clickable { catBarOpen = !catBarOpen }) {
              Text("${act.category.ifBlank { "전자책" }}▾", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CG, maxLines = 1, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
             }
                        Surface(color = if (readerSwipeTurnOn) CG.copy(alpha = 0.22f) else CS2, shape = RoundedCornerShape(8.dp), border = BorderStroke(if (readerSwipeTurnOn) 1.2.dp else 0.8.dp, if (readerSwipeTurnOn) CG else CBDR), modifier = Modifier.clickable { readerSwipeTurnOn = !readerSwipeTurnOn; readerPrefs.edit().putBoolean("reader_swipe_turn_on", readerSwipeTurnOn).apply() }) { Text(if (readerSwipeTurnOn) "👆스와이프 ON" else "👆스와이프 OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (readerSwipeTurnOn) CG else CM, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) }
                Text(act.title, fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false, modifier = Modifier.clickable { tocFilterQuery = ""; tocOpen = true })
                ChipBtn("☰", tocOpen, CG) { tocFilterQuery = ""; tocOpen = !tocOpen }
             ChipBtn(if (readerAiMode == 2) "🪟" else "🦙", readerAiOpen, CG) { commitDraftIfEditing(); aiFloatMin = false; readerAiOpen = !readerAiOpen }
             ChipBtn("🖍️", highlightMode, CA) { highlightMode = !highlightMode; if (highlightMode) selectMode = false }
            if (bookHls.isNotEmpty()) { ChipBtn("📑${bookHls.size}", hlListOpen, CG) { hlListOpen = true } }
                ChipBtn("✂️", selectMode || hasSel, CA) { if (!selectMode) highlightMode = false; toggleScissorsButton() }
             ChipBtn(if (ttsHere) "⏹️" else "🔊", ttsHere, CG) {
              if (ttsHere) ReaderTtsRuntime.stop(ctx) else {
               val chosen = commitDraftIfEditing()
               val startEx = if (chosen.isNotBlank()) chosen else reflowSrc.fullText.substring(leftSlice.s.coerceIn(0, reflowSrc.fullText.length), reflowSrc.rawEndOf(curRawIdx).coerceIn(0, reflowSrc.fullText.length)).trim()
               ReaderTtsRuntime.start(ctx, act, curRawIdx, startEx)
              }
             }
             if (ReaderTtsRuntime.on && ttsBarMin) { ChipBtn("🔼", false, CG) { ttsBarMin = false } }
             ChipBtn(if (readerMemoOpen) "📝" else if (readerMemoMode == 2) "🪟" else "◫", readerMemoOpen, CW) { if (readerMemoOpen) readerMemoOpen = false else openReaderMemoInLastMode() }
             ChipBtn(if (isBm) "🔖★" else "🔖", isBm, CA) {
              val nx = if (isBm) bms.filterNot { it.bookId == act.id && (if (it.charOffset >= 0) it.charOffset in leftSlice.s..curSpreadEndChar else it.pageIndex == curRawIdx) }
              else listOf(BookmarkEntry(act.id, act.title, curRawIdx, aiTargetText.replace(10.toChar(), ' ').take(45), leftSlice.s)) + bms
              bms = nx; saveBookmarks(ctx, nx)
             }
             if (searchQuery.isNotBlank() && !searchOpen) { ChipBtn("✕", false, CM) { searchQuery = "" } }
            }
            if (searchOpen) {
             ReaderUnifiedSearchBar(
              books = books, activeBook = act, activeBookFullText = reflowSrc.fullText, activeBookPages = vPages, activeCurVIdx = vIdx, smartReflow = smartReflowOn,
              query = searchQuery, onQueryChange = { searchQuery = it }, scopeAllBooks = searchAllBooks, onScopeChange = { searchAllBooks = it }, ui = ui,
              onSelectHit = { hit ->
               if (hit.bookId == act.id) jumpToCharOffset(hit.charOffset)
               else { selId = hit.bookId; shelf = false; bmOpen = false; searchAllBooks = false; markBookRead(hit.bookId, hit.charOffset); pendingJump = hit.bookId to hit.charOffset }
              },
              onClose = { searchOpen = false }
             )
            }
            if (highlightMode) {
             Surface(color = CS2, shape = RoundedCornerShape(10.dp), border = BorderStroke(0.9.dp, CA.copy(alpha = 0.75f)), modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
              Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
               Text("🖍️ 문장 탭=밑줄/해제", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CA)
               ChipBtn("🟡노랑", hlColorIdx == 0, CA) { hlColorIdx = 0 }
               ChipBtn("🟢민트", hlColorIdx == 1, CG) { hlColorIdx = 1 }
               ChipBtn("🩷핑크", hlColorIdx == 2, Color(0xFFF472B6)) { hlColorIdx = 2 }
               if (bookHls.isNotEmpty()) {
              ChipBtn("📑모아보기(${bookHls.size})", false, CG) { hlListOpen = true }
                   ChipBtn("↩️취소", false, CW) { val lastId = bookHls.last().id; val nx = allHighlights.filterNot { it.id == lastId }; allHighlights = nx; saveBookHighlights(ctx, nx) }
                ChipBtn("🗑️이쪽지움", false, Color(0xFFF87171)) { val nx = allHighlights.filterNot { it.bookId == act.id && it.endOff > leftSlice.s && it.startOff < curSpreadEndChar }; allHighlights = nx; saveBookHighlights(ctx, nx) }
               }
               ChipBtn("✓완료", true, CG) { highlightMode = false }
              }
             }
            }
if (readerAiOpen && readerAiMode == 1) {
Surface(color = CS, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (hasSel) CA.copy(alpha = 0.75f) else CG.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp).height(212.dp)) {
Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
Text(if (hasSel) "🎯${activeExcerpt.length}자" else "📖$pgLabel", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (hasSel) CA else CG)
ChipBtn("📌요약", false, CG) { val t = commitDraftIfEditing(); askPageAi("핵심 내용을 3줄로 요약해줘.", act.title, pgLabel, t.ifBlank { spreadText }, t.isNotBlank()) }
ChipBtn("💡해설", false, CA) { val t = commitDraftIfEditing(); askPageAi("맥락과 의미를 알기 쉽게 해설해줘.", act.title, pgLabel, t.ifBlank { spreadText }, t.isNotBlank()) }
ChipBtn("✨핵심문장", false, CW) { val t = commitDraftIfEditing(); askPageAi("가장 인상 깊은 핵심 문장 1개를 뽑고 설명해줘.", act.title, pgLabel, t.ifBlank { spreadText }, t.isNotBlank()) }
if (readerAiAns.isNotBlank() && !readerAiBusy) {
ChipBtn("💾노트저장", false, CA) { onBookNote(act.title, "[$pgLabel${if (hasSel) " · 선택구절" else ""} AI 해설]" + 10.toChar() + readerAiAns.removeSuffix(" ▍").trim(), false); readerAiNotice = "✅ 『${act.title}』 독서노트에 이어서 저장됨!" }
ChipBtn("🔈낭독", false, CG) { speakOneShot(ctx, readerAiAns.removeSuffix(" ▍")) }
}
ChipBtn("🪟플로팅", false, CA) { setReaderAiModePersist(2) }
ChipBtn("✕", false, CM) { readerAiOpen = false }
}
readerAiNotice?.let { nt -> Text(nt, fontSize = ui.subSp, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis) }
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
Text(readerAiAns.ifBlank { if (hasSel) "🎯 선택한 구절(${activeExcerpt.length}자) 대기 중입니다. 위 [📌요약 / 💡해설 / ✨핵심문장] 버튼을 누르거나 아래에 질문을 입력해 [↑]를 누르면 AI가 시작됩니다." else "📖 자동 실행이 꺼져 있습니다. 위 [📌요약 / 💡해설 / ✨핵심문장] 버튼을 누르거나 아래에 질문을 입력하면 그때 AI가 진행됩니다." }, fontSize = ui.bodySp, color = CW)
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = readerAiQ, onValueChange = { readerAiQ = it }, placeholder = { Text(if (hasSel) "선택한 구절 질문 입력 후 ↑..." else "페이지 질문 입력 후 ↑...", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
if (readerAiBusy) { Button(onClick = { activeGenId++; readerAiJob?.cancel(); if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }; readerAiBusy = false; readerAiAns = readerAiAns.removeSuffix(" ▍") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) { Text("⏹️", fontSize = ui.subSp) } }
else { Button(onClick = { val q = readerAiQ.trim(); if (q.isNotEmpty()) { val t = commitDraftIfEditing(); readerAiQ = ""; askPageAi(q, act.title, pgLabel, t.ifBlank { spreadText }, t.isNotBlank()) } }) { Text("↑", fontSize = ui.subSp) } }
}
}
}
}
            if (catBarOpen) {
                Surface(color = CS2, shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, CG.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
                            (bCats + listOf("인문·철학", "소설", "에세이", "자기계발", "경제·경영", "기타")).distinct().forEach { c ->
                                ChipBtn(c, act.category == c, CG) {
                                    if (!bCats.contains(c)) { bCats = bCats + c; SmartMemoClassifier.saveBookCats(ctx, bCats) }
                                    onSaveBooks(books.map { if (it.id == act.id) it.copy(category = c) else it }); catBarOpen = false
                                }
                            }
                            ChipBtn("➕", false, CW) { rCfgTab = 2; cfgOpen = true; catBarOpen = false }
                        }
                    }
                }
            }
            if (selBannerVisible) {
                Surface(color = CS2, shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, CA.copy(alpha = 0.65f)), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), SB, CV) {
                        Text("✂️ 선택·편집 구절 대기 중 (${activeExcerpt.length}자) · 아래 버튼을 눌러 실행", fontSize = ui.subSp, color = CA, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                            Text("편집", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.clickable { selectMode = true })
                            Text("해제", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { clearExcerptSelection() })
                        }
                    }
                }
            }

            val bookDedicatedMemo = remember(notes, act.title) { findBookDedicatedNoteBody(notes, act.title) }
            val saveBookMemoInline: (String) -> Unit = { newBody ->
                val nx = upsertBookDedicatedNote(notes, act.title, newBody)
                onUpdateNotes(nx)
            }
            val ovScroll = rememberScrollState()
            LaunchedEffect(vIdx, act.id) {
                if (ovScroll.value != 0) ovScroll.scrollTo(0)
            }
            val transientChromeOpen = isKeyboardOpen || cfgOpen || bmOpen || searchOpen || highlightMode ||
                (readerAiOpen && readerAiMode == 1) || (readerMemoOpen && readerMemoMode == 1) ||
                selBannerVisible || sliderBarVisible || catBarOpen || topTtsVisible || topStatusVisible
            val isFullUnobstructed = !isWindowShrunk && !transientChromeOpen
            val floatingOverlayOpen = (readerMemoOpen && readerMemoMode == 2 && !memoFloatMin) || (readerAiOpen && readerAiMode == 2 && !aiFloatMin)
            val shouldScrollPage = !isFullUnobstructed || floatingOverlayOpen
            val scrollBottomExtra = if (floatingOverlayOpen) 150.dp else 6.dp
            val isHorizMemoSplit = readerMemoOpen && readerMemoMode == 1 && memoSplitHoriz
            val renderBookViewport: @Composable (Modifier) -> Unit = { paneMod ->
            BoxWithConstraints(paneMod) {
                val availW = this.maxWidth
                val renderColW = if (twoPageMode) ((availW - outerPad * 2 - spineW) / 2f).coerceAtLeast(80.dp) else (availW - outerPad * 2).coerceAtLeast(100.dp)
                val rawRenderColWPx = with(density) { renderColW.toPx() }.roundToInt().coerceAtLeast(80)
                val effColWPx = if (abs(rawRenderColWPx - fixedColWPx) <= 6) fixedColWPx else rawRenderColWPx
                val leftPageRender = remember(leftPageTx, effColWPx, fontSizePx, curPt, cfg.readerUseSerif, readerJustifyOn, effectiveIndentOn, leftStartsAtPara, leftContinuesMidPara, searchQuery, isNightTheme, leftSlice.s, bookHls) {
                    buildWrappedHighlightedBookPage(leftPageTx, effColWPx, fontSizePx, curPt, cfg.readerUseSerif, readerJustifyOn, effectiveIndentOn, leftStartsAtPara, leftContinuesMidPara, searchQuery, isNightTheme, leftSlice.s, bookHls)
                }
                val rightPageRender = remember(rightPageTx, effColWPx, fontSizePx, curPt, cfg.readerUseSerif, readerJustifyOn, effectiveIndentOn, rightStartsAtPara, rightContinuesMidPara, searchQuery, isNightTheme, rightSlice?.s, bookHls) {
                    buildWrappedHighlightedBookPage(rightPageTx, effColWPx, fontSizePx, curPt, cfg.readerUseSerif, readerJustifyOn, effectiveIndentOn, rightStartsAtPara, rightContinuesMidPara, searchQuery, isNightTheme, rightSlice?.s ?: 0, bookHls)
                }

                if (selectMode) {
                    Surface(
                        color = CS,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CA.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), SB, CV) {
                                Text(
                                    if (hasSel) "✂️ 구절 선택·수정 (${activeExcerpt.length}자 준비됨)" else "✂️ 문장을 터치해 고른 뒤 기능 버튼을 눌러 주세요",
                                    fontSize = ui.subSp,
                                    fontWeight = FontWeight.Bold,
                                    color = CA,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("📖 본문보기", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.clickable { commitDraftIfEditing(); selectMode = false }.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                if (segments.isNotEmpty()) {
                                    ChipBtn("☑️ 전체선택", selSet.size == segments.size && segments.isNotEmpty(), CG) {
                                        val allIdx = segments.indices.toSet()
                                        selSet = allIdx
                                        appliedExcerpt = ""
                                        val joined = segments.joinToString("\n")
                                        editDraftTfv = TextFieldValue(joined, TextRange(joined.length))
                                    }
                                    ChipBtn("🔄 초기화", false, CM) { clearExcerptSelection() }
                                }
                                ChipBtn(if (editExcerptOpen) "✏️ 편집창 닫기" else "✏️ 구절 직접 수정", editExcerptOpen, CW) {
                                    if (!editExcerptOpen) {
                                        val seed = activeExcerpt.ifBlank { spreadText }
                                        editDraftTfv = TextFieldValue(seed, TextRange(seed.length))
                                        editExcerptOpen = true
                                    } else {
                                        editExcerptOpen = false
                                    }
                                }
                            }
                            scissorMsg?.let { sm ->
                                Text(sm, fontSize = ui.subSp, color = CG)
                            }
                            if (editExcerptOpen) {
                                Surface(color = CS2, shape = RoundedCornerShape(10.dp), border = BorderStroke(0.9.dp, CG.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("✏️ 아래 내용을 자유롭게 다듬은 뒤 [✅ 수정 반영] 또는 실행 버튼을 눌러야 작동합니다.", fontSize = ui.subSp, color = CM)
                                        OutlinedTextField(
                                            value = editDraftTfv,
                                            onValueChange = { editDraftTfv = it },
                                            singleLine = false,
                                            minLines = 3,
                                            maxLines = 6,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                            Button(
                                                onClick = {
                                                    val d = editDraftTfv.text.trim()
                                                    appliedExcerpt = d
                                                    editExcerptOpen = false
                                                    scissorMsg = if (d.isNotEmpty()) "✅ 수정된 구절(${d.length}자)이 반영되었습니다. 아래 버튼을 눌러 실행하세요." else "⚠️ 입력된 구절이 없습니다."
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = CG, contentColor = CON_ACCENT)
                                            ) {
                                                Text("✅ 수정 반영", fontSize = ui.subSp, fontWeight = FontWeight.Bold)
                                            }
                                            OutlinedButton(
                                                onClick = { editExcerptOpen = false },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("취소", fontSize = ui.subSp, color = CM)
                                            }
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                ReaderPointBtn("🦙 AI 창 열기", false, CG, Modifier.weight(1f)) {
                                    commitDraftIfEditing()
                                    selectMode = false
                                    readerAiOpen = true
                                    readerAiNotice = "🎯 구절이 준비되었습니다. 아래 요약·해설 버튼을 누르거나 질문을 입력하세요."
                                }
                                ReaderPointBtn("📝 메모 발췌", false, CA, Modifier.weight(1f)) {
                                    val target = commitDraftIfEditing().ifBlank { spreadText }
                                    selectMode = false
                                    openReaderMemoInLastMode(34.toChar().toString() + target.take(450) + 34.toChar() + 10.toChar() + "• 메모: ")
                                }
                                ReaderPointBtn("🔈 구절 낭독", false, CW, Modifier.weight(1f)) {
                                    val target = commitDraftIfEditing().ifBlank { spreadText }
                                    speakOneShot(ctx, target)
                                    scissorMsg = "🔈 선택한 구절을 낭독합니다."
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                segments.forEachIndexed { sIdx, seg ->
                                    val isSel = sIdx in selSet
                                    Surface(
                                        color = if (isSel) CG.copy(alpha = 0.16f) else CS2,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(if (isSel) 1.2.dp else 0.7.dp, if (isSel) CG else CBDR),
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            val nx = if (isSel) selSet - sIdx else selSet + sIdx
                                            selSet = nx
                                            appliedExcerpt = ""
                                            val joined = segments.filterIndexed { i, _ -> i in nx }.joinToString("\n")
                                            if (editExcerptOpen) editDraftTfv = TextFieldValue(joined, TextRange(joined.length))
                                        }
                                    ) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(if (isSel) "☑" else "☐", fontSize = (curPt - 1).coerceAtLeast(12).sp, fontWeight = FontWeight.Bold, color = if (isSel) CG else CM)
                                            Text(seg, style = pageTextStyle.copy(color = CW), modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                                    var swipeDragPx by remember(act.id) { mutableStateOf(0f) }
                                    val latestSwipeTurn by rememberUpdatedState<(Int) -> Unit> { dir -> val curBase = liveVIdxHolder[0]; goToVPage(curBase + dir * stepPg, curBase) }
                    val latestCanPrev by rememberUpdatedState(vIdx > 0); val latestCanNext by rememberUpdatedState(if (twoPageMode) (vIdx + 2 < vPages.size) else (vIdx + 1 < vPages.size)); val swipeThresholdPx = with(density) { 42.dp.toPx() }; val maxVisualShiftPx = with(density) { 26.dp.toPx() }
                    val bookSwipeMod = if (readerSwipeTurnOn) { Modifier.pointerInput(act.id, readerSwipeTurnOn, swipeThresholdPx) { var totalDx = 0f; detectHorizontalDragGestures(onDragStart = { totalDx = 0f; swipeDragPx = 0f }, onHorizontalDrag = { change, dragAmount -> change.consume(); totalDx += dragAmount; val edgeDamp = if ((totalDx > 0f && !latestCanPrev) || (totalDx < 0f && !latestCanNext)) 0.18f else 0.45f; swipeDragPx = (totalDx * edgeDamp).coerceIn(-maxVisualShiftPx, maxVisualShiftPx) }, onDragEnd = { val dx = totalDx; totalDx = 0f; swipeDragPx = 0f; if (dx <= -swipeThresholdPx) latestSwipeTurn(1) else if (dx >= swipeThresholdPx) latestSwipeTurn(-1) }, onDragCancel = { totalDx = 0f; swipeDragPx = 0f }) }.graphicsLayer { translationX = swipeDragPx } } else Modifier
                    Surface(
                        color = pageSurf,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.8.dp, mut.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxSize().then(bookSwipeMod)
                    ) {
                        val splitScrollMod = if (shouldScrollPage) Modifier.verticalScroll(ovScroll) else Modifier
                        if (twoPageMode) {
                            val twoPgColMod = if (shouldScrollPage) {
                                Modifier.width(renderColW).padding(top = 8.dp, bottom = scrollBottomExtra)
                            } else {
                                Modifier.width(renderColW).fillMaxHeight().padding(top = 8.dp, bottom = 6.dp)
                            }
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .then(splitScrollMod)
                                    .padding(horizontal = outerPad),
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(twoPgColMod) {
                                    Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                                        Text(act.title, fontSize = 10.sp, color = mut.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Box(if (shouldScrollPage) Modifier.width(renderColW).heightIn(min = fixedTextH) else Modifier.width(renderColW).weight(1f)) {
                                        var lLay by remember(leftPageTx, curPt, twoPageMode, effColWPx, readerJustifyOn, effectiveIndentOn) { mutableStateOf<TextLayoutResult?>(null) }
                                        Text(
                                            leftPageRender.annotated,
                                            style = pageTextStyle,
                                            onTextLayout = { lLay = it },
                                            modifier = Modifier.fillMaxWidth().pointerInput(highlightMode, leftSlice.s, leftPageTx, hlColorIdx, bookHls, leftPageRender) {
                                                if (highlightMode || leftPageRender.footnotes.isNotEmpty()) {
                                                    detectTapGestures { pos ->
                                                        val off = lLay?.getOffsetForPosition(pos) ?: return@detectTapGestures
                                                        handleReaderBodyTap(leftPageRender, leftPageTx, leftSlice.s, off)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
                                        Text("${vIdx + 1}", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mut)
                                    }
                                }
                                Box(
                                    Modifier
                                        .width(spineW)
                                        .height(fixedTextH + 52.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    spineBase.copy(alpha = 0.08f),
                                                    spineBase.copy(alpha = 0.22f),
                                                    spineBase.copy(alpha = 0.08f),
                                                    Color.Transparent
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(Modifier.width(1.dp).fillMaxHeight(0.94f).background(mut.copy(alpha = 0.26f)))
                                }
                                Column(twoPgColMod) {
                                                Box(Modifier.fillMaxWidth().height(16.dp).clickable { tocFilterQuery = ""; tocOpen = true }, contentAlignment = Alignment.CenterEnd) {
                                        val chapTag = curTocEntry?.let { "☰ ${it.title.take(16)} (${curChapPct}%)" } ?: "☰ ${act.category.ifBlank { "독서" }}"
                                        Text("$chapTag · 전체 ${progressPct}%", fontSize = 10.sp, color = mut.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                    Spacer(Modifier.height(4.dp))
                                    Box(if (shouldScrollPage) Modifier.width(renderColW).heightIn(min = fixedTextH) else Modifier.width(renderColW).weight(1f)) {
                                        if (hasRightPage && rightSlice != null) {
                                            var rLay by remember(rightPageTx, curPt, twoPageMode, effColWPx, readerJustifyOn, effectiveIndentOn) { mutableStateOf<TextLayoutResult?>(null) }
                                            Text(
                                                rightPageRender.annotated,
                                                style = pageTextStyle,
                                                onTextLayout = { rLay = it },
                                                modifier = Modifier.fillMaxWidth().pointerInput(highlightMode, rightSlice.s, rightPageTx, hlColorIdx, bookHls, rightPageRender) {
                                                    if (highlightMode || rightPageRender.footnotes.isNotEmpty()) {
                                                        detectTapGestures { pos ->
                                                            val off = rLay?.getOffsetForPosition(pos) ?: return@detectTapGestures
                                                            handleReaderBodyTap(rightPageRender, rightPageTx, rightSlice.s, off)
                                                        }
                                                    }
                                                }
                                            )
                                        } else {
                                            Box(Modifier.fillMaxWidth().height(fixedTextH), contentAlignment = Alignment.Center) {
                                                Text("— 마지막 페이지 —", fontSize = ui.subSp, color = mut.copy(alpha = 0.65f))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterEnd) {
                                        Text(if (hasRightPage) "${vIdx + 2}" else "", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mut)
                                    }
                                }
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .then(splitScrollMod)
                                    .padding(start = outerPad, end = outerPad, top = 5.dp, bottom = if (shouldScrollPage) scrollBottomExtra else 5.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().height(15.dp), SB, CV) {
                                    Text(act.title, fontSize = 10.sp, color = mut.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text("${progressPct}%", fontSize = 10.sp, color = mut.copy(alpha = 0.8f))
                                }
                                Spacer(Modifier.height(2.dp))
                                Box((if (shouldScrollPage) Modifier.fillMaxWidth().heightIn(min = fixedTextH) else Modifier.fillMaxWidth().weight(1f)).onSizeChanged { bsz ->
                                    if (!shouldScrollPage && isFullUnobstructed && bsz.height > 120) {
                                        val hCand = bsz.height.toFloat()
                                        if (abs(hCand - measuredTextHPxState.value) >= 5f) {
                                            measuredTextHPxState.value = hCand
                                            readerPrefs.edit().putFloat(fsHKey, hCand).apply()
                                        }
                                    }
                                }) {
                                    var sLay by remember(leftPageTx, curPt, twoPageMode, effColWPx, readerJustifyOn, effectiveIndentOn) { mutableStateOf<TextLayoutResult?>(null) }
                                    Text(
                                        leftPageRender.annotated,
                                        style = pageTextStyle,
                                        onTextLayout = { sLay = it },
                                        modifier = Modifier.fillMaxWidth().pointerInput(highlightMode, leftSlice.s, leftPageTx, hlColorIdx, bookHls, leftPageRender) {
                                            if (highlightMode || leftPageRender.footnotes.isNotEmpty()) {
                                                detectTapGestures { pos ->
                                                    val off = sLay?.getOffsetForPosition(pos) ?: return@detectTapGestures
                                                    handleReaderBodyTap(leftPageRender, leftPageTx, leftSlice.s, off)
                                                }
                                            }
                                        }
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Row(Modifier.fillMaxWidth().height(16.dp), SB, CV) {
                                               val footerChap = curTocEntry?.let { "☰ ${it.title} · 챕터 ${curChapPct}%" } ?: "☰ ${act.category.ifBlank { "독서" }}"
                                    Text(footerChap, fontSize = 10.sp, color = mut.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp).clickable { tocFilterQuery = ""; tocOpen = true })
                                    Text("${vIdx + 1} / ${vPages.size}", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mut)
                                }
                            }
                        }
                    }
                }
            }

            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).onSizeChanged { sz ->
                if (!shouldScrollPage && isFullUnobstructed && !isHorizMemoSplit && sz.width > 200) {
                    val wCand = sz.width.toFloat()
                    if (abs(wCand - measuredBookWPxState.value) >= 4f) {
                        measuredBookWPxState.value = wCand
                        readerPrefs.edit().putFloat(fsWKey, wCand).apply()
                    }
                }
                if (twoPageMode && !shouldScrollPage && isFullUnobstructed && sz.height > 200) {
                    val cand = (sz.height.toFloat() - cardInnerChromePx).coerceAtLeast(120f)
                    if (abs(cand - measuredTextHPxState.value) >= 8f) {
                        measuredTextHPxState.value = cand
                        readerPrefs.edit().putFloat(fsHKey, cand).apply()
                    }
                }
            }) {
    if (readerMemoOpen && readerMemoMode == 1) {
                    if (memoSplitHoriz) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            renderBookViewport(Modifier.weight(0.54f).fillMaxHeight())
                            ReaderMemoWorkspaceCard(
                                ctx = ctx, ui = ui, bookId = act.id, bookTitle = act.title, pgLabel = pgLabel,
                                currentExcerptOrPage = aiTargetText, initialBody = bookDedicatedMemo,
                                pendingAppendExcerpt = pendingMemoExcerpt, onConsumedPendingExcerpt = { pendingMemoExcerpt = null },
                                isFloating = false, splitHoriz = true,
                                onSelectSplitMode = { hz -> setReaderMemoModePersist(1, hz) },
                                onSelectFloatingMode = { setReaderMemoModePersist(2) },
                                onSaveBody = saveBookMemoInline, onClose = { readerMemoOpen = false },
                                modifier = Modifier.weight(0.46f).fillMaxHeight()
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            renderBookViewport(Modifier.weight(0.56f).fillMaxWidth())
                            ReaderMemoWorkspaceCard(
                                ctx = ctx, ui = ui, bookId = act.id, bookTitle = act.title, pgLabel = pgLabel,
                                currentExcerptOrPage = aiTargetText, initialBody = bookDedicatedMemo,
                                pendingAppendExcerpt = pendingMemoExcerpt, onConsumedPendingExcerpt = { pendingMemoExcerpt = null },
                                isFloating = false, splitHoriz = false,
                                onSelectSplitMode = { hz -> setReaderMemoModePersist(1, hz) },
                                onSelectFloatingMode = { setReaderMemoModePersist(2) },
                                onSaveBody = saveBookMemoInline, onClose = { readerMemoOpen = false },
                                modifier = Modifier.weight(0.44f).fillMaxWidth()
                            )
                        }
                    }
                } else {
                    renderBookViewport(Modifier.fillMaxSize())
                }
                val retOff = footnoteReturnOff
                if (retOff != null && activeFootnotePopup == null && kotlin.math.abs(retOff - leftSlice.s) > 20) {
                    val retPg = vPages.indexOfLast { it.s <= retOff }.coerceAtLeast(0) + 1
                    Surface(
                        color = CS,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, CG),
                        shadowElevation = 6.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
                            Text(
                                "↩️ 읽던 본문(${retPg}쪽)으로 복귀",
                                fontSize = ui.subSp,
                                fontWeight = FontWeight.Bold,
                                color = CG,
                                modifier = Modifier.clickable {
                                    footnoteReturnOff = null
                                    jumpToCharOffset(retOff)
                                }
                            )
                            Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { footnoteReturnOff = null })
                        }
                    }
                }
                activeFootnotePopup?.let { fnPop ->
                    val defPg = if (fnPop.defCharOffset >= 0) (vPages.indexOfLast { it.s <= fnPop.defCharOffset }.coerceAtLeast(0) + 1) else -1
                    Surface(
                        color = CS,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.1.dp, Color(0xFF38BDF8)),
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .heightIn(max = 228.dp)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), SB, CV) {
                                Text(
                                    if (defPg > 0) "📌 각주 [${fnPop.numLabel}] · ${defPg}쪽" else "📌 각주 [${fnPop.numLabel}] 미리보기",
                                    fontSize = (ui.subSp.value + 1f).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                    ChipBtn("🦙AI해설", false, CG) {
                                        val ctxTx = if (fnPop.noteBody != null) {
                                            "[본문 문장]" + CH_NL + fnPop.contextSentence + STR_PARA + "[각주 ${fnPop.numLabel}]" + CH_NL + fnPop.noteBody
                                        } else {
                                            fnPop.contextSentence
                                        }
                                        activeFootnotePopup = null
                                        readerAiOpen = true
                                        askPageAi("이 문장과 각주 [${fnPop.numLabel}]의 의미·배경을 알기 쉽게 해설해줘.", act.title, pgLabel, ctxTx, true)
                                    }
                                    if (fnPop.noteBody != null) {
                                        ChipBtn("📝메모", false, CA) {
                                            val memoPiece = "[각주 ${fnPop.numLabel}] ${fnPop.noteBody.take(380)}" + CH_NL + "• 메모: "
                                            activeFootnotePopup = null
                                            openReaderMemoInLastMode(memoPiece)
                                        }
                                    }
                                    if (fnPop.defCharOffset >= 0) {
                                        ChipBtn("📖이동", false, CW) {
                                            footnoteReturnOff = leftSlice.s
                                            val dst = fnPop.defCharOffset
                                            activeFootnotePopup = null
                                            jumpToCharOffset(dst)
                                        }
                                    }
                                    Text(
                                        "✕",
                                        fontSize = (ui.subSp.value + 1.5f).sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CM,
                                        modifier = Modifier.clickable { activeFootnotePopup = null }.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Text(
                                    fnPop.noteBody ?: ("도서 내부에서 [${fnPop.numLabel}]번 각주 설명문을 찾지 못했습니다." + STR_PARA + "• 문맥: " + CH_DQ + fnPop.contextSentence + CH_DQ + CH_NL + "(상단 [🦙AI해설]을 누르면 해당 구절과 용어를 바로 풀이해 드립니다.)"),
                                    fontSize = (curPt - 3).coerceIn(13, 19).sp,
                                    lineHeight = ((curPt - 3).coerceIn(13, 19) * 1.48f).sp,
                                    color = CW
                                )
                            }
                        }
                    }
                }
           }
            if (tocOpen) {
                val qTrim = tocFilterQuery.trim()
                val indexedToc = remember(tocEntries, qTrim) { val allIndexed = tocEntries.mapIndexed { idx, entry -> idx to entry }; if (qTrim.isEmpty()) allIndexed else allIndexed.filter { (_, entry) -> KoreanChosungMatcher.matches(entry.title, qTrim) } }
                val initScrollIdx = remember(tocOpen, indexedToc, curTocIdx) { if (qTrim.isNotEmpty() || curTocIdx < 0) 0 else indexedToc.indexOfFirst { it.first == curTocIdx }.let { if (it >= 0) (it - 2).coerceAtLeast(0) else 0 } }
                val tocListState = rememberLazyListState(initialFirstVisibleItemIndex = initScrollIdx)
                AlertDialog(
                    onDismissRequest = { tocOpen = false },
                    containerColor = CS,
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), SB, CV) {
                                Text("☰ 목차 · 챕터 이동 (${tocEntries.size})", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CG)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                    if (curTocIdx > 0) ChipBtn("◀이전장", false, CW) { val prevE = tocEntries[curTocIdx - 1]; if (abs(prevE.charOffset - leftSlice.s) > 60) footnoteReturnOff = leftSlice.s; tocOpen = false; jumpToCharOffset(prevE.charOffset) }
                                    if (curTocIdx >= 0 && curTocIdx + 1 < tocEntries.size) ChipBtn("다음장▶", false, CA) { val nextE = tocEntries[curTocIdx + 1]; if (abs(nextE.charOffset - leftSlice.s) > 60) footnoteReturnOff = leftSlice.s; tocOpen = false; jumpToCharOffset(nextE.charOffset) }
                                }
                            }
                            curTocEntry?.let { curE -> Text("📖 현재: ${curE.title} (챕터 ${curChapPct}% · 전체 ${progressPct}%)", fontSize = 11.5.sp, color = CA, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (tocEntries.size >= 7) OutlinedTextField(value = tocFilterQuery, onValueChange = { tocFilterQuery = it }, placeholder = { Text("챕터·소제목 검색 (초성 지원)...", fontSize = 11.5.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    },
                    text = {
                        if (indexedToc.isEmpty()) Text("일치하는 목차 항목이 없습니다.", fontSize = ui.subSp, color = CM) else {
                            LazyColumn(state = tocListState, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(indexedToc, key = { (origIdx, e) -> "${origIdx}_${e.charOffset}" }) { (origIdx, entry) ->
                                    val isCurChap = origIdx == curTocIdx; val isPassedChap = curTocIdx >= 0 && origIdx < curTocIdx
                                    val entryPg = vPages.indexOfLast { it.s <= entry.charOffset }.coerceAtLeast(0) + 1
                                    val nextOff = tocEntries.getOrNull(origIdx + 1)?.charOffset ?: reflowSrc.fullText.length
                                    val pgSpanCnt = ((vPages.indexOfLast { it.s < nextOff }.coerceAtLeast(entryPg - 1) + 1) - entryPg + 1).coerceAtLeast(1)
                                    val entryBookPct = (((entry.charOffset.coerceAtLeast(0).toLong() * 100L) / reflowSrc.fullText.length.coerceAtLeast(1).toLong()).toInt()).coerceIn(0, 100)
                                    Surface(color = if (isCurChap) CG.copy(alpha = 0.17f) else CS2, shape = RoundedCornerShape(10.dp), border = BorderStroke(if (isCurChap) 1.3.dp else 0.8.dp, if (isCurChap) CG else CBDR), modifier = Modifier.fillMaxWidth().padding(start = if (entry.level >= 2) 12.dp else 0.dp).clickable { if (abs(entry.charOffset - leftSlice.s) > 60) footnoteReturnOff = leftSlice.s; tocOpen = false; jumpToCharOffset(entry.charOffset) }) {
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), SB, CV) {
                                                Text((if (entry.level >= 2) "└ " else "") + entry.title, fontSize = if (entry.level == 1) (ui.subSp.value + 1f).sp else ui.subSp, fontWeight = if (isCurChap || entry.level == 1) FontWeight.Bold else FontWeight.Medium, color = if (isCurChap) CG else if (isPassedChap) CW.copy(alpha = 0.72f) else CW, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("${entryPg}쪽", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isCurChap) CG else CA)
                                                    Text("${pgSpanCnt}쪽 분량 · ${entryBookPct}%", fontSize = 9.5.sp, color = CM)
                                                }
                                            }
                                            if (isCurChap) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                                                    Box(Modifier.weight(1f).height(4.dp).background(CBDR, RoundedCornerShape(50))) { Box(Modifier.fillMaxWidth(curChapPct / 100f).fillMaxHeight().background(CG, RoundedCornerShape(50))) }
                                                    Text("읽는 중 ${curChapPct}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CG)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { tocOpen = false }) { Text("닫기", color = CG, fontWeight = FontWeight.Bold) } }
                )
            }
            if (hlListOpen) { val sortedHls = remember(bookHls) { bookHls.sortedBy { it.startOff } }; AlertDialog(onDismissRequest = { hlListOpen = false }, containerColor = CS, title = { Row(Modifier.fillMaxWidth(), SB, CV) { Text("🖍️ 형광펜 모아보기 (${sortedHls.size})", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CA); if (sortedHls.isNotEmpty()) { ChipBtn("🗑️전체삭제", false, Color(0xFFF87171)) { val nx = allHighlights.filterNot { it.bookId == act.id }; allHighlights = nx; saveBookHighlights(ctx, nx); hlListOpen = false } } } }, text = { if (sortedHls.isEmpty()) { Text("이 책에 저장된 형광펜이 없습니다. 상단 🖍️ 버튼으로 문장을 탭해 밑줄을 칠 수 있어요.", fontSize = ui.subSp, color = CM) } else { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(sortedHls, key = { it.id }) { hl -> val dot = when (hl.colorIdx) { 1 -> "🟢"; 2 -> "🩷"; else -> "🟡" }; val hlPg = vPages.indexOfLast { it.s <= hl.startOff }.coerceAtLeast(0) + 1; val sIdx = hl.startOff.coerceIn(0, reflowSrc.fullText.length); val eIdx = hl.endOff.coerceIn(sIdx, reflowSrc.fullText.length); val snippet = reflowSrc.fullText.substring(sIdx, eIdx).replace(10.toChar(), ' ').trim().ifEmpty { "(빈 구절)" }; Surface(color = CS2, shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, CBDR), modifier = Modifier.fillMaxWidth().clickable { hlListOpen = false; jumpToCharOffset(hl.startOff) }) { Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text("$dot ${hlPg}쪽", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG); Text(snippet, fontSize = ui.subSp, color = CW, maxLines = 3, overflow = TextOverflow.Ellipsis) }; Text("🗑️", fontSize = 15.sp, modifier = Modifier.clickable { val nx = allHighlights.filterNot { it.id == hl.id }; allHighlights = nx; saveBookHighlights(ctx, nx) }.padding(4.dp)) } } } } } }, confirmButton = { TextButton(onClick = { hlListOpen = false }) { Text("닫기", color = CG, fontWeight = FontWeight.Bold) } }) }; if (sliderBarVisible) {
                Surface(
                    color = CS,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.9.dp, CG.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), SB, CV) {
                                       Text("🎚️ 빠른 이동 (${pgLabel})", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                                if (curTocIdx > 0) ChipBtn("◀장", false, CW) { jumpToCharOffset(tocEntries[curTocIdx - 1].charOffset) }
                                ChipBtn("☰목차", tocOpen, CA) { tocFilterQuery = ""; tocOpen = true }
                                if (curTocIdx >= 0 && curTocIdx + 1 < tocEntries.size) ChipBtn("장▶", false, CW) { jumpToCharOffset(tocEntries[curTocIdx + 1].charOffset) }
                                Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { sliderOpen = false }.padding(horizontal = 4.dp))
                            } 
                        }
                        Slider(
                            value = vIdx.toFloat(),
                            onValueChange = { goToVPage(it.toInt()) },
                            valueRange = 0f..(vPages.size - 1).coerceAtLeast(1).toFloat(),
                            modifier = Modifier.fillMaxWidth().height(24.dp)
                        )
                    }
                }
            }
            val canPrev = vIdx > 0
            val canNext = if (twoPageMode) (vIdx + 2 < vPages.size) else (vIdx + 1 < vPages.size)
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = CV
            ) {
                OutlinedButton(
                    onClick = { goToVPage(vIdx - stepPg) },
                    enabled = canPrev,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.1.dp, if (canPrev) fg.copy(alpha = 0.45f) else mut.copy(alpha = 0.2f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp)
                ) {
                    Text(
                        "◀",
                        fontSize = (ui.bodySp.value + 0.5f).sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canPrev) fg else mut.copy(alpha = 0.35f)
                    )
                }
                ChipBtn(
                    if (sliderOpen) "✕" else "🎚️${vIdx + 1}/${vPages.size}",
                    sliderOpen,
                    CG
                ) {
                    sliderOpen = !sliderOpen
                }
                OutlinedButton(
                    onClick = { goToVPage(vIdx + stepPg) },
                    enabled = canNext,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.1.dp, if (canNext) fg.copy(alpha = 0.45f) else mut.copy(alpha = 0.2f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp)
                ) {
                    Text(
                        "▶",
                        fontSize = (ui.bodySp.value + 0.5f).sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canNext) fg else mut.copy(alpha = 0.35f)
                    )
                }
            }
        }
}
        if (!shelf && act != null && readerMemoOpen && readerMemoMode == 2) {
         val rootBookMemo = remember(notes, act.title) { findBookDedicatedNoteBody(notes, act.title) }
         val rootSaveMemo: (String) -> Unit = { nb -> onUpdateNotes(upsertBookDedicatedNote(notes, act.title, nb)) }
         val rootTargetTx = liveActiveExcerpt.ifBlank { liveSpreadText }
         ReaderFloatingMemoWindow(
          ctx = ctx, ui = ui, maxWPx = rootMaxWPx, maxHPx = rootMaxHPx,
          floatW = memoFloatW, floatH = memoFloatH, floatWPx = memoFloatWPx, floatHPx = memoFloatHPx,
          floatXState = memoFloatXState, floatYState = memoFloatYState,
          floatMin = memoFloatMin, compactWidth = memoFloatCompact,
          onToggleCompactWidth = { memoFloatCompact = !memoFloatCompact; readerPrefs.edit().putBoolean("reader_memo_compact", memoFloatCompact).apply() },
          onPersistPos = { nx, ny -> readerPrefs.edit().putFloat("reader_memo_fx", nx).putFloat("reader_memo_fy", ny).apply() },
          onToggleMin = { memoFloatMin = !memoFloatMin },
          onSwitchToSplit = { hz -> setReaderMemoModePersist(1, hz) },
          onClose = { readerMemoOpen = false },
          bookId = act.id, bookTitle = act.title, pgLabel = livePgLabel,
          currentExcerptOrPage = rootTargetTx, initialBody = rootBookMemo,
          pendingAppendExcerpt = pendingMemoExcerpt, onConsumedPendingExcerpt = { pendingMemoExcerpt = null },
          onSaveBody = rootSaveMemo
         )
        }
if (!shelf && act != null && readerAiOpen && readerAiMode == 2) {
         val fltHasSel = liveActiveExcerpt.isNotBlank()
         ReaderDraggableFloatBox(
          title = "✥ 이동 · 🦙 플로팅 해설",
          maxWPx = rootMaxWPx, maxHPx = rootMaxHPx,
          floatW = aiFloatW, floatH = aiFloatH, floatWPx = aiFloatWPx, floatHPx = aiFloatHPx,
          floatXState = aiFloatXState, floatYState = aiFloatYState,
          floatMin = aiFloatMin, compactWidth = aiFloatCompact,
          onToggleCompactWidth = { aiFloatCompact = !aiFloatCompact; readerPrefs.edit().putBoolean("reader_ai_compact", aiFloatCompact).apply() },
          onPersistPos = { nx, ny -> readerPrefs.edit().putFloat("reader_ai_fx", nx).putFloat("reader_ai_fy", ny).apply() },
          onToggleMin = { aiFloatMin = !aiFloatMin },
          onClose = { readerAiOpen = false },
          extraHeaderActions = { Text("📌상단", fontSize = 11.sp, color = CG, modifier = Modifier.clickable { setReaderAiModePersist(1) }) }
         ) {
          Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
           Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
            Text(if (fltHasSel) "🎯${liveActiveExcerpt.length}자" else "📖$livePgLabel", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (fltHasSel) CA else CG)
            ChipBtn("📌요약", false, CG) { val t = commitExcerptHook(); askPageAi("핵심 내용을 3줄로 요약해줘.", act.title, livePgLabel, t.ifBlank { liveSpreadText }, t.isNotBlank()) }
            ChipBtn("💡해설", false, CA) { val t = commitExcerptHook(); askPageAi("맥락과 의미를 알기 쉽게 해설해줘.", act.title, livePgLabel, t.ifBlank { liveSpreadText }, t.isNotBlank()) }
            ChipBtn("✨핵심문장", false, CW) { val t = commitExcerptHook(); askPageAi("가장 인상 깊은 핵심 문장 1개를 뽑고 설명해줘.", act.title, livePgLabel, t.ifBlank { liveSpreadText }, t.isNotBlank()) }
            if (readerAiAns.isNotBlank() && !readerAiBusy) {
             ChipBtn("💾노트저장", false, CA) { onBookNote(act.title, "[$livePgLabel${if (fltHasSel) " · 선택구절" else ""} AI 해설]" + 10.toChar() + readerAiAns.removeSuffix(" ▍").trim(), false); readerAiNotice = "✅ 『${act.title}』 독서노트에 이어서 저장됨!" }
             ChipBtn("🔈낭독", false, CG) { speakOneShot(ctx, readerAiAns.removeSuffix(" ▍")) }
            }
           }
           readerAiNotice?.let { nt -> Text(nt, fontSize = ui.subSp, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis) }
           Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(readerAiAns.ifBlank { if (fltHasSel) "🎯 선택한 구절(${liveActiveExcerpt.length}자) 대기 중입니다. 위 [📌요약 / 💡해설 / ✨핵심문장] 버튼을 누르거나 질문을 입력해 [↑]를 누르세요." else "📖 위 [📌요약 / 💡해설 / ✨핵심문장] 버튼을 누르거나 아래에 질문을 입력해 [↑]를 누르면 AI가 시작됩니다." }, fontSize = ui.bodySp, color = CW)
           }
           Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = readerAiQ, onValueChange = { readerAiQ = it }, placeholder = { Text(if (fltHasSel) "선택 구절 질문 입력 후 ↑..." else "페이지 질문 입력 후 ↑...", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
            if (readerAiBusy) { Button(onClick = { activeGenId++; readerAiJob?.cancel(); if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }; readerAiBusy = false; readerAiAns = readerAiAns.removeSuffix(" ▍") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) { Text("⏹️", fontSize = ui.subSp) } }
            else { Button(onClick = { val q = readerAiQ.trim(); if (q.isNotEmpty()) { val t = commitExcerptHook(); readerAiQ = ""; askPageAi(q, act.title, livePgLabel, t.ifBlank { liveSpreadText }, t.isNotBlank()) } }) { Text("↑", fontSize = ui.subSp) } }
           }
          }
         }
        }
        if (folderDialogOpen) {
            FolderManageDialog(
                title = "📁 독서대 폴더 관리 및 백업",
                folders = bCats,
                selectedFolder = selCat,
                ui = ui,
                onAddFolder = { t ->
                    if (!bCats.contains(t)) {
                        val nx = bCats + t
                        bCats = nx
                        SmartMemoClassifier.saveBookCats(ctx, nx)
                    }
                },
                onRenameFolder = { oldN, newN ->
                    val (nf, nb) = FolderRegistry.renameBookFolder(ctx, repo, oldN, newN, books)
                    bCats = nf
                    onSaveBooks(nb)
                    if (selCat == oldN) selCat = newN
                },
                onDeleteFolder = { delN ->
                    if (bCats.size > 1) {
                        val nx = bCats.filterNot { it == delN }
                        bCats = nx
                        SmartMemoClassifier.saveBookCats(ctx, nx)
                        if (selCat == delN) selCat = "전체"
                    }
                },
                onBackupAllInTab = { triggerReaderBackup(null) },
                onBackupCurrentFolder = { if (selCat != "전체") triggerReaderBackup(selCat) },
                onRestoreTab = { readerBackupImp.launch(arrayOf("*/*")) },
                statusText = readerBackupMsg,
                onDismiss = { folderDialogOpen = false }
            )
        }
    }
}
