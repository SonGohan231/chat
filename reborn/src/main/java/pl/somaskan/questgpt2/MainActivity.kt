package pl.somaskan.questgpt2

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

private object Draft {
    var text = ""
    val images = mutableListOf<String>()
    var referenceImages: List<String> = emptyList()
}

class MainActivity : PanelActivity()
class MiniActivity : PanelActivity() { override val mini = true }

open class PanelActivity : Activity() {
    protected open val mini = false
    private val bg = 0xff0c1220.toInt()
    private val surface = 0xff172338.toInt()
    private val accent = 0xff77e3cf.toInt()
    private val white = 0xffeef4ff.toInt()
    private val muted = 0xffb4c4d9.toInt()
    private lateinit var status: TextView
    private lateinit var note: TextView
    private lateinit var history: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var draft: EditText
    private lateinit var live: Button
    private lateinit var share: Button
    private lateinit var send: Button
    private lateinit var meter: ProgressBar
    private lateinit var attachments: LinearLayout
    private var lastMessages: List<Message>? = null
    private var permissionAction: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val observer: () -> Unit = { if(!isDestroyed) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = column().apply { setBackgroundColor(bg); setPadding(dp(20),dp(14),dp(20),dp(12)) }
        val header = row()
        header.addView(label(if(mini) "QuestGPT · Mini" else "QuestGPT 2", if(mini) 24f else 29f, true), LinearLayout.LayoutParams(0,dp(48),1f))
        header.addView(button(if(mini) "Otwórz" else "Mini") { switchPanel() })
        header.addView(button("⋯") { menu() }.apply { contentDescription = "Menu i połączenie" })
        root.addView(header)
        status = label("", if(mini) 15f else 17f).apply { setTextColor(muted) }
        root.addView(status)
        meter = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progressTintList=android.content.res.ColorStateList.valueOf(accent) }
        root.addView(meter, LinearLayout.LayoutParams(-1,dp(5)))
        note = label("",16f).apply { setTextColor(0xffffc982.toInt()); setPadding(0,dp(5),0,dp(5)); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(note)
        scroll = ScrollView(this).apply { isFillViewport = true }
        history = column()
        scroll.addView(history)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        attachments = row()
        root.addView(attachments)
        val controls = row()
        live = button("Live") { toggleVoice() }
        share = button("Ekran") { toggleScreen() }
        controls.addView(live); controls.addView(share)
        controls.addView(button("Zdjęcia") { pickImages() })
        controls.addView(button("Klatka") { previewFrame() })
        controls.addView(button("Wycisz") { Hub.voiceService?.mute() ?: Hub.note("Najpierw włącz Live.") })
        controls.addView(button("Przerwij") { Hub.voiceService?.interrupt(); Hub.textCall?.cancel() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(controls) })
        val composer = row()
        draft = EditText(this).apply {
            setTextColor(white); setHintTextColor(muted); hint = "Zapytaj o cokolwiek…"
            textSize = if(mini) 18f else 20f
            minLines=1; maxLines=4
            setPadding(dp(14),dp(12),dp(12),dp(12)); background=shape(surface)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setText(savedInstanceState?.getString("draft") ?: Draft.text)
            setOnEditorActionListener { _, actionId, _ -> if(actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        composer.addView(draft,LinearLayout.LayoutParams(0,-2,1f))
        send = button("Wyślij",true) { submit() }
        composer.addView(send)
        root.addView(composer)
        if(!mini) root.addView(label("Mów w Live lub dołącz obraz. Udostępnianie ekranu zawsze wymaga zgody systemu.",14f).apply {setTextColor(muted)})
        setContentView(root)
        if(Hub.state.messages.isEmpty()) Hub.message("guide", "Cześć! Możesz ze mną pisać, rozmawiać i pokazywać obrazy.\n\nZacznij od ⋯ → Połączenie: zapisz własny klucz OpenAI API i wykonaj test. Potem włącz Live oraz Ekran.\n\nMini panel otworzysz przyciskiem Mini albo z menu Questa podczas gry.")
        receiveShare(intent)
        Updates.check()
    }
    override fun onStart() { super.onStart(); Hub.observe(observer); renderAttachments() }
    override fun onStop() { Draft.text=draft.text.toString(); Hub.unobserve(observer); super.onStop() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("draft",draft.text.toString()); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }

    private fun render() {
        val s = Hub.state
        val age = if(s.lastSentAt > 0) " · OpenAI: ${s.sentFrames} klatek" else ""
        status.text = "${s.voice}\n${s.capture}$age"
        meter.progress = s.micLevel
        live.text = if(s.voiceActive) "Stop Live" else "Live"
        share.text = if(s.sharing) "Stop ekran" else "Ekran"
        send.isEnabled = !s.busy
        send.text = if(s.busy) "Czekaj…" else "Wyślij"
        note.text = s.note
        note.visibility=if(s.note.isBlank()) View.GONE else View.VISIBLE
        if(lastMessages != s.messages) {
            val atBottom = scroll.getChildAt(0)?.let { it.height - (scroll.height + scroll.scrollY) < dp(100) } ?: true
            history.removeAllViews()
            val messages = if(mini) s.messages.takeLast(6) else s.messages
            messages.forEach { message ->
                val card = column().apply { setPadding(dp(14),dp(10),dp(14),dp(12)); background=shape(if(message.role=="user")0xff24394b.toInt() else surface) }
                card.addView(label(when(message.role){ "user" -> "TY"; "guide" -> "NA POCZĄTEK"; else -> "ASYSTENT" },13f,true).apply {setTextColor(accent)})
                card.addView(label(message.text + if(!message.complete) " ▍" else "",if(mini)18f else 20f).apply {setTextIsSelectable(true); setLineSpacing(dp(3).toFloat(),1.08f)})
                history.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(3)})
            }
            lastMessages=s.messages
            if(atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    private fun submit() {
        if(!CredentialStore(this).hasKey()) { connection(); return }
        if(Hub.state.busy || (Hub.state.voiceActive && !Hub.state.voiceReady)) { Hub.note("Poczekaj na gotowość połączenia."); return }
        val typed=draft.text.toString().trim()
        val prompt=typed.ifBlank { if(Draft.images.isNotEmpty()) "Co widzisz na załączonym obrazie?" else "" }
        if(prompt.isBlank()) return
        val freshScreen=Hub.state.frame?.takeIf { Hub.state.sharing && Protocol.fresh(it,SystemClock.elapsedRealtime()) }
        val images = when {
            Draft.images.isNotEmpty() -> Draft.images.toList().also { Draft.referenceImages=it }
            freshScreen != null && !Hub.state.voiceActive -> listOf(freshScreen.dataUrl)
            Hub.state.voiceActive -> emptyList()
            else -> Draft.referenceImages
        }
        if(!Api.ask(this,prompt,images)) return
        draft.text.clear(); Draft.text=""; Draft.images.clear(); renderAttachments()
    }
    private fun toggleVoice() {
        if(Hub.state.voiceActive) { Hub.voiceService?.stopVoice(); return }
        if(Hub.state.busy) { Hub.note("Poczekaj na odpowiedź tekstową przed włączeniem Live."); return }
        if(!CredentialStore(this).hasKey()) { connection(); return }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            permissionAction="voice"; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10); return
        }
        runCatching { startForegroundService(Intent(this,VoiceService::class.java).setAction("START").putExtra("greeting",getPreferences(0).getBoolean("greeting",true))) }
            .onFailure { Hub.note("Nie można włączyć mikrofonu. Uruchom Live z widocznego panelu Questa.") }
    }
    private fun toggleScreen() {
        if(Hub.state.sharing) { Hub.screenService?.stopCapture(); return }
        AlertDialog.Builder(this).setTitle("Udostępnij swój widok")
            .setMessage("W Live do OpenAI będą wysyłane klatki ekranu co około 2 sekundy. Poza Live ekran zostaje w podglądzie, a klatka trafia do OpenAI po wysłaniu pytania.\n\nZa chwilę Quest poprosi o zgodę. Następnie wróć do gry przyciskiem Meta. Stop ekran kończy udostępnianie. Chronione treści mogą dawać pusty obraz.")
            .setPositiveButton("Dalej") { _,_ ->
                runCatching {
                    val manager=getSystemService(MediaProjectionManager::class.java)
                    val request=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                    startActivityForResult(request,20)
                }.onFailure { Hub.note("System nie udostępnia przechwytywania ekranu. Możesz wysłać zrzut z Plików.") }
            }.setNegativeButton("Anuluj",null).show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==10 && permissionAction=="voice") {
            permissionAction=null
            if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) toggleVoice()
            else AlertDialog.Builder(this).setTitle("Mikrofon wyłączony").setMessage("Bez zgody rozmowa Live nie wystartuje. Nadal możesz pisać i wysyłać zdjęcia.")
                .setPositiveButton("Ustawienia aplikacji") { _,_ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
                .setNegativeButton("Zamknij",null).show()
        }
    }
    @Deprecated("Uses framework activity results to keep the native panel small")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==20) {
            if(resultCode!=RESULT_OK || data==null) { Hub.note("Zgoda anulowana. Ekran nie jest udostępniany."); return }
            val bounds=getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val scale=1280.0/maxOf(bounds.width(),bounds.height(),1)
            runCatching { startForegroundService(Intent(this,ScreenService::class.java).setAction("START").putExtra("code",resultCode).putExtra("consent",data)
                .putExtra("width",(bounds.width()*scale).toInt()).putExtra("height",(bounds.height()*scale).toInt())) }
                .onFailure { Hub.note("Nie można uruchomić przechwytywania. Wróć do panelu i ponów zgodę.") }
        }
        if(requestCode==30 && resultCode==RESULT_OK && data!=null) {
            val uris=mutableListOf<Uri>()
            data.clipData?.let { clip -> for(i in 0 until minOf(clip.itemCount,3)) uris+=clip.getItemAt(i).uri }
            if(uris.isEmpty()) data.data?.let {uris+=it}
            importImages(uris)
        }
    }
    private fun pickImages() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),30) }
            .onFailure { Hub.note("Brak aplikacji Pliki. Udostępnij zdjęcie do QuestGPT 2 z galerii Questa.") }
    }
    @Suppress("DEPRECATION")
    private fun receiveShare(incoming:Intent?) {
        if(incoming==null) return
        if(incoming.action==Intent.ACTION_SEND || incoming.action==Intent.ACTION_SEND_MULTIPLE) {
            if(incoming.type?.startsWith("image/")==true) {
                val uris=if(incoming.action==Intent.ACTION_SEND_MULTIPLE)incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    else listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                importImages(uris.take(3))
            } else incoming.getStringExtra(Intent.EXTRA_TEXT)?.let {draft.setText(it.take(16000))}
            incoming.action=null
        }
    }
    private fun importImages(uris:List<Uri>) {
        Hub.note("Wczytuję obrazy…")
        io.execute {
            val loaded=runCatching {
                uris.take(3).map { uri ->
                    require(uri.scheme=="content") {"Obsługiwane są obrazy wybrane w systemowej galerii."}
                    contentResolver.openAssetFileDescriptor(uri,"r")?.use { require(it.length<40L*1024*1024) {"Obraz przekracza 40 MB."} }
                    val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder,info,_ ->
                        val factor=minOf(1.0,1600.0/maxOf(info.size.width,info.size.height))
                        decoder.setTargetSize(maxOf(1,(info.size.width*factor).toInt()),maxOf(1,(info.size.height*factor).toInt()))
                        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try { ByteArrayOutputStream().use {out -> bitmap.compress(Bitmap.CompressFormat.JPEG,85,out); "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)} }
                    finally {bitmap.recycle()}
                }
            }
            runOnUiThread {
                loaded.onSuccess {Draft.images.clear();Draft.images.addAll(it);Hub.note("Dodano ${it.size} obrazów. Wpisz pytanie i wyślij."); if(!isDestroyed)renderAttachments()}
                    .onFailure {Hub.note("Nie można odczytać obrazu: ${it.message}")}
            }
        }
    }
    private fun bitmap(data:String):Bitmap? = runCatching {val bytes=Base64.decode(data.substringAfter(','),Base64.DEFAULT);android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)}.getOrNull()
    private fun renderAttachments() {
        attachments.removeAllViews()
        Draft.images.forEach { url -> attachments.addView(ImageView(this).apply {setImageBitmap(bitmap(url)); contentDescription="Obraz do wysłania"; scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(dp(72),dp(52))) }
        if(Draft.images.isNotEmpty() || Draft.referenceImages.isNotEmpty()) {
            attachments.addView(button(if(Draft.images.isNotEmpty())"Usuń obrazy" else "Zapomnij ostatni obraz") { Draft.images.clear();Draft.referenceImages=emptyList();renderAttachments() })
        }
    }
    private fun previewFrame() {
        val frame=Hub.state.frame
        if(frame==null) { Hub.note("Najpierw włącz Ekran. Potem przejdź do gry i wróć do tego panelu."); return }
        val content=column().apply {setPadding(dp(18),dp(8),dp(18),dp(10))}
        content.addView(ImageView(this).apply {setImageBitmap(bitmap(frame.dataUrl));adjustViewBounds=true;contentDescription="Rzeczywista przechwycona klatka"},LinearLayout.LayoutParams(-1,dp(220)))
        content.addView(label("Klatka ${frame.sequence} · wiek ${(SystemClock.elapsedRealtime()-frame.at)/1000}s" + if(frame.blank) "\nObraz jest ciemny/pusty. Może być chroniony." else "",16f))
        AlertDialog.Builder(this).setTitle("To jest obraz udostępniany przez Questa").setView(content)
            .setPositiveButton("Dołącz klatkę") { _,_ -> Draft.images.clear();Draft.images.add(frame.dataUrl);renderAttachments();Hub.note("Klatka dołączona. Wpisz pytanie i wyślij.") }
            .setNegativeButton("Zamknij",null).show()
    }
    private fun switchPanel() {
        Draft.text=draft.text.toString()
        startActivity(Intent(this,if(mini) MainActivity::class.java else MiniActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    private fun menu() {
        val items=arrayOf("Połączenie z OpenAI","Sprawdź działanie","Jak używać podczas gry","Nowa rozmowa","Eksportuj rozmowę","Pobierz aktualizację","Zatrzymaj wszystko")
        AlertDialog.Builder(this).setTitle("QuestGPT 2").setItems(items) { _,which -> when(which) {
            0->connection();1->diagnostics();2->help()
            3->AlertDialog.Builder(this).setTitle("Wyczyścić rozmowę?").setMessage("Usunie lokalną historię i zakończy Live.")
                .setPositiveButton("Wyczyść") {_,_->Hub.clear();Draft.images.clear();Draft.referenceImages=emptyList();renderAttachments()}.setNegativeButton("Anuluj",null).show()
            4->export()
            5->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://questgpt-2.songoku222.chatgpt.site")))
            6->{Hub.voiceService?.stopVoice();Hub.screenService?.stopCapture();Hub.textCall?.cancel();Hub.note("Mikrofon i udostępnianie zatrzymane.")}
        }}.show()
    }
    private fun connection() {
        val store=CredentialStore(this)
        val content=column().apply {setPadding(dp(20),dp(12),dp(20),dp(12))}
        content.addView(label("Własny klucz OpenAI API",21f,true))
        content.addView(label("Połączenie bezpośrednie z OpenAI. Klucz jest szyfrowany w goglach i nie jest częścią APK. API ma osobne rozliczenie od abonamentu ChatGPT.",16f))
        val key=EditText(this).apply {hint=if(store.hasKey()) "Zapisany · wpisz tylko, by zmienić" else "Wklej klucz sk-…"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO; setSingleLine()}
        content.addView(key)
        content.addView(label("Model tekstu i zdjęć",16f))
        val textModel=EditText(this).apply{setText(store.textModel());setSingleLine()};content.addView(textModel)
        content.addView(label("Model rozmowy Live",16f))
        val voiceModel=EditText(this).apply{setText(store.realtimeModel());setSingleLine()};content.addView(voiceModel)
        val greeting=android.widget.CheckBox(this).apply{text="Powitanie głosowe po włączeniu Live";isChecked=getPreferences(0).getBoolean("greeting",true)};content.addView(greeting)
        val testStatus=label(Hub.state.apiTest,16f);content.addView(testStatus)
        val dialog=AlertDialog.Builder(this).setTitle("Połączenie").setView(ScrollView(this).apply{addView(content)})
            .setPositiveButton("Zapisz i testuj",null).setNegativeButton("Zamknij",null).setNeutralButton("Usuń klucz",null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    Hub.voiceService?.stopVoice()
                    if(key.text.toString().isNotBlank())store.saveKey(key.text.toString())
                    store.saveModels(textModel.text.toString(),voiceModel.text.toString())
                    check(store.hasKey()) {"Wklej klucz API, aby wykonać test."}
                    key.text.clear();getPreferences(0).edit().putBoolean("greeting",greeting.isChecked).apply()
                    Api.test(this);dialog.dismiss();diagnostics()
                }.onFailure{testStatus.text=Protocol.safe(it.message.orEmpty())}
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                Hub.voiceService?.stopVoice();Hub.textCall?.cancel();store.clearKey();key.text.clear()
                Hub.change{it.copy(apiTest="Klucz usunięty",note="Klucz API został usunięty.")};dialog.dismiss()
            }
        };dialog.show()
    }
    private fun diagnostics() {
        val content=column().apply {setPadding(dp(20),dp(12),dp(20),dp(12))}
        val result=label("",17f);content.addView(result)
        content.addView(button("Test rzeczywistej odpowiedzi API",true){Api.test(this)})
        content.addView(button("Sprawdź mikrofon i Live"){toggleVoice()})
        content.addView(button("Sprawdź podgląd ekranu"){previewFrame()})
        content.addView(button("Zezwól na powiadomienia") {if(Build.VERSION.SDK_INT>=33)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)})
        val updater:()->Unit = {
            val s=Hub.state
            result.text="QuestGPT ${BuildConfig.VERSION_NAME}\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n\n${s.apiTest}\n\n${s.voice}\nMikrofon: ${s.micLevel}/100\n\n${s.capture}\nKlatki potwierdzone przez OpenAI: ${s.sentFrames}\n\nTest sprzętowy zaliczony dopiero, gdy usłyszysz odpowiedź i podgląd pokaże grę."
        }
        val dialog=AlertDialog.Builder(this).setTitle("Sprawdź działanie").setView(ScrollView(this).apply{addView(content)}).setPositiveButton("Zamknij",null).create()
        dialog.setOnDismissListener{Hub.unobserve(updater)};Hub.observe(updater);dialog.show()
    }
    private fun help() {
        AlertDialog.Builder(this).setTitle("Asystent podczas gry")
            .setMessage("1. Zapisz klucz w Połączeniu i sprawdź odpowiedź API.\n\n2. Włącz Live oraz Ekran. Potwierdź zgodę systemu.\n\n3. Przejdź do gry. Mikrofon i przechwytywanie działają w usługach pierwszoplanowych.\n\n4. Przyciskiem Meta otwórz menu i wybierz QuestGPT 2 — aplikacja zgłasza mini panel do uruchamiania nad VR. Możesz też wcześniej wybrać Mini.\n\n5. Powiedz pytanie. Klatka pokaże dokładnie to, co dostarcza system.\n\nMeta kontroluje widoczność panelu i może zatrzymać lub wyciszyć usługę. Nie każda gra pozwala na przechwycenie. Jeśli mikrofon przejęła gra, wyłącz jej czat głosowy. Gdy system zakończy ekran, potrzebna jest nowa zgoda.\n\nPrzycisk Stop kończy sesję. Nie ma ukrytego nasłuchu ani automatycznego włączania po restarcie.")
            .setPositiveButton("Rozumiem",null).show()
    }
    private fun export() {
        runCatching {
            val file=File(cacheDir,"exports/QuestGPT-rozmowa.txt").apply{parentFile?.mkdirs();writeText(Hub.state.messages.joinToString("\n\n"){"${it.role}: ${it.text}"})}
            val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Zapisz rozmowę"))
        }.onFailure{Hub.note("Nie można wyeksportować: ${it.message}")}
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun label(value:String,size:Float=18f,bold:Boolean=false)=TextView(this).apply{text=value;textSize=size;setTextColor(white);if(bold)setTypeface(null,Typeface.BOLD)}
    private fun button(value:String,primary:Boolean=false,action:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;textSize=16f;minWidth=dp(48);minimumWidth=dp(48);minHeight=dp(48)
        setTextColor(if(primary)bg else white);background=shape(if(primary)accent else surface)
        setPadding(dp(14),dp(6),dp(14),dp(6));layoutParams=LinearLayout.LayoutParams(-2,dp(48)).apply{setMargins(dp(3),dp(4),dp(3),dp(4))}
        setOnClickListener{action()}
    }
    private fun shape(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(12).toFloat()}
}
