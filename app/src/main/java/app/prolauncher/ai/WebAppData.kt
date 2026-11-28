package app.prolauncher.ai
import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

data class WebAppProject(val id: Long, val title: String, val htmlCode: String, val updatedAt: String, val folder: String = "유틸리티", val icon: String = "🌐")
val WEBAPP_ICON_PRESETS = listOf("🌐", "🧮", "⏱️", "📝", "📅", "🎮", "💰", "📊", "🎵", "📚", "🔧", "⚡", "🏋️", "🍳", "🧠", "✨")
val SEED_CALC = """<!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>더치페이 계산기</title><style>*{box-sizing:border-box;font-family:system-ui,sans-serif}body{margin:0;padding:12px;background:#0f172a;color:#f8fafc}.c{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:12px;margin-bottom:8px}input{width:100%;padding:8px;border-radius:8px;border:1px solid #475569;background:#0f172a;color:#fff;margin-top:4px}.r{display:flex;gap:6px;margin-top:8px}button{flex:1;padding:9px;border:none;border-radius:8px;background:#10b981;color:#042f2e;font-weight:700}</style></head><body><div class="c"><b style="color:#34d399">🧮 더치페이 · 할인 계산기</b><input id="a" type="number" value="48000" oninput="u()" placeholder="총액"><div class="r"><input id="d" type="number" value="0" oninput="u()" placeholder="할인%"><input id="p" type="number" value="3" min="1" oninput="u()" placeholder="인원"></div><div class="r"><button onclick="p.value=2;u()">2명</button><button onclick="p.value=4;u()">4명</button><button onclick="sv()">기록저장</button></div></div><div class="c"><div id="o" style="font-size:21px;font-weight:800;color:#fbbf24"></div><div id="h" style="font-size:12px;color:#cbd5e1;margin-top:6px"></div></div><script>function u(){const f=Math.round((+a.value||0)*(1-Math.min(100,Math.max(0,+d.value||0))/100)),n=Math.max(1,+p.value||1),e=Math.ceil(f/n/100)*100;o.textContent=e.toLocaleString()+'원 / 인 (합계 '+f.toLocaleString()+'원)';h.innerHTML=(JSON.parse(localStorage.getItem('pl_dc')||'[]')).join('<br>')||'저장된 기록 없음';return {f,n,e};}function sv(){const r=u(),l=JSON.parse(localStorage.getItem('pl_dc')||'[]');l.unshift('• '+r.f.toLocaleString()+'원 / '+r.n+'명 → '+r.e.toLocaleString()+'원');localStorage.setItem('pl_dc',JSON.stringify(l.slice(0,5)));u();}u();</script></body></html>"""

val SEED_POMO = """<!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>포모도로 타이머</title><style>body{margin:0;padding:16px;background:#1c1917;color:#fafaf9;font-family:system-ui,sans-serif;text-align:center}.b{background:#292524;border-radius:16px;padding:18px;border:1px solid #44403c}#ck{font-size:48px;font-weight:800;color:#fbbf24;margin:10px 0}button{padding:9px 12px;margin:3px;border:none;border-radius:9px;font-weight:700;background:#10b981;color:#052e16}</style></head><body><div class="b"><div id="m" style="color:#34d399;font-weight:700">🔥 집중 (25분)</div><div id="ck">25:00</div><button id="bt" onclick="tg()">▶ 시작</button><button onclick="ps(25,'🔥 집중(25분)')">25분</button><button onclick="ps(5,'☕ 휴식(5분)')">5분</button><div id="st" style="margin-top:8px;font-size:12px;color:#a8a29e"></div></div><script>let s=1500,t=null;function up(){ck.textContent=String(Math.floor(s/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');st.textContent='완료 세션: '+(localStorage.getItem('pl_pm')||0)+'회';}function tg(){if(t){clearInterval(t);t=null;bt.textContent='▶ 재개';}else{bt.textContent='⏸ 정지';t=setInterval(()=>{if(s>0){s--;up();}else{clearInterval(t);t=null;bt.textContent='▶ 시작';localStorage.setItem('pl_pm',(+localStorage.getItem('pl_pm')||0)+1);up();alert('완료!');}},1000);}}function ps(n,l){if(t){clearInterval(t);t=null;}s=n*60;m.textContent=l;bt.textContent='▶ 시작';up();}up();</script></body></html>"""

val STARTER_HTML = """<!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>새 웹앱</title><style>body{margin:0;padding:16px;font-family:system-ui,sans-serif;background:#f8fafc;color:#0f172a}.card{background:#fff;border-radius:14px;padding:16px;box-shadow:0 2px 8px rgba(0,0,0,.08)}button{padding:10px 16px;border:none;border-radius:10px;background:#0f766e;color:#fff;font-weight:700}</style></head><body><div class="card"><h3>🌐 나만의 미니 웹앱</h3><p id="msg">AI 빌더로 원하는 웹앱을 바로 제작해 보세요!</p><button onclick="msg.textContent='실행 확인: '+new Date().toLocaleTimeString()">테스트</button></div></body></html>"""

fun loadWebAppFolders(ctx: Context): List<String> = runCatching {
 val raw = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).getString("webapp_folders_v1", null) ?: return listOf("유틸리티", "생산성", "게임·취미")
 val a = JSONArray(raw); buildList { for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it) } }.ifEmpty { listOf("유틸리티", "생산성", "게임·취미") }
}.getOrElse { listOf("유틸리티", "생산성", "게임·취미") }

fun saveWebAppFolders(ctx: Context, f: List<String>) {
 val a = JSONArray(); f.distinct().filter { it.isNotBlank() }.forEach { a.put(it.trim()) }
 ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("webapp_folders_v1", a.toString()).apply()
}

fun loadWebApps(ctx: Context): List<WebAppProject> = runCatching {
 val raw = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).getString("webapp_projects_v1", null)
 if (raw == null) return listOf(WebAppProject(9001L, "더치페이 계산기", SEED_CALC, "기본 내장", "유틸리티", "🧮"), WebAppProject(9002L, "포모도로 타이머", SEED_POMO, "기본 내장", "생산성", "⏱️")).also { saveWebApps(ctx, it) }
 val a = JSONArray(raw); buildList { for (i in 0 until a.length()) a.getJSONObject(i).let { o -> add(WebAppProject(o.getLong("id"), o.optString("title", "웹앱"), o.optString("htmlCode", ""), o.optString("updatedAt", ""), o.optString("folder", "유틸리티").ifBlank { "유틸리티" }, o.optString("icon", "🌐").ifBlank { "🌐" })) } }
}.getOrElse { emptyList() }

fun saveWebApps(ctx: Context, list: List<WebAppProject>) {
 val a = JSONArray(); list.forEach { w -> a.put(JSONObject().put("id", w.id).put("title", w.title).put("htmlCode", w.htmlCode).put("updatedAt", w.updatedAt).put("folder", w.folder).put("icon", w.icon.ifBlank { "🌐" })) }
 ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("webapp_projects_v1", a.toString()).apply()
}

fun extractCleanHtmlFromAi(raw: String): String {
 val nb = raw.substringBefore("\n\n⏱️").substringBefore("\n⏱️").removeSuffix(" ▍").trim()
 Regex("```(?:html|HTML)?\\s*([\\s\\S]*?)```").find(nb)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.contains("<") }?.let { return it }
 val u = nb.indexOf("```html", ignoreCase = true).takeIf { it >= 0 }?.let { it + 7 } ?: nb.indexOf("```").takeIf { it >= 0 }?.let { it + 3 }
 val c = if (u != null && u < nb.length) nb.substring(u).trim() else nb
 val d = c.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: c.indexOf("<html", ignoreCase = true)
 return if (d >= 0) { val e = c.lastIndexOf("</html>", ignoreCase = true); if (e > d) c.substring(d, e + 7).trim() else c.substring(d).trim() } else c
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveWebAppRunner(htmlCode: String, reloadTick: Int, modifier: Modifier = Modifier) {
 var lastHtml by remember { mutableStateOf("") }; var lastTick by remember { mutableStateOf(-1) }
 AndroidView(
  factory = { c -> WebView(c).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.databaseEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.useWideViewPort = true; settings.loadWithOverviewMode = true; webChromeClient = WebChromeClient(); webViewClient = WebViewClient() } },
  modifier = modifier,
  update = { wv -> if (lastHtml != htmlCode || lastTick != reloadTick) { lastHtml = htmlCode; lastTick = reloadTick; wv.loadDataWithBaseURL("https://app.prolauncher.local/", htmlCode, "text/html", "UTF-8", null) } }
 )
}

fun webAppPkgOf(id: Long): String = "webapp://$id"

fun isWebAppPinnedHome(ctx: Context, id: Long): Boolean {
 val favs = ctx.getSharedPreferences("pl_ai_v2_prefs", Context.MODE_PRIVATE).getStringSet("fav_pkgs", emptySet()) ?: emptySet()
 return favs.contains(webAppPkgOf(id))
}
fun applyWebAppPatch(orig: String, raw: String): Pair<String, Int> { val cl = raw.substringBefore("\n\n⏱️").substringBefore("\n⏱️").removeSuffix(" ▍").trim(); val rx = Regex("<".repeat(3) + "SEARCH\\s*\\n([\\s\\S]*?)\\n===\\s*\\n([\\s\\S]*?)\\n" + ">".repeat(3) + "REPLACE"); var res = orig; var h = 0; rx.findAll(cl).forEach { m -> val s = m.groupValues[1]; val r = m.groupValues[2]; if (s.isNotEmpty() && res.contains(s)) { res = res.replaceFirst(s, r); h++ } else if (s.trim().length >= 6 && res.contains(s.trim())) { res = res.replaceFirst(s.trim(), r.trim()); h++ } }; return res to h }

fun toggleWebAppPinnedHome(ctx: Context, id: Long, title: String, icon: String = "🌐"): Boolean {
 val repo = LauncherRepository(ctx)
 val pkg = webAppPkgOf(id)
 val lbl = "${icon.ifBlank { "🌐" }} $title"
 repo.toggleFavorite(pkg, lbl)
 packageChangeTick++
 return repo.getFavoritePackages().contains(pkg)
}

@Composable
fun PinnedWebAppRunnerOverlay(appId: Long, onClose: () -> Unit, onEditInStudio: (Long) -> Unit) {
 val ctx = androidx.compose.ui.platform.LocalContext.current
 val aiCfg = remember { loadAiCfg(ctx) }
 val ui = rememberUi(aiCfg.uiScale)
 val app = remember(appId) { loadWebApps(ctx).find { it.id == appId } }
 var reloadTick by remember { mutableStateOf(0) }
 androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(CBG).padding(horizontal = ui.hPad, vertical = ui.vPad), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
  androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), SB, CV) {
   androidx.compose.material3.Text("${app?.icon?.ifBlank { "🌐" } ?: "🌐"} ${app?.title ?: "웹앱"}", fontSize = ui.titleSp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = CW, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
   androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
    ChipBtn("🔄 새로고침", false, CW) { reloadTick++ }
    ChipBtn("🛠️ 스튜디오편집", false, CA) {
     ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit()
      .putLong("webapp_active_id_v1", appId)
      .putString("webapp_active_title_v1", app?.title ?: "웹앱")
      .putString("webapp_active_code_v1", app?.htmlCode ?: STARTER_HTML)
      .apply()
     onEditInStudio(appId)
    }
    ChipBtn("✕ 홈으로", true, CG) { onClose() }
   }
  }
  androidx.compose.material3.Surface(color = CS, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(0.9.dp, CG.copy(alpha = 0.65f)), modifier = Modifier.weight(1f).fillMaxWidth()) {
   LiveWebAppRunner(app?.htmlCode ?: STARTER_HTML, reloadTick, Modifier.fillMaxSize())
  }
 }
}
