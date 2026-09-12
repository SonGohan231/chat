# QuestGPT 2.3 — kamera otoczenia i natywne MR

Aktualizacja płatnej wersji API, pakiet `pl.somaskan.questgpt2`. Ten sam podpis umożliwia aktualizację wcześniejszej wersji 2.1 z zachowaniem klucza i rozmów. Wersja Free pozostaje osobnym pakietem.

## Jak uruchomić na Meta Quest 3 / 3S

1. Zainstaluj APK jako aktualizację QuestGPT 2. Gogle muszą mieć aktualny Horizon OS z Androidem 14 (API 34) lub nowszym. Samo API kamer Meta jest dostępne od Horizon OS v74; ta kompilacja ma minimum Android API 34.
2. Jeśli klucz nie był zapisany: `⋯ → Połączenie` i test odpowiedzi API. Opłaty API są niezależne od ChatGPT Plus.
3. Naciśnij `Otoczenie` albo otwórz ikonę `QuestGPT · Otoczenie MR` w bibliotece Questa.
4. Naciśnij `Kamera`. Potwierdź opis udostępniania na panelu MR i zgodę systemu na kamerę. Własny widok MR włącza natywny passthrough. Kamera dla AI ma oddzielne sterowanie.
5. Poczekaj, aż podgląd pokaże otoczenie; następnie włącz `Live` i zezwól na mikrofon. Zapytaj np. „Co widzisz przede mną?” lub „Przeczytaj tę etykietę”.
6. `−` zwija panel do małej ikony AI i przycisku Stop. Ikona podąża za głową, w dolnej prawej części widoku MR. Kliknięcie AI przywraca panel przed użytkownikiem.
7. `Ustaw widok` przełącza panel między przypięciem przed użytkownikiem i podążaniem za głową. `Stop kamera` usuwa bieżący obraz AI, `Wycisz` dotyczy mikrofonu, `Stop wszystko` kończy kamerę, mikrofon i przechwytywanie ekranu.
8. `Czat` wraca do panelu 2D przez mechanizm hybrydowy Meta. Kamera MR zatrzymuje się po wyjściu do tła. Zwykły tryb 2D ma `Skróty`: kamera, zwinięcie do ikony, przerwanie odpowiedzi i zatrzymanie wszystkiego.

## Co faktycznie widzi użytkownik i AI

- Użytkownik: systemowy, stereoskopowy passthrough Meta. Scena nie tworzy nieprzezroczystego tła; panel używa mieszania alfa i nie dodaje systemowej tafli.
- AI: pojedyncze zdjęcia z przedniej kamery RGB wybranej po oficjalnych znacznikach Meta. Pierwszeństwo ma kamera lewa. Kamera awatara nie jest używana.
- Podgląd na panelu pokazuje tę samą klatkę, która może zostać wysłana do AI. Bez Live podgląd pozostaje lokalny do wysłania pytania. W Live zdjęcia trafiają do OpenAI co około 2 sekundy oraz przy pytaniu; licznik zwiększa się po potwierdzeniu elementu rozmowy przez serwer.
- Pole kamery RGB jest węższe od całego passthrough. Obraz nie zawiera głębi, mapy pomieszczenia ani dokładnych odległości. Aplikacja nie przekazuje danych Scene API ani siatki terenu.
- `Ekran` nadal służy do pokazania gry lub interfejsu systemowego przez MediaProjection. Przełączenie na kamerę zatrzymuje przechwytywanie ekranu i odwrotnie.
- Bieżące klatki są przechowywane w pamięci, bez automatycznego zapisu zdjęć otoczenia do galerii. Puste i nieaktualne klatki są odrzucane. Kamera zatrzymuje się po uśpieniu; brak obrazu wywołuje komunikat i zwolnienie zasobów.

## Granice działania

Ikona podążająca za głową działa we własnej scenie MR. Horizon OS decyduje o widoczności paneli 2D nad innymi aplikacjami i ich działaniu w tle. Ta aplikacja nie może wymusić stale widocznej nakładki nad każdą grą ani zmienić innej gry w tryb passthrough.

Nie przeprowadzono testu na fizycznym Meta Quest ani z opłaconym połączeniem OpenAI. Emulator Android nie emuluje kamer passthrough ani kompozytora Meta. Testy Camera2 sprawdzają rzeczywiste API Androida z kamerą emulowaną, obsługę zgód, generowanie JPEG, podgląd, mini menu i zatrzymanie usług. Nie stanowią potwierdzenia sprzętowego passthrough.

## Implementacja i źródła

- `reborn`: Kotlin, Android Camera2, Meta Spatial SDK 0.14.0, OpenAI Responses i Realtime, istniejący Android Keystore.
- Release zawiera biblioteki Meta dla `arm64-v8a`. Debug jest przeznaczony dla emulatora `x86_64`; natywny renderer Meta jest w nim niedostępny, a testy otwierają wspólne kontrolki w aktywności 2D.
- [Passthrough Camera API](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-pca-overview/).
- [Natywny passthrough Spatial SDK](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-passthrough/).
- [Oficjalny przykład Meta dla przejścia między MR i panelem 2D](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/blob/f233e2327b95f9871b75bdba867d6fdd726f07cc/HybridSample/app/src/main/java/com/meta/spatial/samples/hybridsample/HybridSampleActivity.kt).
- [Przesyłanie obrazów w Realtime](https://developers.openai.com/api/docs/guides/realtime-conversations#image-inputs).

## Zweryfikowane wydanie 2.3.13

- APK: `QuestGPT-MR.apk`, 31,206,195 bajtów; ARM64, Android API 34+.
- SHA-256 APK: `97ea508835c46abbc11c424aa23530e3f2b34dfaed11fb47beedda7949a80304`.
- Certyfikat: `b6f3aaa694e26894b2373a55940346c64ec13904f9681c116cbcdcbf9e9b4234` — zgodny z wcześniejszym QuestGPT 2.
- Kod APK: `8e3281eb38f098cc185b926cd21fecb206c9212d`. Późniejszy commit zmienia wyłącznie pulę maszyn CI.
- [Zakończony pomyślnie build i testy](https://github.com/SonGohan231/chat/actions/runs/34714950430): 15 testów jednostkowych, 7 testów Androida, 0 błędów lint.
- Podpis APK v3, pakiet i wersję odczytano z końcowego pliku. Zweryfikowano obecność renderera Meta i OpenXR dla ARM64. Biblioteki są kompresowane w APK i wypakowywane przez Androida podczas instalacji.
- Brak testu na fizycznym Queście i z płatnym API; zakres weryfikacji pozostaje taki jak opisano powyżej.

