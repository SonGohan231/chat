# QuestGPT 2.1 — nowa aplikacja na Meta Quest 3

Nowy kod znajduje się w `reborn/`. Pakiet instalacyjny: `pl.somaskan.questgpt2`.
Poprzednia aplikacja w `app/` pozostaje osobnym produktem. Nie należy uruchamiać obu sesji głosowych równocześnie.

**Pobieranie i prowadzenie przez instalację:** [strona QuestGPT 2](https://questgpt-2.songoku222.chatgpt.site).

## Uruchomienie

1. Zainstaluj podpisane `QuestGPT-2.apk` w goglach z aktywnym trybem deweloperskim. Użyj istniejącego instalatora APK/SideQuest lub `adb install QuestGPT-2.apk`.
2. Otwórz **QuestGPT 2**. Wybierz **⋯ → Połączenie**, wprowadź swój klucz OpenAI API i wybierz **Zapisz i testuj**. Test wykonuje rzeczywiste żądanie Responses — nie samą kontrolę dostępności modelu. Użycie API jest rozliczane osobno od ChatGPT.
3. **Live** uruchamia rozmowę po udzieleniu zgody na mikrofon. Zielony wskaźnik obrazuje poziom próbek z mikrofonu. Sesja jest gotowa dopiero po `session.updated` od OpenAI. Nie podawaj nikomu klucza ani nie wysyłaj go w czacie.
4. **Ekran → Dalej** otwiera systemową zgodę na przechwytywanie. W Live wysyłane są klatki co około 2 sekundy. Bez Live klatka trafia do OpenAI dopiero z pytaniem tekstowym. **Widok → Podgląd / dołącz klatkę** pokazuje rzeczywisty obraz zwracany przez urządzenie.
5. Przejdź do gry i zadaj pytanie. Przyciskiem Meta otwórz menu, następnie uruchom QuestGPT 2 — Mini ma osobną ikonę w bibliotece aplikacji, a manifest zgłasza je też jako `OVERLAY_LAUNCHER`. Mini można też otworzyć przyciskiem w dużym panelu.

**Stop Live**, **Stop ekran** i **⋯ → Zatrzymaj wszystko** są dostępne w obu panelach. Powiadomienia sesji mają własne przyciski zatrzymania. Uśpienie urządzenia zatrzymuje sesję głosową.

## Zaimplementowane

- Natywne, skalowalne panele duży i Mini ze wspólną rozmową i szkicem pytania.
- Bezpośrednie Responses API do tekstu i maksymalnie 3 zdjęć; historia ostatnich 20 zakończonych wypowiedzi jest dołączana bez zależności od wygasającego `previous_response_id`. `store:false`.
- Wybieranie obrazów z Plików oraz odbiór `ACTION_SEND`/`ACTION_SEND_MULTIPLE`, automatyczne skalowanie, podgląd i usuwanie załączników. Ostatnio wysłane zdjęcie można zachować do kolejnego pytania i usunąć przyciskiem **Zapomnij obraz**.
- Natywne `AudioRecord`/`AudioTrack`, PCM 24 kHz, anulowanie odpowiedzi, zatrzymanie odtwarzania i skrócenie nieodsłuchanej odpowiedzi w Realtime. Kolejka audio nie blokuje odbioru zdarzeń sieciowych. Systemowe AEC i redukcja szumu, gdy dostępne.
- Mikrofon w usłudze pierwszoplanowej, z wykrywaniem wyciszenia przez drugą aplikację. Brak automatycznego restartu mikrofonu po utracie połączenia lub restarcie gogli. Limit jednej sesji: 55 minut.
- `MediaProjection` w osobnej usłudze, uruchomienie przed pobraniem projekcji, rejestracja callbacku przed utworzeniem VirtualDisplay, jedno użycie zgody. Zwolnienie projekcji, czytnika i wyświetlacza po zatrzymaniu.
- Wyraźne stany anulowania zgody, braku klatek, pustego obrazu i zakończenia projekcji. Klatki starsze niż 5 sekund nie są wysyłane jako bieżące. Nieaktualny kontekst ekranowy jest usuwany z sesji Live.
- Podgląd klatki i licznik obrazów potwierdzonych przez OpenAI. Przechwytywanie nie jest automatycznie utożsamiane z dostarczeniem obrazu do modelu.
- Klucz użytkownika szyfrowany AES-GCM z kluczem Android Keystore, wyłączony backup aplikacji, ekran konfiguracji zabezpieczony `FLAG_SECURE`, brak klucza w kodzie/APK. To osobista aplikacja BYOK; dystrybucja dla wielu użytkowników wymaga backendu i krótkotrwałych poświadczeń.
- Lokalna historia tekstowa, jej usuwanie i eksport; zdjęcia i klatki pozostają w pamięci aplikacji. Eksport jest wykonywany na żądanie przez systemowy arkusz udostępniania.
- Sprawdzanie metadanych nowej wersji przy starcie procesu oraz przejście do strony z APK. Instalację nowej APK użytkownik zatwierdza w Androidzie.

## Granice funkcjonalności

- **Nie jest to gwarantowana, stale widoczna nakładka nad każdą grą.** Horizon OS decyduje o panelach, fokusie oraz pracy aplikacji w tle. Nie ma wymuszania przycisku nad każdą aplikacją ani przechwytywania systemowych skrótów kontrolera.
- Udostępnianie obrazu to klatki, nie analiza każdej klatki filmu. Chronione aplikacje mogą blokować przechwytywanie. Drugi casting/nagrywanie może zakończyć pierwszą sesję.
- API nie daje aplikacji historii konta ChatGPT, jego pamięci ani abonamentu. Klucz i dostępny model trzeba skonfigurować na urządzeniu. Nie wykonano płatnego testu OpenAI bez klucza użytkownika.
- Nie ma sterowania innymi aplikacjami, ADB, ukrytego nasłuchu, wybudzania hasłem ani dostępu do chronionych treści. Głos w tle i overlay wymagają próby na fizycznym Queście; emulator Androida nie zastępuje Horizon OS.
- Przycisk zatrzymania nie cofa danych już przekazanych do OpenAI.
- Domyślne modele według dokumentacji OpenAI: `gpt-5.6-sol` i `gpt-realtime-2.1`. Można je zmienić w Połączeniu; dostęp zależy od projektu API.

## Próba na fizycznym Meta Quest 3

Wykonaj ją po instalacji; poniższe pozycje nie są wynikami już przeprowadzonego testu sprzętowego.

| Próba | Oczekiwany wynik |
| --- | --- |
| Połączenie → Zapisz i testuj | „API odpowiedziało”, bez błędu autoryzacji lub limitu. |
| Live, pytanie głosem | Wskaźnik mikrofonu reaguje; pojawiają się transkrypcja i słyszalna odpowiedź. |
| Zdjęcia, charakterystyczny obraz i pytanie | Model odnosi się do właściwego zdjęcia; można usunąć załączniki. |
| Ekran, zgoda systemu, przejście do gry | Klatka przedstawia aktualny widok gry; w Live wzrasta licznik klatek potwierdzonych przez OpenAI. |
| Pytanie o nowy widok | Odpowiedź odnosi się do zmiany obrazu, nie tylko do poprzedniej klatki. |
| Przycisk Meta i uruchomienie Mini | Mały panel można otworzyć; zachowuje rozmowę i pozwala zatrzymać obie usługi. |
| Przerwij podczas odpowiedzi | Dźwięk kończy się bez odtwarzania reszty starej odpowiedzi. |
| Stop ekran, Stop Live | Znika przechwycony obraz, mikrofon przestaje działać. Nowa projekcja wymaga zgody. |

W razie problemu zapisz wersję Horizon OS, nazwę gry i dokładny komunikat ze „Sprawdź działanie”. Sam wskaźnik aktywnej usługi nie potwierdza odbioru obrazu ani odpowiedzi przez OpenAI.

## Budowanie i podpis

Gradle 8.9, JDK 17, Android SDK 35:

```sh
gradle :reborn:testDebugUnitTest :reborn:assembleRelease :reborn:lintDebug
gradle :reborn:connectedDebugAndroidTest
```

Workflow `.github/workflows/questgpt2.yml` buduje niepodpisany wariant release i uruchamia testy emulatora API 35. Końcowa APK jest wyrównana `zipalign -P 16` i podpisana osobnym, trwałym kluczem poza repozytorium. Zachowaj prywatną kopię podpisu do następnych wydań; nie publikuj jej w repozytorium ani artefaktach CI.

Testy jednostkowe obejmują gotowość Realtime, odrzucanie nieaktualnych/pustych klatek, rzeczywiste formaty Responses, odmowy i odpowiedzi niepełne, historię, żądania multimodalne, konfigurację audio GA i usuwanie kluczy z błędów. Testy na emulatorze obejmują oba panele, zapis w Keystore, anulowanie udostępniania oraz rzeczywistą projekcję Androida. Końcowy wynik wykonania jest zapisany w `QUESTGPT2-VALIDATION.md`.

## Źródła implementacji

- Meta, MediaProjection: https://developers.meta.com/horizon/documentation/native/native-media-projection/
- Meta, panele i OVERLAY_LAUNCHER: https://developers.meta.com/horizon/documentation/spatial-sdk/hybrid-apps-overview/
- Android, projekcja, zgoda i callback: https://developer.android.com/media/grow/media-projection
- OpenAI, rozmowy, obrazy i przerwanie audio: https://developers.openai.com/api/docs/guides/realtime-conversations

Strona instalacji: https://questgpt-2.songoku222.chatgpt.site

## Nowości w 2.1

- Wszystkie podstawowe przyciski w dwóch rzędach; brak przewijania poziomego, by dotrzeć do wyciszenia lub przerwania.
- Gdy otworzysz klawiaturę, rzędy dodatkowych przycisków chowają się, aby zostawić miejsce na pytanie i wysyłanie.
- Widok → Zapytaj o aktualny widok wysyła bieżącą, niepustą klatkę.
- Widok → Zrzut za 5 sekund: zatwierdź ekran, wróć do gry przyciskiem Meta, potem otwórz Widok → Podgląd. Zrzut pozostaje dostępny po zatrzymaniu przechwytywania. To jawnie zapisany obraz, a nie bieżący podgląd.
- W Połączeniu można włączyć rozpoczynanie Live po otwarciu aplikacji. Działa po zapisaniu klucza i wcześniejszej zgodzie na mikrofon. Nie wznawia ukrytego nasłuchu po restarcie.
- Wspólne ustawienia powitania i autostartu dużego oraz małego panelu.
- Mikrofon: próba 24 kHz, a następnie 48 kHz z konwersją do 24 kHz.
- Obsługa potwierdzeń obrazów z wariantów zdarzeń Realtime, bez podwójnego naliczania.

Aktualizacja jest podpisana certyfikatem QuestGPT 2.0.5, więc instalacja na istniejącą wersję 2 zachowuje dane. Jeśli Android odrzuci aktualizację z powodu niezgodnego podpisu, nie odinstalowuj aplikacji bez zachowania własnego klucza i wyeksportowania potrzebnej historii.
