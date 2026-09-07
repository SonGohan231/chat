package pl.somaskan.questgpt

import android.content.Context
import pl.somaskan.questgpt.adb.AgentAccessPolicy
import pl.somaskan.questgpt.adb.QuestAgentRuntime

object StartupConversationGuide {
    private const val PREFS = "questgpt_startup"
    private const val KEY_ENABLED = "auto_conversation"
    private const val KEY_FULL_GUIDE_SHOWN = "full_guide_shown"
    private const val KEY_MIC_REQUESTED = "mic_auto_requested"
    private const val KEY_LAST_STARTED_AT = "last_started_at"
    private const val START_DEBOUNCE_MS = 30_000L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun canStartNow(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_STARTED_AT, 0L)
        return System.currentTimeMillis() - last >= START_DEBOUNCE_MS
    }

    fun markStarted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_STARTED_AT, System.currentTimeMillis()).apply()
    }

    fun needsFullGuide(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FULL_GUIDE_SHOWN, false)

    fun markFullGuideShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FULL_GUIDE_SHOWN, true).apply()
    }

    fun resetFullGuide(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FULL_GUIDE_SHOWN, false).apply()
    }

    fun shouldAutoRequestMic(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MIC_REQUESTED, false)

    fun markMicRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MIC_REQUESTED, true).apply()
    }

    fun buildPrompt(
        context: Context,
        fullGuide: Boolean,
        adbConnected: Boolean,
        micGranted: Boolean,
        notificationsGranted: Boolean,
        installGranted: Boolean,
    ): String {
        AgentAccessPolicy.refreshRuntime()
        val serviceEnabled = AgentServiceController.isEnabled(context)
        val level = AgentAccessPolicy.current().label
        val mode = if (fullGuide) {
            "To jest pierwsze uruchomienie przewodnika. Zacznij rozmowę samodzielnie i przeprowadź użytkownika przez konfigurację krok po kroku."
        } else {
            "To jest kolejne uruchomienie. Zacznij rozmowę samodzielnie krótkim powitaniem, podaj stan gotowości i zapytaj, co użytkownik chce teraz zrobić. Nie powtarzaj całego szkolenia, chyba że o nie poprosi."
        }

        return """
            $mode

            Jesteś QuestGPT działającym natywnie na Meta Quest 3. Mów po polsku, naturalnie, krótko i praktycznie. Nie czekaj, aż użytkownik odezwie się pierwszy. W tej wypowiedzi startowej niczego nie klikaj ani nie zmieniaj — tylko przedstaw stan, możliwości i następny krok.

            Stan bieżący:
            - mikrofon: ${if (micGranted) "gotowy" else "brak uprawnienia"}
            - Wireless ADB: ${if (adbConnected) "połączone" else "niepołączone"}
            - Agent Service: ${if (serviceEnabled) "włączony" else "wyłączony"}
            - Auto Vision: ${if (QuestAgentRuntime.autoVisionEnabled) "włączone" else "wyłączone"}
            - sterowanie GPT: ${if (QuestAgentRuntime.agentControlEnabled) "włączone" else "wyłączone"}
            - poziom uprawnień agenta: $level
            - powiadomienia: ${if (notificationsGranted) "gotowe" else "brak uprawnienia"}
            - instalowanie aktualizacji APK: ${if (installGranted) "dozwolone" else "brak uprawnienia"}
            - backend OpenAI: ${QuestEndpoints.PUBLIC_BACKEND}

            Możliwości, które masz wyjaśnić w razie pierwszego przewodnika:
            1. rozmowa tekstowa i głosowa przez OpenAI Realtime;
            2. analiza zdjęć i ręcznych screenshotów;
            3. ADB Vision: aktualny obraz ekranu + UIAutomator, dzięki czemu możesz rozumieć elementy interfejsu;
            4. Agent: tap, swipe, wpisywanie tekstu, Back/Home, otwieranie aplikacji, URL i ustawień;
            5. poziom System: głośność, jasność, Wi-Fi, Bluetooth oraz instalacja/usuwanie APK, z potwierdzeniami dla operacji wrażliwych;
            6. pliki, notatki, szkicowanie po obrazie, aktualizacje APK i Live Edit;
            7. Agent Service utrzymujący Auto Vision i próbujący ponownie połączyć ADB w tle.

            Zasady prowadzenia użytkownika:
            - jeśli czegoś brakuje, zacznij od najważniejszego brakującego połączenia i prowadź po jednym kroku;
            - jeśli ADB nie jest połączone, wyjaśnij, że trzeba wejść w Ustawienia > ADB + Agent > Wireless debugging i sparować urządzenie kodem; nie udawaj, że widzisz ekran przez ADB, dopóki nie jest połączone;
            - jeśli wszystko jest gotowe, powiedz w jednym zdaniu, że możesz już widzieć interfejs, rozmawiać i wykonywać dozwolone działania, a potem zapytaj o pierwsze zadanie;
            - nie zasypuj użytkownika długą listą. Przy pierwszym przewodniku podziel prezentację na krótkie etapy i po każdym ważnym etapie daj użytkownikowi możliwość odpowiedzi.
        """.trimIndent()
    }
}
