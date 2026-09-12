package pl.somaskan.questgptfree

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : FreePanelActivity()
class MiniActivity : FreePanelActivity() { override val mini=true }

open class FreePanelActivity : Activity() {
    protected open val mini=false
    private val backgroundColor=0xff0c1422.toInt()
    private val surface=0xff19283b.toInt()
    private val ink=0xffeef5ff.toInt()
    private val accent=0xff89e5ca.toInt()
    private lateinit var body: FrameLayout
    private lateinit var address: TextView
    private lateinit var loading: ProgressBar
    private var snapshotStatus: TextView?=null
    internal var web: WebView?=null
        private set
    private var webMode=false
    private var fileCallback: ValueCallback<Array<Uri>>?=null
    private var multipleFiles=false
    private var requestedTypes=arrayOf<String>()
    private var pendingPermission: PermissionRequest?=null
    private var permissionDialog: AlertDialog?=null
    private var chooserDialog: AlertDialog?=null
    private var savedWebState: Bundle?=null
    private var pageProblem: String?=null
    private val popups=mutableSetOf<WebView>()
    private val handler=Handler(Looper.getMainLooper())
    private val refreshStatus=object: Runnable {
        override fun run() { snapshotStatus?.text=SnapshotStore.status(this@FreePanelActivity); handler.postDelayed(this,500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root=column().apply { setBackgroundColor(backgroundColor) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(dp(12)+bars.left,dp(6)+bars.top,dp(12)+bars.right,dp(6)+maxOf(bars.bottom,keyboard.bottom))
            insets
        }
        val toolbar=row()
        toolbar.addView(button("Start") { showHome() },LinearLayout.LayoutParams(dp(76),dp(50)))
        toolbar.addView(label(if(mini) "Free · Mini" else "QuestGPT Free",if(mini)18f else 24f,true).apply {
            maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; gravity=Gravity.CENTER_VERTICAL
        },LinearLayout.LayoutParams(0,dp(50),1f))
        toolbar.addView(button(if(mini) "Duży" else "Mini") {
            startActivity(Intent(this,if(mini) MainActivity::class.java else MiniActivity::class.java)
                .putExtra("panel",webMode).putExtra("page",web?.url?.takeIf { WebPolicy.chat(it) } ?: WebPolicy.HOME))
        },LinearLayout.LayoutParams(dp(76),dp(50)))
        root.addView(toolbar)
        address=label("Bez klucza API · limity Twojego konta ChatGPT",13f).apply {setTextColor(accent); maxLines=2}
        root.addView(address)
        loading=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; visibility=View.GONE }
        root.addView(loading,LinearLayout.LayoutParams(-1,dp(3)))
        body=FrameLayout(this)
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val controls=row()
        controls.addView(button("Wstecz") { goBack() },LinearLayout.LayoutParams(0,dp(52),1f))
        controls.addView(button("Przeglądarka") { openBrowser() },LinearLayout.LayoutParams(0,dp(52),1.5f))
        controls.addView(button("⋯") { menu() }.apply {contentDescription="Opcje panelu"},LinearLayout.LayoutParams(dp(54),dp(52)))
        root.addView(controls)
        setContentView(root)
        savedWebState=savedInstanceState?.getBundle("web")
        if(savedInstanceState?.getBoolean("panel") == true || intent.getBooleanExtra("panel",false)) showWeb()
        else showHome()
    }

    private fun showHome() {
        cancelNativeRequests()
        webMode=false
        web?.onPause()
        body.removeAllViews()
        address.text="Bez klucza API · limity Twojego konta ChatGPT"
        loading.visibility=View.GONE
        val content=column().apply {setPadding(dp(6),dp(16),dp(6),dp(14))}
        content.addView(label("Twój ChatGPT\nw zasięgu ręki",if(mini)28f else 38f,true))
        content.addView(label("Zaloguj się na stronie ChatGPT. Możesz używać konta Free lub swojego Plus. QuestGPT Free nie wymaga doładowania API.",18f).apply {setPadding(0,dp(12),0,dp(12))})
        content.addView(button("Otwórz ChatGPT",true) { openBrowser() },fullButton())
        content.addView(label("Zalecane: przeglądarka obsługuje logowanie i zapamiętuje Twoje konto.",14f).apply {setPadding(0,dp(4),0,dp(10))})
        content.addView(button("Panel w aplikacji") { showWeb() },fullButton())
        content.addView(label("Panel zależy od zgodności strony z goglami. Gdy logowanie lub głos nie działa, wybierz Przeglądarka.",14f).apply {setPadding(0,dp(6),0,dp(16))})
        val capture=row()
        capture.addView(button("Zrzut za 5 s") { requestSnapshot() },LinearLayout.LayoutParams(0,dp(56),1f))
        capture.addView(button("Ostatni zrzut") { previewSnapshot() },LinearLayout.LayoutParams(0,dp(56),1f))
        content.addView(capture)
        snapshotStatus=label(SnapshotStore.status(this),15f).apply {setPadding(0,dp(8),0,dp(14));setTextColor(accent)}
        content.addView(snapshotStatus)
        content.addView(button("Jak pokazać zdjęcie i użyć głosu?") { help() },fullButton())
        content.addView(label("Niezależna aplikacja dla Questa. Usługę ChatGPT i jej limity zapewnia OpenAI.",13f).apply {setPadding(0,dp(16),0,0)})
        body.addView(ScrollView(this).apply {isFillViewport=true;addView(content)},FrameLayout.LayoutParams(-1,-1))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb() {
        snapshotStatus=null
        webMode=true
        body.removeAllViews()
        var fresh=false
        val browser=web ?: WebView(this).also { w ->
            fresh=true
            web=w
            w.setBackgroundColor(backgroundColor)
            w.settings.apply {
                javaScriptEnabled=true
                domStorageEnabled=true
                allowFileAccess=false
                allowContentAccess=true // Only user-selected content:// attachments.
                mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture=true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically=false
                builtInZoomControls=true
                displayZoomControls=false
                useWideViewPort=true
                loadWithOverviewMode=true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(w,false)
            w.webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                    val url=request.url.toString()
                    if(!request.isForMainFrame) return false
                    if(WebPolicy.inside(url)) return false
                    if(request.hasGesture() && WebPolicy.external(url)) openExternal(request.url)
                    else if(request.hasGesture()) message("Ten odnośnik wymaga przeglądarki. Otwórz ChatGPT przyciskiem Przeglądarka.")
                    return true
                }
                override fun onPageStarted(view: WebView,url: String?,favicon: Bitmap?) {
                    cancelNativeRequests()
                    pageProblem=null
                    if(webMode) { address.text=WebPolicy.host(url) ?: "Strona"; loading.visibility=View.VISIBLE }
                }
                override fun onPageFinished(view: WebView,url: String?) {
                    CookieManager.getInstance().flush()
                    if(webMode) { address.text=pageProblem ?: ((WebPolicy.host(url) ?: "Strona") + " · bez API"); loading.visibility=View.GONE }
                }
                override fun onReceivedError(view: WebView,request: WebResourceRequest,error: WebResourceError) {
                    if(request.isForMainFrame) pageError("Nie udało się wczytać strony. Sprawdź internet lub wybierz Przeglądarka.")
                }
                override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,response: WebResourceResponse) {
                    if(request.isForMainFrame && response.statusCode>=400) pageError("Strona zwróciła błąd ${response.statusCode}. Spróbuj w przeglądarce.")
                }
                override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean {
                    cancelNativeRequests(); (view.parent as? ViewGroup)?.removeView(view); view.destroy(); web=null
                    showHome(); message("Panel został zamknięty przez system. Możesz otworzyć go ponownie lub użyć przeglądarki.")
                    return true
                }
            }
            w.webChromeClient=object: WebChromeClient() {
                override fun onProgressChanged(view: WebView,progress: Int) { if(webMode) loading.progress=progress }
                override fun onShowFileChooser(view: WebView,callback: ValueCallback<Array<Uri>>,params: FileChooserParams): Boolean {
                    if(!webMode || !WebPolicy.chat(view.url)) { callback.onReceiveValue(null); return true }
                    fileCallback?.onReceiveValue(null)
                    fileCallback=callback
                    multipleFiles=params.mode==FileChooserParams.MODE_OPEN_MULTIPLE
                    requestedTypes=params.acceptTypes.filter { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+*-]+")) }.toTypedArray()
                    val last=SnapshotStore.latest(this@FreePanelActivity)
                    val acceptsImage=requestedTypes.isEmpty() || requestedTypes.any { it=="*/*" || it.startsWith("image/") }
                    if(last!=null && acceptsImage) {
                        chooserDialog=AlertDialog.Builder(this@FreePanelActivity).setTitle("Dołącz plik w ChatGPT")
                            .setItems(arrayOf("Wybierz zdjęcie lub plik","Ostatni zrzut Questa")) { _,which ->
                                if(which==1) deliverFiles(arrayOf(last)) else pickFiles()
                            }.setOnCancelListener { deliverFiles(null) }.show()
                    } else pickFiles()
                    return true
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        if(!webMode || !WebPolicy.nativeCapability(request.origin.toString(),web?.url) ||
                            !request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {request.deny();return@runOnUiThread}
                        pendingPermission?.deny();pendingPermission=request
                        permissionDialog=AlertDialog.Builder(this@FreePanelActivity).setTitle("Mikrofon dla ChatGPT")
                            .setMessage("Zezwolić stronie chatgpt.com na mikrofon podczas rozmowy? Zakończ rozmowę przyciskiem na stronie.")
                            .setPositiveButton("Zezwól") { _,_ ->
                                if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) grantMicrophone()
                                else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),42)
                            }.setNegativeButton("Anuluj") { _,_ -> denyMicrophone() }
                            .setOnCancelListener {denyMicrophone()}.show()
                    }
                }
                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if(pendingPermission===request) {pendingPermission=null;permissionDialog?.dismiss()}
                }
                override fun onCreateWindow(view: WebView,isDialog: Boolean,isUserGesture: Boolean,resultMsg: Message): Boolean {
                    if(!isUserGesture) return false
                    // Read only the destination of a user-opened popup; never inspect login forms or cookies.
                    val popup=WebView(this@FreePanelActivity)
                    popups.add(popup)
                    popup.webViewClient=object: WebViewClient() {
                        override fun shouldOverrideUrlLoading(child: WebView,request: WebResourceRequest): Boolean {
                            val target=request.url.toString()
                            if(target=="about:blank") return false
                            if(WebPolicy.inside(target)) w.loadUrl(target) else if(WebPolicy.external(target)) openExternal(request.url)
                            handler.post { if(popups.remove(popup)) popup.destroy() }
                            return true
                        }
                    }
                    (resultMsg.obj as? WebView.WebViewTransport)?.webView=popup
                    resultMsg.sendToTarget()
                    handler.postDelayed({if(popups.remove(popup)) runCatching {popup.destroy()}},30000)
                    return true
                }
            }
            w.setDownloadListener { url,_,_,_,_ ->
                if(WebPolicy.external(url)) openExternal(Uri.parse(url))
                else message("Ten plik pobierz przez zwykłą przeglądarkę po zalogowaniu do ChatGPT.")
            }
        }
        (browser.parent as? ViewGroup)?.removeView(browser)
        body.addView(browser,FrameLayout.LayoutParams(-1,-1))
        address.text="chatgpt.com · bez API"
        browser.onResume()
        if(fresh) {
            val restored=savedWebState?.let { browser.restoreState(it) }
            savedWebState=null
            if(restored==null) browser.loadUrl(intent.getStringExtra("page")?.takeIf {WebPolicy.chat(it)} ?: WebPolicy.HOME)
        }
    }

    private fun pickFiles() {
        val types=requestedTypes
        val request=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if(types.size==1) types[0] else "*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multipleFiles)
        if(types.size>1) request.putExtra(Intent.EXTRA_MIME_TYPES,types)
        runCatching {startActivityForResult(request,41)}.onFailure {deliverFiles(null);message("Nie znaleziono aplikacji Pliki. Spróbuj dołączyć zdjęcie w przeglądarce.")}
    }
    private fun deliverFiles(uris: Array<Uri>?) {
        val callback=fileCallback ?: return
        fileCallback=null
        if(!webMode || !WebPolicy.chat(web?.url)) {callback.onReceiveValue(null);return}
        val readable=uris?.filter { uri -> uri.scheme=="content" && runCatching {
            contentResolver.openAssetFileDescriptor(uri,"r")?.use {true} ?: false
        }.getOrDefault(false) }?.take(if(multipleFiles)10 else 1)?.toTypedArray()
        callback.onReceiveValue(readable?.takeIf {it.isNotEmpty()})
    }
    private fun grantMicrophone() {
        val request=pendingPermission ?: return
        pendingPermission=null
        if(webMode && !isFinishing && WebPolicy.nativeCapability(request.origin.toString(),web?.url) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        else request.deny()
    }
    private fun denyMicrophone() {pendingPermission?.deny();pendingPermission=null}
    private fun cancelNativeRequests() {
        denyMicrophone();permissionDialog?.dismiss();permissionDialog=null
        fileCallback?.onReceiveValue(null);fileCallback=null;chooserDialog?.dismiss();chooserDialog=null
    }
    private fun requestSnapshot() {
        AlertDialog.Builder(this).setTitle("Lokalny zrzut ekranu")
            .setMessage("Po zgodzie systemu masz 5 sekund na powrót do gry przyciskiem Meta. Powstanie jeden obraz w Zdjęcia → QuestGPT Free. Przechwytywanie zakończy się samo.\n\nPotem w ChatGPT wybierz + i dołącz zrzut. Obraz nie jest wysyłany automatycznie. Niektóre aplikacje blokują przechwytywanie.")
            .setPositiveButton("Zrób zrzut") { _,_ ->
                runCatching {
                    val manager=getSystemService(MediaProjectionManager::class.java)
                    val request=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                    startActivityForResult(request,43)
                }.onFailure {message("System nie udostępnia przechwytywania. Użyj systemowego zrzutu Questa i dołącz go do rozmowy.")}
            }.setNegativeButton("Anuluj",null).show()
    }
    private fun previewSnapshot() {
        val uri=SnapshotStore.latest(this) ?: return message("Najpierw zrób zrzut. Możesz też dołączyć zwykłe zdjęcie bezpośrednio na stronie ChatGPT.")
        val image=ImageView(this).apply {adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER}
        if(runCatching {image.setImageURI(uri);image.drawable!=null}.getOrDefault(false))
            AlertDialog.Builder(this).setTitle("Ostatni zrzut Questa").setView(image)
                .setMessage("W ChatGPT wybierz + → dołącz obraz. W panelu możesz wybrać Ostatni zrzut Questa; w przeglądarce: Zdjęcia → QuestGPT Free.")
                .setPositiveButton("Zamknij",null).show()
        else message("Plik został usunięty lub jest niedostępny. Zrób nowy zrzut.")
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==41) {
            val uris=if(resultCode==RESULT_OK) data?.clipData?.let {clip -> (0 until minOf(clip.itemCount,10)).map {clip.getItemAt(it).uri}.toTypedArray()}
                ?: data?.data?.let {arrayOf(it)} else null
            deliverFiles(uris)
        }
        if(requestCode==43) {
            if(resultCode!=RESULT_OK || data==null) {SnapshotStore.status(this,"Zrzut anulowany. Nic nie zostało zapisane ani wysłane.");return}
            val bounds=windowManager.maximumWindowMetrics.bounds
            val scale= minOf(1.0,1600.0/maxOf(bounds.width(),bounds.height()))
            runCatching { startForegroundService(Intent(this,SnapshotService::class.java).setAction("CAPTURE")
                .putExtra("consent",data).putExtra("code",resultCode)
                .putExtra("width",(bounds.width()*scale).toInt()).putExtra("height",(bounds.height()*scale).toInt())) }
                .onFailure {SnapshotStore.status(this,"Nie udało się uruchomić zrzutu. Spróbuj ponownie z widocznego panelu.")}
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==42) {if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) grantMicrophone() else denyMicrophone()}
    }
    private fun openBrowser() {openExternal(Uri.parse(WebPolicy.HOME))}
    private fun openExternal(uri: Uri) {
        if(!WebPolicy.external(uri.toString())) return
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this,uri) }
            .recoverCatching { startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure {message("Nie znaleziono przeglądarki. Otwórz Meta Quest Browser i wpisz chatgpt.com.")}
    }
    private fun menu() {
        AlertDialog.Builder(this).setItems(arrayOf("Odśwież stronę","Nowa strona ChatGPT","Zrzut za 5 sekund","Ostatni zrzut","Pomoc","Wyloguj panel")) { _,which ->
            when(which) {
                0 -> web?.reload() ?: showWeb()
                1 -> {showWeb();web?.loadUrl(WebPolicy.HOME)}
                2 -> requestSnapshot()
                3 -> previewSnapshot()
                4 -> help()
                5 -> AlertDialog.Builder(this).setTitle("Wylogować panel?").setMessage("Usunie sesję ChatGPT z panelu aplikacji. Konto w zwykłej przeglądarce ma osobne logowanie.")
                    .setPositiveButton("Wyloguj") { _,_ ->
                        showHome();web?.destroy();web=null;savedWebState=null
                        CookieManager.getInstance().removeAllCookies {CookieManager.getInstance().flush()}
                        android.webkit.WebStorage.getInstance().deleteAllData()
                    }.setNegativeButton("Anuluj",null).show()
            }
        }.show()
    }
    private fun help() { message("1. Otwórz ChatGPT i zaloguj się na swoje konto. Obowiązują limity Free lub Plus. Nie potrzebujesz klucza API.\n\n2. Zdjęcie: na stronie ChatGPT wybierz + i plik z gogli.\n\n3. Widok gry: Zrzut za 5 s → zgoda systemu → wróć do gry. Potem dołącz obraz w ChatGPT. Free nie przesyła ekranu ciągle.\n\n4. Głos: użyj ikony głosu na stronie i zezwól na mikrofon. Dostępność zależy od konta, przeglądarki i Horizon OS.\n\n5. Mini: użyj ikony Free · Mini. System Questa decyduje, przy których grach panel może być widoczny.\n\nJeśli panel zatrzyma się na logowaniu lub weryfikacji, wybierz Przeglądarka. Nie ma wspólnego logowania między panelem a przeglądarką.") }
    private fun pageError(text: String) {pageProblem=text;if(webMode) {loading.visibility=View.GONE;address.text=text}}
    private fun message(text: String) {if(!isFinishing && !isDestroyed) AlertDialog.Builder(this).setMessage(text).setPositiveButton("Rozumiem",null).show()}
    private fun goBack() {if(webMode && web?.canGoBack()==true) web?.goBack() else if(webMode) showHome() else finish()}
    @Deprecated("Framework activity back navigation") override fun onBackPressed() {goBack()}
    override fun onResume() {super.onResume();if(webMode)web?.onResume();handler.post(refreshStatus)}
    override fun onPause() {handler.removeCallbacks(refreshStatus);web?.onPause();CookieManager.getInstance().flush();super.onPause()}
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra("panel",false)) showWeb()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("panel",webMode)
        web?.let {w -> val state=Bundle();w.saveState(state);outState.putBundle("web",state)}
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        cancelNativeRequests();handler.removeCallbacksAndMessages(null)
        popups.forEach {runCatching {it.destroy()}};popups.clear()
        web?.let {(it.parent as? ViewGroup)?.removeView(it);it.destroy()};web=null
        super.onDestroy()
    }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun label(text: String,size: Float,bold: Boolean=false)=TextView(this).apply {
        this.text=text;textSize=size;setTextColor(ink);if(bold)setTypeface(null,Typeface.BOLD);setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun button(text: String,primary: Boolean=false,action: ()->Unit)=Button(this).apply {
        this.text=text;isAllCaps=false;textSize=16f;minHeight=dp(48);minimumWidth=0;minWidth=0
        setPadding(dp(9),dp(5),dp(9),dp(5));setTextColor(if(primary)backgroundColor else ink)
        background=GradientDrawable().apply {setColor(if(primary)accent else surface);cornerRadius=dp(12).toFloat()}
        setOnClickListener {action()}
    }
    private fun fullButton()=LinearLayout.LayoutParams(-1,dp(56)).apply {topMargin=dp(4);bottomMargin=dp(4)}
}
