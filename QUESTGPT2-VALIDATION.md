# QuestGPT 2.0.5 — wynik weryfikacji

Data: 11 września 2026. Dostarczono podpisaną aplikację `QuestGPT-2.apk`, pakiet `pl.somaskan.questgpt2`, versionCode `20005`.

## Wynik

| Sprawdzenie | Rzeczywisty wynik |
| --- | --- |
| Kompilacja debug i release | PASS |
| Testy protokołu | 8 wykonanych, 0 błędów, 0 pominiętych |
| Testy Androida API 35, emulator Pixel C x86_64 | 4 wykonane, 0 błędów, 0 pominiętych |
| Android Lint | 0 błędów, 19 ostrzeżeń |
| Podpis gotowej APK | `apksigner verify`: PASS, APK Signature Scheme v3, RSA 3072 |
| Kontrola zrzutów dużego i małego panelu | Pole pytania i cały przycisk Wyślij widoczne nad paskiem systemowym |
| Fizyczny Meta Quest 3 / Horizon OS | NIE WYKONANO |
| Rzeczywiste odpowiedzi i audio OpenAI z kluczem użytkownika | NIE WYKONANO |

Testy instrumentacyjne uruchomiono na wariancie debug. Wariant release zbudowano z tego samego kodu, wyrównano i podpisano osobno. Nie jest to dowód pełnej zgodności z Horizon OS ani test instalacji podpisanej APK na fizycznym urządzeniu.

## Co faktycznie sprawdzono

1. Oba natywne panele uruchamiają się, zachowują rozmowę, mają dostępny przycisk wysyłania; przycisk mieści się nad paskiem systemowym i ma pełny obszar dotyku. Live bez klucza otwiera konfigurację i nie udaje aktywnej sesji.
2. Testowy, nieważny klucz przechodzi zapis i odczyt Android Keystore; tekst jawny nie trafia do ustawień; usunięcie usuwa dostęp do klucza. Ten test nie wysyła żądania sieciowego.
3. Anulowanie zgody nie uruchamia przechwytywania.
4. Systemowa zgoda Androida rzeczywiście uruchamia MediaProjection. Otrzymano niepustą klatkę z ImageReader. Zatrzymanie usługi wyłącza przechwytywanie i usuwa klatkę ze stanu.

Osiem testów jednostkowych obejmuje gotowość sesji po potwierdzeniu serwera, stare/puste/przyszłe klatki, formaty odpowiedzi Responses, odmowy i odpowiedzi niepełne, historię multimodalną, konfigurację audio Realtime GA i maskowanie kluczy w błędach. To kontrola protokołu na danych testowych, nie odpowiedzi prawdziwego serwera.

Ostrzeżenia Lint dotyczą API/wersji zależności, ustawień kopii zapasowej, statycznych referencji oraz polskich tekstów wpisanych bezpośrednio w kodzie. Nie przedstawiono ich jako zera ostrzeżeń. Projekt ma minSdk 32 i targetSdk 35. Ostrzeżenie API dotyczy stałej `RECEIVER_NOT_EXPORTED` w rejestracji odbiornika; test wykonano na API 35, zachowania na API 32 nie potwierdzono.

## Powtarzalność i plik wydania

- Kod zbudowanej aplikacji: [`a41896e59915164c2b253a098bb9905277dbb4f6`](https://github.com/SonGohan231/chat/commit/a41896e59915164c2b253a098bb9905277dbb4f6).
- [Końcowy przebieg CI 34595588321](https://github.com/SonGohan231/chat/actions/runs/34595588321): wszystkie kroki zakończone sukcesem.
- Artefakt testów: `QuestGPT-2-test-evidence`, ID `10262332483`. Zawiera raporty HTML/XML, logi Androida i zrzuty `main.png`, `mini.png`, `screen-sharing.png`.
- Rozmiar podpisanej APK: `6165541` bajtów.
- SHA-256 APK: `a77e52c93a6e88cf825d2a0a143289e3fd6563bb38fd08c796e4bbfcb4e301b5`.
- SHA-256 certyfikatu: `b6f3aaa694e26894b2373a55940346c64ec13904f9681c116cbcdcbf9e9b4234`.
- [Pobieranie i instrukcja](https://questgpt-2.songoku222.chatgpt.site).
- [Kopia APK w Google Drive](https://drive.google.com/file/d/1uFZ121bP_TMlUa99zNQJ6UvNHgnyvm03/view?usp=drivesdk).

Stronę zapisano i opublikowano prywatnie jako wersję 1; system publikacji potwierdził sukces. Sprawdzono lokalne odnośniki, składnię JavaScript i zachowanie listy instalacyjnej w izolowanym środowisku. Nie wykonano testu strony w przeglądarce ani testu WebMCP w przeglądarce z obsługą tego API.

## Ograniczenia wymagające sprawdzenia w goglach

Mini korzysta z paneli udostępnianych przez Horizon OS. Brak gwarancji stałej nakładki nad każdą grą. Mikrofon w tle, dostęp do widoku aplikacji oraz ponowne otwarcie Mini należy sprawdzić w konkretnej grze. Udostępnianie wysyła klatki co około 2 sekundy w Live; chronione treści mogą pozostać puste. Klucz OpenAI trzeba skonfigurować w aplikacji; API ma osobne rozliczenie od ChatGPT.

Procedura próby na fizycznym Queście jest w [instrukcji](QUESTGPT2.md). Tych pozycji nie oznaczono jako zaliczonych automatycznie.
