# QuestGPT 2.1.6 — wykonana walidacja, 12.09.2026

APK: `QuestGPT-2.1.apk`, 6 177 829 bajtów. Pakiet `pl.somaskan.questgpt2`, versionCode 20106.

Kod kompilacji: `627d85b215fb2945ba524cdeff43cf925e5e65b2`.
[GitHub Actions: kompilacja i testy](https://github.com/SonGohan231/chat/actions/runs/34684917583).

- Testy jednostkowe: **11/11 zaliczonych** (8 protokołu OpenAI, 3 konwersji PCM16).
- Testy natywne na emulatorze Android 15 / API 35: **5/5 zaliczonych**, bez pominięć.
- Panele: duży i Mini uruchamiają się; pole pytania pozostaje nad systemowym paskiem. W Mini przy szerokości 384 dp wszystkie 7 podstawowych przycisków są widoczne w granicach ekranu. Zrzut `mini-384dp.png` obejrzany po wykonaniu testu.
- Android Keystore: zapis i odczyt klucza testowego; klucz nie występuje jawnie w zapisanych preferencjach.
- Anulowanie zgody na ekran nie uruchamia projekcji.
- Rzeczywista projekcja Androida zwraca niepustą klatkę; zrzut z opóźnieniem jest zapisany, a zatrzymanie projekcji usuwa bieżącą klatkę i zachowuje świadomie zapisany zrzut.
- `lintDebug`: 0 błędów, 19 ostrzeżeń (m.in. teksty interfejsu w kodzie zamiast zasobów tłumaczeniowych).
- Zbudowano debug i release. Końcową release APK podpisano poza repozytorium kluczem QuestGPT 2. Sprawdzenie `apksigner verify` zakończone powodzeniem, podpis v3.
- SHA-256 APK: `eeb28c02280c688221d7010b7a4a0c5ba941a30445a05410306f4cc6a7d65c57`.
- SHA-256 certyfikatu: `b6f3aaa694e26894b2373a55940346c64ec13904f9681c116cbcdcbf9e9b4234` — taki sam jak w QuestGPT 2.0.5.

## Czego nie potwierdzono

Nie podłączono fizycznego Questa 3. Nie wykonano płatnego wywołania OpenAI: nie było dostępnego klucza, a połączenie OpenAI Platform nie udostępniło celu konfiguracji. Testy protokołu sprawdzają format i obsługę danych, nie są dowodem skutecznego połączenia z usługą.

Nie potwierdzono zachowania mikrofonu, przechwytywania ani widoczności Mini w konkretnej grze Horizon OS. Przypinanie paneli i praca usług w tle pozostają pod kontrolą systemu. Nie można gwarantować stałej nakładki w każdej aplikacji. Nie wykonano osobnego testu z otwartą klawiaturą; dostosowanie interfejsu korzysta z Android WindowInsets.

Na goglach po instalacji należy zapisać własny klucz w **Połączeniu**, wykonać test odpowiedzi API, uruchomić **Live**, zatwierdzić **Ekran**, przejść do gry i sprawdzić **Widok → Podgląd**. Samo uruchomienie usługi nie oznacza, że konkretna gra udostępnia obraz.

Dokumentacja implementacji:
- [Meta MediaProjection](https://developers.meta.com/horizon/documentation/native/native-media-projection/)
- [Funkcje Androida na Horizon OS](https://developers.meta.com/horizon/documentation/android-apps/features-overview/)
- [OpenAI Realtime](https://developers.openai.com/api/docs/guides/realtime)
- [OpenAI WebSockets](https://developers.openai.com/api/docs/guides/voice-websockets)
