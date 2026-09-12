# QuestGPT Free — ChatGPT przez stronę, bez klucza API

Osobna aplikacja `pl.somaskan.questgptfree` dla Meta Quest 3/3S. Instaluje się obok QuestGPT 2. Korzysta z interfejsu `https://chatgpt.com/` i limitów konta ChatGPT Free/Plus. Nie zawiera klienta OpenAI API, pola klucza, automatycznych zapytań do modeli ani usługi nasłuchu w tle.

## Korzystanie

1. Zainstaluj podpisany `QuestGPT-Free.apk` i otwórz QuestGPT Free w aplikacjach z nieznanych źródeł.
2. Wybierz **Otwórz ChatGPT**. To zalecana ścieżka: zwykła przeglądarka lub obsługiwana przez nią karta Custom Tab, z sesją konta przechowywaną przez przeglądarkę.
3. Zaloguj się na stronie ChatGPT swoim dotychczasowym kontem. Subskrypcja Plus pozostaje subskrypcją ChatGPT, bez osobnego doładowania API.
4. Zdjęcia dodawaj przez przycisk **+** na stronie. Ikona głosu, jeżeli dostępna, uruchamia głos ChatGPT po zgodzie na mikrofon.
5. Widok gry: **Zrzut za 5 s** → zgoda systemu → wróć do gry przyciskiem Meta. Jeden obraz zapisuje się w **Zdjęcia → QuestGPT Free**, a przechwytywanie kończy się automatycznie. Następnie samodzielnie dołącz obraz do rozmowy.

## Panel w APK i Mini

**Panel w aplikacji** osadza oryginalną stronę ChatGPT w systemowym Android WebView. Obsługuje wybór plików, mikrofon po zgodzie, sesję przeglądarki, cofanie, odświeżanie i wylogowanie. Po wybraniu załącznika na stronie możesz wskazać **Ostatni zrzut Questa**.

Panel i zwykła przeglądarka mają osobne logowanie. Dostawcy logowania lub ChatGPT mogą odrzucić osadzoną przeglądarkę; wtedy użyj **Przeglądarka**. Aplikacja nie obchodzi weryfikacji, nie zmienia tożsamości przeglądarki, nie wyciąga ciasteczek ani nie automatyzuje interfejsu ChatGPT.

**QuestGPT Free · Mini** ma osobną ikonę. Widoczność obok aplikacji VR zależy od Horizon OS i danej gry. Nie jest to gwarantowana nakładka nad każdą aplikacją.

## Zakres

| Funkcja | Free |
|---|---|
| Tekst i historia konta ChatGPT | Przez oryginalną stronę po zalogowaniu |
| Własny klucz i płatności API | Niepotrzebne |
| Zdjęcia i zrzuty | Ręczne dołączenie do rozmowy |
| Głos | Interfejs ChatGPT; zależy od konta, przeglądarki i systemu |
| Automatyczna transmisja ekranu co 2 sekundy | Niedostępna |
| Natywny dostęp do pamięci ChatGPT poza stroną | Niedostępny |
| Stały nasłuch w tle, nad każdą grą | Niegwarantowany / brak natywnej usługi |

## Prywatność i opłaty

APK Free nie korzysta z płatnego API. ChatGPT nadal ma limity i opcjonalne płatne subskrypcje. Żadna subskrypcja nie jest kupowana przez ten APK automatycznie. To niezależny projekt, nie oficjalna aplikacja OpenAI ani Meta.

Zrzuty trafiają do wspólnego katalogu zdjęć urządzenia i pozostają tam po zakończeniu przechwytywania. Trafiają do ChatGPT dopiero po wybraniu ich w załącznikach i wysłaniu wiadomości. Usuń je w aplikacji Pliki/Zdjęcia, gdy nie są już potrzebne. Chronione lub czarne klatki nie są zapisywane.

Panel nie odczytuje haseł ani tokenów logowania. Nie używa `addJavascriptInterface`, nie wstrzykuje skryptów w stronę ani nie usuwa kontroli certyfikatów. Mikrofon ma dostęp tylko dla oryginalnego originu HTTPS ChatGPT, po potwierdzeniu użytkownika. Nieznane odnośniki użytkownika otwiera zwykła przeglądarka.

## Budowanie i weryfikacja

Moduł `free`, Gradle 8.9, Java 17, Android SDK 35. Workflow `.github/workflows/questgpt-free.yml` buduje APK, uruchamia testy polityki originów, Android lint oraz testy na emulatorze Android 15. Klucz podpisujący pozostaje poza repozytorium.

Testy obejmują panele, anulowanie zrzutu, faktyczne MediaProjection → JPEG → koniec usługi, przekazanie wybranego zrzutu do testowego formularza HTML oraz odmowę mikrofonu obcej stronie. Osobny zrzut publicznej strony pokazuje wynik dostępu z emulatora; nie jest testem logowania do konta lub rozmowy głosowej. Konto użytkownika i fizyczny Quest wymagają sprawdzenia na urządzeniu.

Źródła:
- https://help.openai.com/en/articles/20001274
- https://developer.android.com/develop/ui/views/layout/webapps/webview
- https://developer.chrome.com/docs/android/custom-tabs
- https://developer.android.com/media/grow/media-projection
