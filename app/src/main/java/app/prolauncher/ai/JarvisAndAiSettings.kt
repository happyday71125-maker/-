package app.prolauncher.ai
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
class BriefingReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent?) {
        val act = intent?.action
        if (act != null) {
            if (act == android.content.Intent.ACTION_BOOT_COMPLETED ||
                act == android.content.Intent.ACTION_MY_PACKAGE_REPLACED ||
                act == android.content.Intent.ACTION_TIME_CHANGED ||
                act == android.content.Intent.ACTION_TIMEZONE_CHANGED
            ) {
                ScheduleBriefingManager.rescheduleAllBriefingAlarms(context)
            }
            return
        }
        when (intent?.getStringExtra("mode") ?: "") {
            "STOP_BRIEF" -> ScheduleBriefingManager.stopBriefingPlayback(context)
            "ITEM" -> ScheduleBriefingManager.postBriefingNotification(
                context,
                intent?.getStringExtra("title") ?: "⏰ 일정 알림",
                intent?.getStringExtra("body") ?: "예약된 메모 일정입니다.",
                (System.currentTimeMillis() % 10000L).toInt() + 8000
            )
            "AUTO_BRIEF" -> ScheduleBriefingManager.triggerScheduledBriefingFromAlarm(
                context,
                intent?.getStringExtra("slot_label") ?: ""
            )
        }
    }
}
class JarvisMicService : android.app.Service() { override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null; override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int { runCatching { val chId = "pl_jarvis_fg_ch"; val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager; if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(chId, "오디오 낭독 · 자비스 백그라운드", android.app.NotificationManager.IMPORTANCE_LOW)); val pi = android.app.PendingIntent.getActivity(this, 7799, android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE); val isTts = ReaderTtsRuntime.on || SupertonicTtsEngine.playing || briefingSpeakingNow; val ttl = if (ReaderTtsRuntime.on) "📖 독서대 연속 낭독 중 (화면 OFF 유지)" else if (briefingSpeakingNow) "⏰ 자동 일정 브리핑 낭독 중" else if (isTts) "🔊 Pro Launcher 음성 재생 중" else "🤖 Pro Launcher 자비스 동작 중"; val sub = if (ReaderTtsRuntime.on) (ReaderTtsRuntime.status ?: "화면이 꺼져도 자동으로 페이지를 넘기며 계속 읽습니다") else if (wakeOnly) "호출어([${wakeWord}]) 대기 중 · 화면이 꺼져도 유지됩니다" else "백그라운드 오디오·음성 활성 중"; val nb = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, chId) else android.app.Notification.Builder(this); val nf = nb.setSmallIcon(if (isTts) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_btn_speak_now).setContentTitle(ttl).setContentText(sub).setContentIntent(pi).setOngoing(true).build(); if (android.os.Build.VERSION.SDK_INT >= 34) { val hasMic = (JarvisRuntime.on || vOn) && checkSelfPermission("android.permission.RECORD_AUDIO") == 0; var fgType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC; if (hasMic) fgType = fgType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE; startForeground(7799, nf, fgType) } else startForeground(7799, nf) }; return START_STICKY } }
class ProJarvisAccessibilityService : android.accessibilityservice.AccessibilityService() { companion object { var inst: ProJarvisAccessibilityService? = null }; override fun onServiceConnected() { super.onServiceConnected(); inst = this }; override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}; override fun onInterrupt() {}; override fun onDestroy() { if (inst === this) inst = null; super.onDestroy() }; fun doAct(a: Int): Boolean = runCatching { performGlobalAction(a) }.getOrDefault(false); fun doScroll(down: Boolean): Boolean = runCatching { if (android.os.Build.VERSION.SDK_INT >= 24) { val dm = resources.displayMetrics; val cx = dm.widthPixels * 0.5f; val sy = if (down) dm.heightPixels * 0.76f else dm.heightPixels * 0.24f; val ey = if (down) dm.heightPixels * 0.22f else dm.heightPixels * 0.78f; val p = android.graphics.Path().apply { moveTo(cx, sy); lineTo(cx, ey) }; val g = android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p, 0L, 320L)).build(); dispatchGesture(g, null, null) } else false }.getOrDefault(false) }
object JarvisController { var sleeping by mutableStateOf(false); var torchOn by mutableStateOf(false)
private val aliasMap = mapOf("카톡" to listOf("카카오톡", "kakao"), "카카오톡" to listOf("카카오톡", "kakao"), "유튜브뮤직" to listOf("youtube music", "유튜브 뮤직"), "유튜브" to listOf("youtube", "유튜브"), "인스타" to listOf("instagram", "인스타그램"), "크롬" to listOf("chrome", "크롬"), "인터넷" to listOf("인터넷", "browser", "chrome"), "카메라" to listOf("카메라", "camera"), "갤러리" to listOf("갤러리", "사진", "photos", "gallery"), "설정" to listOf("설정", "settings"), "시계" to listOf("시계", "clock", "알람"), "계산기" to listOf("계산기", "calculator"), "지도" to listOf("지도", "맵", "maps", "네이버 지도", "카카오맵", "티맵"), "전화" to listOf("전화", "phone", "dialer"), "문자" to listOf("메시지", "messages", "문자"), "배달" to listOf("배달의민족", "쿠팡이츠", "요기요"), "당근" to listOf("당근", "karrot"), "토스" to listOf("토스", "toss"), "네이버" to listOf("네이버", "naver"), "쿠팡" to listOf("쿠팡", "coupang"), "넷플릭스" to listOf("넷플릭스", "netflix"), "멜론" to listOf("멜론", "melon"), "플레이스토어" to listOf("play 스토어", "play store", "vending"))
fun launchAppByName(ctx: Context, target: String): String? { val q = target.replace(Regex("(앱|어플|프로그램|좀|빨리|지금|바로)"), "").replace(" ", "").trim(); if (q.isEmpty()) return null; val pm = ctx.packageManager; val apps = runCatching { pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0) }.getOrDefault(emptyList()); val cands = aliasMap.entries.firstOrNull { q.contains(it.key, true) }?.value ?: listOf(q); val hit = apps.firstOrNull { ri -> val lb = ri.loadLabel(pm).toString().replace(" ", ""); val pk = ri.activityInfo.packageName; cands.any { c -> val nc = c.replace(" ", ""); lb.equals(nc, true) || lb.contains(nc, true) || pk.contains(nc, true) } }; if (hit != null) { val lb = hit.loadLabel(pm).toString(); val li = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); if (li != null) { runCatching { ctx.startActivity(li) }; return "🚀 [$lb] 앱을 실행합니다." } }; return null }
fun setTorch(ctx: Context, on: Boolean): String = runCatching { val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager; val id = cm.cameraIdList.firstOrNull { c -> cm.getCameraCharacteristics(c).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: cm.cameraIdList.firstOrNull(); if (id != null) { cm.setTorchMode(id, on); torchOn = on; if (on) "🔦 손전등(플래시)을 켰습니다." else "🔦 손전등(플래시)을 껐습니다." } else "⚠️ 플래시를 지원하지 않는 기기입니다." }.getOrDefault("⚠️ 손전등 제어 중 오류가 발생했습니다.")
fun batteryReport(ctx: Context): String = runCatching { val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager; val lv = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY); val chg = if (android.os.Build.VERSION.SDK_INT >= 23) bm.isCharging else false; "🔋 현재 배터리는 ${lv}%이며 ${if (chg) "충전 중입니다." else "사용 중입니다."}" }.getOrDefault("🔋 배터리 정보를 읽을 수 없습니다.")
fun handle(ctx: Context, rawIn: String, callName: String, onNavTab: (Int) -> Unit, onAddMemo: (String) -> String, getNotes: () -> List<NoteItem>, fromVoice: Boolean = true): Pair<Boolean, String> { var q = rawIn.trim(); if (q.isEmpty()) return true to ""; val cn = callName.trim().ifBlank { wakeWord.ifBlank { "자비스" } }; val (woke, stripped) = matchWakePhrase(q, cn); val inFollowUp = System.currentTimeMillis() < wakeFollowUpUntil; if (fromVoice && (sleeping || (wakeOnly && !inFollowUp)) && !woke) return true to ""; if (woke) { sleeping = false; wakeFollowUpUntil = System.currentTimeMillis() + 18000L; q = stripped; if (q.isEmpty() || q in listOf("있어", "들려", "불렀어", "일어나", "네", "응", "어")) return true to "🤖 네, [${cn}] 듣고 있습니다! 말씀하세요." }; val chgM = Regex("^(?:깨우기\\s*명령어를|깨우기\\s*명령어|호출어를|호출어|내\\s*호칭을|너의\\s*이름을)\\s*([가-힣a-zA-Z0-9 ]{1,12}?)(?:으로|로)\\s*(?:바꿔줘|바꿔|변경해줘|변경해|설정해줘|설정해)$").find(q); if (chgM != null) { val nw = chgM.groupValues[1].trim(); if (nw.isNotEmpty()) { setWakeWord(ctx, nw); return true to "👂 깨우기(호출) 명령어를 [${nw}](으)로 변경했습니다! 이제 [${nw}]라고 불러주세요." } }; val chgS = Regex("^(?:쉬기\\s*명령어를|쉬기\\s*명령어|대기\\s*명령어를|대기\\s*명령어)\\s*([가-힣a-zA-Z0-9 ]{1,12}?)(?:으로|로)\\s*(?:바꿔줘|바꿔|변경해줘|변경해|설정해줘|설정해)$").find(q); if (chgS != null) { val ns = chgS.groupValues[1].trim(); if (ns.isNotEmpty()) { setSleepWord(ctx, ns); return true to "🌙 쉬기 명령어를 [${ns}](으)로 변경했습니다! 이제 [${ns}]라고 하면 깨우기 전까지 대기합니다." } }; if (q in listOf("호출어 모드 켜줘", "호출어 모드 켜", "호출어 전용 모드")) { setWakeOnly(ctx, true); return true to "👂 호출어 대기 모드를 켰습니다. 이제 [${cn}]라고 부를 때만 반응합니다." }; if (q in listOf("호출어 모드 꺼줘", "호출어 모드 꺼", "상시 반응 모드")) { setWakeOnly(ctx, false); return true to "🎙️ 상시 반응 모드로 전환했습니다. 호출어 없이 바로 말씀하세요." }; if (matchSleepPhrase(q, sleepWord)) { sleeping = true; wakeFollowUpUntil = 0L; return true to "🌙 네, [${cn}]라고 깨우기 전까지 그 단어만 감지하며 조용히 대기하겠습니다." }; if (q in listOf("자비스 종료", "자비스 꺼줘", "음성비서 종료", "음성 비서 꺼줘", "마이크 꺼줘", "녹음 중지")) return true to "STOP_JARVIS"; val svc = ProJarvisAccessibilityService.inst; fun reqAcc(): String { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; return "⚙️ 다른 앱 위에서 스크롤·뒤로가기·잠금을 쓰려면 접근성 설정에서 [Pro Launcher 자비스 음성제어]를 1회 켜주세요." }; if (q.contains("접근성 설정")) return true to reqAcc(); if (q in listOf("뒤로", "뒤로 가", "뒤로가", "뒤로 가기", "뒤로가기", "이전 화면", "뒤로가줘", "뒤로 가줘")) return true to (if (svc?.doAct(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true) "◀️ 뒤로 이동했습니다." else reqAcc()); if (q in listOf("홈으로", "홈으로 가", "홈으로 와", "홈 화면", "홈화면", "런처 홈", "홈으로 가줘")) { onNavTab(2); svc?.doAct(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME); return true to "🏠 홈 화면으로 이동했습니다." }; if (q.contains("최근 앱") || q.contains("멀티태스킹")) return true to (if (svc?.doAct(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) == true) "🗂️ 최근 앱 목록을 열었습니다." else reqAcc()); if (q.contains("알림창") || q.contains("알림 내려") || q.contains("알림 보여")) return true to (if (svc?.doAct(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true) "🔔 알림창을 열었습니다." else reqAcc()); if (q.contains("빠른 설정") || q.contains("제어센터")) return true to (if (svc?.doAct(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) == true) "🎛️ 빠른 설정창을 열었습니다." else reqAcc()); if (q.contains("화면 잠가") || q.contains("화면 꺼") || q.contains("잠금화면")) return true to (if (android.os.Build.VERSION.SDK_INT >= 28 && svc?.doAct(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true) "🔒 화면을 잠갔습니다." else reqAcc()); if (q.contains("화면 캡처") || q.contains("스크린샷") || q.contains("캡쳐해") || q.contains("캡처해")) return true to (if (android.os.Build.VERSION.SDK_INT >= 28 && svc?.doAct(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) == true) "📸 화면을 캡처했습니다." else reqAcc()); if (q.contains("아래로 스크롤") || q.contains("아래로 내려") || q.contains("밑으로 내려") || q.contains("스크롤 내려") || q in listOf("내려줘", "다음 영상")) return true to (if (svc?.doScroll(true) == true) "⏬ 아래로 스크롤했습니다." else reqAcc()); if (q.contains("위로 스크롤") || q.contains("위로 올려") || q.contains("스크롤 올려") || q in listOf("올려줘", "이전 영상")) return true to (if (svc?.doScroll(false) == true) "⏫ 위로 스크롤했습니다." else reqAcc())
if ((q.contains("손전등") || q.contains("플래시") || q.contains("후레쉬") || q.contains("후레시") || q.contains("라이트")) && (q.contains("켜") || q.contains("온"))) return true to setTorch(ctx, true); if ((q.contains("손전등") || q.contains("플래시") || q.contains("후레쉬") || q.contains("후레시") || q.contains("라이트")) && (q.contains("꺼") || q.contains("오프"))) return true to setTorch(ctx, false); val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager; if (q.contains("볼륨") || q.contains("소리") || q.contains("음량")) { val mx = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1); val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC); val num = Regex("(\\d{1,3})").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull(); if (q.contains("최대") || q.contains("최고") || q.contains("풀로")) { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mx, android.media.AudioManager.FLAG_SHOW_UI); return true to "🔊 미디어 볼륨을 최대로 설정했습니다." }; if (num != null && num in 0..100) { val tv = ((mx * num) / 100f).toInt().coerceIn(0, mx); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, tv, android.media.AudioManager.FLAG_SHOW_UI); return true to "🔊 볼륨을 ${num}%로 설정했습니다." }; if (q.contains("키워") || q.contains("높여") || q.contains("크게") || q.contains("올려")) { val nv = (cur + maxOf(1, mx / 4)).coerceAtMost(mx); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, nv, android.media.AudioManager.FLAG_SHOW_UI); return true to "🔊 볼륨을 높였습니다 (${(nv * 100) / mx}%)." }; if (q.contains("줄여") || q.contains("낮춰") || q.contains("작게") || q.contains("내려")) { val nv = (cur - maxOf(1, mx / 4)).coerceAtLeast(0); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, nv, android.media.AudioManager.FLAG_SHOW_UI); return true to "🔉 볼륨을 낮췄습니다 (${(nv * 100) / mx}%)." } }; if (q.contains("진동 모드") || q.contains("진동으로")) { runCatching { am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE }; return true to "📳 진동 모드로 전환했습니다." }; if (q.contains("무음 모드") || q.contains("무음으로")) { runCatching { am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT }.onFailure { runCatching { am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE } }; return true to "🔇 무음 모드로 전환했습니다." }; if (q.contains("벨소리 모드") || q.contains("소리 모드")) { runCatching { am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL }; return true to "🔔 일반 소리 모드로 전환했습니다." }; if (q.contains("배터리") && (q.contains("몇") || q.contains("잔량") || q.contains("알려") || q.contains("얼마") || q.length <= 8)) return true to batteryReport(ctx); if (q.contains("몇 시") || q.contains("현재 시각") || q.contains("현재 시간") || q.contains("오늘 며칠") || q.contains("무슨 요일") || q == "오늘 날짜") return true to "🕒 현재는 ${java.text.SimpleDateFormat("M월 d일 EEEE a h시 m분", java.util.Locale.KOREA).format(java.util.Date())}입니다."; if (q.contains("와이파이 설정")) { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; return true to "📶 와이파이 설정 화면을 열었습니다." }; if (q.contains("블루투스 설정")) { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; return true to "🟦 블루투스 설정 화면을 열었습니다." }
if (q.contains("타이머")) { val minM = Regex("(\\d+)\\s*분").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0; val secM = Regex("(\\d+)\\s*초").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0; val totS = minM * 60 + secM; if (totS > 0) { runCatching { ctx.startActivity(android.content.Intent("android.intent.action.SET_TIMER").putExtra("android.intent.extra.alarm.LENGTH", totS).putExtra("android.intent.extra.alarm.SKIP_UI", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; return true to "⏲️ ${if (minM > 0) "${minM}분 " else ""}${if (secM > 0) "${secM}초 " else ""}타이머를 시작했습니다." } }; if (q.contains("알람") && (q.contains("맞춰") || q.contains("설정") || q.contains("깨워") || q.contains("울려"))) { val ps = ScheduleBriefingManager.parseLineSchedule(q); if (ps != null) { val cal = java.util.Calendar.getInstance().apply { timeInMillis = ps.first }; val hr = cal.get(java.util.Calendar.HOUR_OF_DAY); val mn = cal.get(java.util.Calendar.MINUTE); runCatching { ctx.startActivity(android.content.Intent("android.intent.action.SET_ALARM").putExtra("android.intent.extra.alarm.HOUR", hr).putExtra("android.intent.extra.alarm.MINUTES", mn).putExtra("android.intent.extra.alarm.SKIP_UI", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; onAddMemo("⏰ 알람 ${ps.second} $q"); return true to "⏰ ${ps.second} 시스템 알람 및 일정 알림을 설정했습니다." } }; if (q.contains("브리핑") || q in listOf("오늘 일정 알려줘", "오늘 할 일 알려줘", "일정 읽어줘", "오늘 일정")) return true to ScheduleBriefingManager.buildBriefingSummary(getNotes()); val memoRx = Regex("(?:이라고|라고)?\\s*(?:음성\\s*메모로|음성\\s*메모에|음성\\s*메모|메모로|메모에|메모)?\\s*(?:저장\\s*해\\s*주세요|저장\\s*해\\s*줘요|저장\\s*해\\s*줘|저장\\s*해|저장|메모\\s*해\\s*주세요|메모\\s*해\\s*줘요|메모\\s*해\\s*줘|메모\\s*해|기록\\s*해\\s*줘요|기록\\s*해\\s*줘|기록\\s*해|적어\\s*줘요|적어\\s*줘|적어|일정\\s*등록\\s*해\\s*줘|일정\\s*추가\\s*해\\s*줘)[.?!~ ]*$"); val isVoiceMemoReq = fromVoice || q.contains("음성메모") || q.contains("음성 메모") || q.contains("메모로"); if (memoRx.containsMatchIn(q) && (q.contains("메모") || q.contains("기록") || q.contains("적어") || q.contains("일정"))) { val memoTx = q.replace(memoRx, "").trim(); if (memoTx.isNotEmpty()) return true to onAddMemo(if (isVoiceMemoReq) "__VOICEMEMO__" + memoTx else memoTx) }; if (q.contains("작성창") || q.contains("마크다운") || q.contains("에디터 열어")) { onNavTab(0); return true to "✍️ 마크다운 작성 스튜디오로 이동합니다." }; if (q.contains("독서대 열어") || q.contains("독서 화면") || q == "독서대") { onNavTab(1); return true to "📖 독서대 화면으로 이동합니다." }; if (q.contains("메모장 열어") || q.contains("메모 화면") || q == "메모장") { onNavTab(3); return true to "📝 메모장 화면으로 이동합니다." }; if (q.contains("챗봇 열어") || q.contains("채팅창 열어") || q == "챗봇") { onNavTab(4); return true to "🦙 AI 챗봇 화면으로 이동합니다." }; if (q.contains("검색해") || q.contains("찾아줘") || (q.contains("유튜브에서") && (q.contains("틀어") || q.contains("보여") || q.contains("재생")))) { val sq = q.replace(Regex("^(유튜브에서|네이버에서|구글에서|지도에서|웹에서)\\s*"), "").replace(Regex("(검색해줘|검색해봐|검색해|찾아줘|틀어줘|보여줘|재생해줘|재생해)$"), "").trim(); if (sq.isNotEmpty()) { val enc = java.net.URLEncoder.encode(sq, "UTF-8"); val pr = "ht" + "tps:" + "/" + "/"; val cm = ".c" + "om"; val url = when { q.contains("유튜브") -> pr + "w" + "ww.youtube" + cm + "/results?search_query=$enc"; q.contains("네이버") -> pr + "m.search.naver" + cm + "/search.naver?query=$enc"; q.contains("지도") -> "geo:0,0?q=$enc"; else -> pr + "w" + "ww.google" + cm + "/search?q=$enc" }; runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; return true to "🔎 [$sq] 검색 결과를 열었습니다." } }; val appRx = Regex("\\s*(열어줘|열어봐|열어|실행해줘|실행해봐|실행해|실행|틀어줘|틀어봐|틀어|켜줘|켜봐|켜|오픈)$"); if (appRx.containsMatchIn(q)) { val appQ = q.replace(appRx, "").trim(); launchAppByName(ctx, appQ)?.let { return true to it } }; return false to q } }
@Composable fun EmojiHelpDialog() {
if (!showEmojiHelp) return
var hTab by remember { mutableStateOf(0) }
AlertDialog(
onDismissRequest = { showEmojiHelp = false },
title = { Text("❓ 런처 기능 & 마크다운 작성 도움말", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CG) },
text = {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
SettingsTabBar(listOf("📱 앱 기능 안내", "✍️ 마크다운 문법"), hTab) { hTab = it }
Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
if (hTab == 0) {
Text(
"[✍️ 마크다운 작성 스튜디오 (독서대 왼쪽 탭)]\n" +
"• 마크다운 편집·미리보기·동시보기와 음성 전사를 지원하며, [💾저장 / 📂문서함 / 📂파일열기 / 📕PDF내보내기]로 문서를 관리합니다.\n" +
"• [◫분할AI]로 화면을 나누거나 [🪟플로팅AI]를 켜서 상단 바(✥)를 드래그해 원하는 위치로 창을 옮기며 AI 첨삭·초안 작성을 받을 수 있습니다.\n\n" +
"[📖 독서대: 연속 2쪽 조판 · 독서노트 이어쓰기 · 버튼형 이동]\n" +
"• 설정이나 AI 해설창을 열어도 페이지가 바뀌지 않고 현재 쪽 그대로 스크롤해 읽을 수 있습니다.\n" +
"• 하단 [🎚️쪽수] 버튼을 눌렀을 때만 슬라이더가 열려 오작동을 방지하며, 발췌·해설 저장 시 같은 책은 『책제목』 전용 독서노트 하나에 자동으로 이어서 기록됩니다.\n\n" +
"[🏠 홈 & 📝 스마트 메모장]\n" +
"• 홈에서 위로 올리면 앱 서랍이 열리고, 앱 서랍을 아래로 내리면 닫힙니다.\n" +
"• 메모 작성뿐 아니라 기존 메모 [✏️ 수정] 화면에서도 [🎙️ 음성전사]로 음성을 이어 적을 수 있습니다.",
fontSize = 12.sp, lineHeight = 18.sp, color = CW
)
} else {
MarkdownGuideContent()
}
}
}
},
confirmButton = { Button(onClick = { showEmojiHelp = false }) { Text("확인") } }
)
}
suspend fun buildAiChatReply(
qRaw: String, aiCfg: AiRagConfig, history: List<ChatMsg>,
notes: List<NoteItem>, books: List<BookItem>, cats: List<String>, ai: LocalAiNoteEngine, onStream: ((String) -> Unit)? = null, genId: Long = 0L
): String = withContext(Dispatchers.IO) {
val q = qRaw.trim(); val low = q.lowercase()
val useRag = aiCfg.ragOn || wantsMemoRagLookup(q)
val memCtx = if (useRag) buildRagContextSmart(q, aiCfg, notes, books, cats) else ""
val webCtx = if (ddgWebOn) fetchDuckDuckGoContext(q) else ""
val ragContext = listOf(memCtx, webCtx).filter { it.isNotBlank() }.joinToString(10.toChar().toString() + 10.toChar().toString())
val liveResp = callLlmHttp(aiCfg.sysPrompt, ragContext, history, q, aiCfg.apiUrl, aiCfg.apiModel, aiCfg.apiKey, onStream, genId, aiCfg.temp, aiCfg.ctxSize, aiCfg.maxTok.coerceAtLeast(512), aiCfg.cpuMode, aiCfg.histTurns)
if (!liveResp.isNullOrBlank()) return@withContext liveResp
if (!useRag) {
if (low.contains("요약") || q.lines().size >= 3 || q.length >= 80) return@withContext "✨ [핵심 요약]\n${SMC.summarizeByLength(q, true)}"
return@withContext "🤖 [오프라인 AI 답변]\n질문하신 '$q'에 대한 답변입니다:\n• 현재 오프라인 상태여서 내장 엔진이 응답했습니다. 로컬 GGUF 모델을 등록하거나 네트워크에 연결하면 상세한 AI 답변을 받을 수 있습니다."
}
if (notes.isEmpty()) return@withContext "현재 저장된 메모가 없습니다. [📝 메모] 탭에서 메모를 먼저 작성해 주세요."
if (low.contains("요약") || low.contains("정리") || low.contains("브리핑") || low.contains("전체")) {
val imps = notes.filter { SMC.isImportant(it) }
return@withContext buildString {
append("🗂️ [전체 메모 RAG 분석 브리핑 · 총 ${notes.size}건]\n")
if (imps.isNotEmpty()) { append("★ 중요 메모 (${imps.size}건):\n"); imps.forEach { n -> append("• [${SMC.categoryOf(n, cats)}] ${n.content.replace("\n", " ").take(65)}\n") } }
append("📌 전체 메모 요약:\n")
notes.take(8).forEach { n ->
val s = SMC.summarizeByLength(n.content, false).ifBlank { n.content.replace("\n", " ").take(60) }
append("• [${SMC.categoryOf(n, cats)} · ${n.createdAt}] $s\n")
}
}.trim()
}
if (low.contains("할일") || low.contains("할 일") || low.contains("체크") || low.contains("일정")) {
val unc = notes.flatMap { n -> n.content.lines().filter { it.trimStart().startsWith("☐ ") }.map { "[${SMC.categoryOf(n, cats)}] $it" } }
return@withContext if (unc.isNotEmpty()) "✅ 미완료 체크리스트 (${unc.size}건):\n" + unc.joinToString("\n") else "미완료된 체크리스트(☐)가 없습니다."
}
ai.askRag(q, notes, books, aiCfg.ragBooks)
}
@Composable
fun SettingsTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
Surface(color = Color(0xFF090E18), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, Color(0xFF1F2C42)), modifier = Modifier.fillMaxWidth()) {
Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
tabs.forEachIndexed { idx, label ->
val sel = selected == idx
Surface(
color = if (sel) CG.copy(alpha = 0.22f) else Color.Transparent,
shape = RoundedCornerShape(9.dp),
border = if (sel) BorderStroke(1.dp, CG.copy(alpha = 0.8f)) else null,
modifier = Modifier.weight(1f).clickable { onSelect(idx) }
) {
Text(label, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) CG else CM, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
}
}
}
}
}
@Composable
fun AiPromptRagPanel(
ctx: Context, aiCfg: AiRagConfig, ui: ResponsiveUi,
onUpdate: (AiRagConfig) -> Unit, fixedTab: Int = -1
) {
var testReport by remember { mutableStateOf<String?>(null) }
var subTab by remember { mutableStateOf(0) }
val curTab = if (fixedTab >= 0) fixedTab else subTab
val pScope = rememberCoroutineScope()
val chatGPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
if (u != null) { testReport = "⏳ 채팅용 GGUF 모델 등록 중..."; pScope.launch { testReport = importGgufModelUri(ctx, u, false) } }
}
val embGPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
if (u != null) { testReport = "⏳ 임베딩젬마2 텍스트 GGUF 등록·검증 중..."; pScope.launch { testReport = importGgufModelUri(ctx, u, true) } }
}
val stPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { us -> if (us.isNotEmpty()) { testReport = "⏳ Supertonic TTS 모델·보이스 파일 등록 중..."; pScope.launch { testReport = SupertonicTtsEngine.importUris(ctx, us) } } }
val stExpPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { u -> if (u != null) { testReport = "⏳ Supertonic 모델·보이스 ZIP 백업 내보내는 중..."; pScope.launch { testReport = SupertonicTtsEngine.exportBackupZip(ctx, u) } } }
val chatExpPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) { testReport = "⏳ 채팅 LLM GGUF 백업 내보내는 중..."; pScope.launch { testReport = SupertonicTtsEngine.exportGgufToUri(ctx, u, false) } } }
val embExpPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) { testReport = "⏳ 임베딩젬마2 GGUF 백업 내보내는 중..."; pScope.launch { testReport = SupertonicTtsEngine.exportGgufToUri(ctx, u, true) } } }
SideEffect { SupertonicTtsEngine.init(ctx) }
val wrapMod = if (fixedTab >= 0) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().heightIn(max = ui.panelMaxH)
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF25344E)), modifier = wrapMod) {
Column(Modifier.then(if (fixedTab >= 0) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(ui.cardPad + 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
if (fixedTab < 0) {
SettingsTabBar(listOf("🔊 음성·TTS", "🦙 AI 모델", "🗂️ 프롬프트·RAG"), curTab) { subTab = it }
}
testReport?.let { rep ->
Surface(color = Color(0xFF102220), shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, CG.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()) {
Text(rep, fontSize = ui.subSp, color = CG, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
}
}
when (curTab) {
0 -> {
Surface(color = Color(0xFF0A101D), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("📦 Supertonic 3 모델 용량별 선택 & 자동 다운로드", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA)
Text(SupertonicTtsEngine.statusText, fontSize = ui.subSp, color = CM)
SupertonicTtsEngine.dlProgress?.let { dp -> Text(dp, fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG) }
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn("⬇️🪶 초경량 INT8 (105MB·추천)", SupertonicTtsEngine.modelTier == "INT8", CG) { if (!SupertonicTtsEngine.dlBusy) SupertonicTtsEngine.downloadSupertonicTier(ctx, "INT8") { testReport = it } else SupertonicTtsEngine.cancelDownload() }
ChipBtn("⬇️🚀 초고속 INT8-Fast (105MB)", SupertonicTtsEngine.modelTier == "INT8_FAST", CG) { if (!SupertonicTtsEngine.dlBusy) SupertonicTtsEngine.downloadSupertonicTier(ctx, "INT8_FAST") { testReport = it } else SupertonicTtsEngine.cancelDownload() }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn("⬇️⚡ 경량 FP16 (195MB)", SupertonicTtsEngine.modelTier == "FP16", CA) { if (!SupertonicTtsEngine.dlBusy) SupertonicTtsEngine.downloadSupertonicTier(ctx, "FP16") { testReport = it } else SupertonicTtsEngine.cancelDownload() }
ChipBtn("⬇️👑 무손실 FP32 원본 (398MB)", SupertonicTtsEngine.modelTier == "FP32", CW) { if (!SupertonicTtsEngine.dlBusy) SupertonicTtsEngine.downloadSupertonicTier(ctx, "FP32") { testReport = it } else SupertonicTtsEngine.cancelDownload() }
if (SupertonicTtsEngine.dlBusy) ChipBtn("⏹️ 다운로드 취소", false, Color(0xFFF87171)) { SupertonicTtsEngine.cancelDownload(); testReport = "⏹️ 다운로드 취소됨" }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (SupertonicTtsEngine.enabled) "🔊 Supertonic 활성" else "📱 기본 TTS 사용중", SupertonicTtsEngine.enabled, CG) { SupertonicTtsEngine.setMode(ctx, !SupertonicTtsEngine.enabled) }
ChipBtn("🔈 샘플 듣기", false, CW) { speakOneShot(ctx, "안녕하세요! 프로 런처 AI 슈퍼토닉 쓰리 한국어 음성 테스트입니다.") }
ChipBtn("📂 파일넣기", false, CA) { stPick.launch(arrayOf("*/*")) }
ChipBtn("📤 ZIP백업", false, CW) { stExpPick.launch("supertonic_3_${SupertonicTtsEngine.modelTier.lowercase()}_backup.zip") }
ChipBtn("🗑️ 비우기", false, Color(0xFFF87171)) { testReport = SupertonicTtsEngine.clearModels(ctx) }
}
}
}
Surface(color = Color(0xFF0A101D), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Box(Modifier.padding(12.dp)) { SupertonicControlSection(ctx, ui, compact = false) }
}
Surface(color = Color(0xFF0A101D), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
  var wakeDraft by remember(wakeWord) { mutableStateOf(wakeWord) }
var sleepDraft by remember(sleepWord) { mutableStateOf(sleepWord) }
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("🤖 자비스 쉬기 · 깨우기 명령어 설정", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CG)
Text("마이크가 켜진 상태에서 쉬기 명령어를 말하면 깨우기 명령어가 들릴 때만 깨어납니다. 음성으로도 변경할 수 있습니다.", fontSize = ui.subSp, color = CM)
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
OutlinedTextField(value = wakeDraft, onValueChange = { wakeDraft = it }, label = { Text("깨우기 (예: 자비스)") }, singleLine = true, modifier = Modifier.weight(1f))
OutlinedTextField(value = sleepDraft, onValueChange = { sleepDraft = it }, label = { Text("쉬기 (예: 쉬어)") }, singleLine = true, modifier = Modifier.weight(1f))
}
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn("💾 명령어 저장", true, CG) {
setWakeWord(ctx, wakeDraft)
setSleepWord(ctx, sleepDraft)
testReport = "✅ 깨우기=[${wakeWord}] · 쉬기=[${sleepWord}] 명령어가 저장되었습니다."
}
ChipBtn("🔄 기본값(자비스/쉬어)", false, CM) {
setWakeWord(ctx, "자비스")
setSleepWord(ctx, "쉬어")
wakeDraft = "자비스"
sleepDraft = "쉬어"
testReport = "🔄 기본 명령어(깨우기: 자비스 · 쉬기: 쉬어)로 복원됨"
}
}
}
}
}
1 -> {
Surface(color = Color(0xFF0A101D), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("🦙 온디바이스 GGUF 채팅 & 임베딩젬마2 관리", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CG)
Text("• 채팅 LLM: " + AiResidentManager.gemmaName.take(24) + (if (AiResidentManager.gemmaMb > 0L) " (${AiResidentManager.gemmaMb}MB)" else ""), fontSize = ui.subSp, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("⬇️ 채팅LLM 자동받기", AiResidentManager.gemmaMb > 0L, CG) { SupertonicTtsEngine.downloadGgufAuto(ctx, false) { testReport = it } }
ChipBtn("📂 GGUF 파일넣기", false, CG) { chatGPick.launch(arrayOf("*/*")) }
ChipBtn("📤 GGUF 백업", false, CW) { chatExpPick.launch(findLocalGguf()?.name ?: "local_chat_llm_backup.gguf") }
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
Text("• 시맨틱 검색 임베딩: " + AiResidentManager.embedName.take(22) + (if (AiResidentManager.embedMb > 0L) " (${AiResidentManager.embedMb}MB)" else ""), fontSize = ui.subSp, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("⬇️ 임베딩젬마2 자동받기", AiResidentManager.embedMb > 0L, CA) { SupertonicTtsEngine.downloadGgufAuto(ctx, true) { testReport = it } }
ChipBtn("📂 임베딩 파일넣기", false, CA) { embGPick.launch(arrayOf("*/*")) }
ChipBtn("📤 임베딩 백업", false, CW) { embExpPick.launch(findEmbedGguf()?.name ?: "embeddinggemma2_backup.gguf") }
ChipBtn("🗑️ 전체비우기", false, Color(0xFFF87171)) { testReport = AiResidentManager.clearStorage(ctx) }
}
}
}
Surface(color = Color(0xFF0A101D), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("⚡ AI 연결 방식 및 호출 이름", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("🧠 오프라인 단독(GGUF)", aiCfg.apiUrl == "local_gguf" || aiCfg.apiUrl.isBlank(), CG) { val u = aiCfg.copy(apiUrl = "local_gguf"); onUpdate(u); saveAiCfg(ctx, u); testReport = "✅ 오프라인 단독 GGUF 모드" }
ChipBtn("🦙 외부 Ollama(11434)", aiCfg.apiUrl.contains("11434"), CA) { val u = aiCfg.copy(apiUrl = "ht" + "tp:" + "/" + "/127.0.0.1:11434"); onUpdate(u); saveAiCfg(ctx, u); testReport = "✅ 로컬 Ollama(11434) 연동" }
val gemUrl = listOf("ht" + "tps:", "", "generativelanguage.googleapis" + ".c" + "om", "v1beta/openai/chat/completions").joinToString("/")
ChipBtn("✨ Gemini API", aiCfg.apiUrl.contains("googleapis"), CW) { val u = aiCfg.copy(apiUrl = gemUrl, apiModel = if (aiCfg.apiModel.startsWith("gemini")) aiCfg.apiModel else "gemini-2.5-flash"); onUpdate(u); saveAiCfg(ctx, u); testReport = "✨ Gemini API 엔드포인트 설정됨" }
}
OutlinedTextField(value = aiCfg.apiUrl, onValueChange = { val u = aiCfg.copy(apiUrl = it); onUpdate(u); saveAiCfg(ctx, u) }, label = { Text("모드 또는 서버 주소 (local_gguf / 서버 URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = aiCfg.apiModel, onValueChange = { val u = aiCfg.copy(apiModel = it); onUpdate(u); saveAiCfg(ctx, u) }, label = { Text("AI 호칭 / 모델명 (예: 자비스)") }, singleLine = true, modifier = Modifier.weight(1f))
OutlinedTextField(value = aiCfg.apiKey, onValueChange = { val u = aiCfg.copy(apiKey = it); onUpdate(u); saveAiCfg(ctx, u) }, label = { Text("API Key (오프라인 비움)") }, singleLine = true, modifier = Modifier.weight(1f))
}
}
}
}
else -> {
Surface(color = Color(0xFF0A101D), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("✍️ AI 역할 시스템 프롬프트", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA)
Text("기본값 복원", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable { val u = aiCfg.copy(sysPrompt = DEF_SYS_PROMPT); onUpdate(u); saveAiCfg(ctx, u) })
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
PROMPT_PRESETS.forEach { (label, promptTxt) ->
ChipBtn(label, aiCfg.sysPrompt == promptTxt, CA) { val u = aiCfg.copy(sysPrompt = promptTxt); onUpdate(u); saveAiCfg(ctx, u) }
}
}
OutlinedTextField(value = aiCfg.sysPrompt, onValueChange = { val u = aiCfg.copy(sysPrompt = it); onUpdate(u); saveAiCfg(ctx, u) }, label = { Text("시스템 프롬프트 직접 편집") }, singleLine = false, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
}
}
Surface(color = Color(0xFF0A101D), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Column(Modifier.weight(1f)) {
Text("🗂️ 내 메모 · 독서대 RAG 연동", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW)
Text("켜면 저장된 메모와 도서 내용을 근거로 답변합니다", fontSize = ui.subSp, color = CM)
}
Switch(checked = aiCfg.ragOn, onCheckedChange = { val u = aiCfg.copy(ragOn = it); onUpdate(u); saveAiCfg(ctx, u) })
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("전체 스마트 참조", aiCfg.ragScope == "ALL") { val u = aiCfg.copy(ragScope = "ALL"); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("상위 3건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 3) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 3); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("상위 5건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 5) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 5); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("상위 10건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 10) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 10); onUpdate(u); saveAiCfg(ctx, u) }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("📚 독서대 본문 포함", aiCfg.ragBooks) { val u = aiCfg.copy(ragBooks = !aiCfg.ragBooks); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("★ 중요 메모만 참조", aiCfg.ragImpOnly) { val u = aiCfg.copy(ragImpOnly = !aiCfg.ragImpOnly); onUpdate(u); saveAiCfg(ctx, u) }
}
}
}
}
}
}
}
}
object AiResidentManager {
var gemmaName by mutableStateOf("미연결")
var embedName by mutableStateOf("미연결")
var gemmaMb by mutableStateOf(0L)
var embedMb by mutableStateOf(0L)
fun clearStorage(ctx: Context): String {
appCtxRef = ctx.applicationContext
releaseAllLocalModels()
gemmaMb = 0L; embedMb = 0L; gemmaName = "미연결"; embedName = "미연결"
var b = 0L
listOf("models", "llm_models", "embed_models").forEach { d -> java.io.File(ctx.applicationContext.filesDir, d).listFiles()?.forEach { b += it.length(); it.delete() } }
return "🗑️ GGUF 채팅 · 임베딩젬마2 모델 비우기 완료 (" + (b / 1048576L) + "MB 확보)"
}
}
