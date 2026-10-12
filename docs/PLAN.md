# barcode-pro — specyfikacja

Czysto javowa biblioteka do odczytu kodów kreskowych. Algorytmy odczytu przenosimy z [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) 3.1.1 (Apache 2.0) do zwykłego bytecode Javy. Biblioteka C++ nie jest zależnością: brak JNI, brak natywnego OpenCV, brak zint i innych binariów platformowych.

Dokument jest planem i kontraktem implementacji. Zapis z pierwszej wersji, że silnikiem na stałe zostaje Javowy ZXing, a własny dekoder bitów leży poza zakresem, był błędny. BoofCV zeszło z buildu razem z przechodnim Trove: progi, morfologia i detektory są własne. ZXing `core` i `javase` zostają przy binaryzatorze hybrydowym obrazu oryginalnego, Reed-Solomonie, korekcji PDF417 i formatach spoza własnego zestawu. Dalszy kawałek tego rusztowania schodzi, gdy zamiennik czyta ten sam zbiór i nie dokłada fałszywych trafień.

Na trudnych obrazach biblioteka ma być wyraźnie lepsza od standardowego wywołania Javowego ZXinga. Dorównanie bibliotekom komercyjnym (Dynamsoft, Aspose) na brudnych zdjęciach produkcyjnych nie jest gwarantowanym wynikiem portu: na opublikowanych zbiorach zdjęć sam zxing-cpp zostaje poniżej tych SDK. Czas działania binarki C++ nie jest kryterium. W Javie zostaje algorytm, bez SIMD i bez ręcznego zarządzania pamięcią.

## Kierunek portu

Źródło zachowania to ścieżka odczytu zxing-cpp 3.1.1. Przy pierwszym pliku przeniesionym z tego drzewa dochodzi `NOTICE` z notą autorów ZXing i zxing-cpp. Komentarz nad klasą podaje plik źródłowy i tag. Portujemy zachowanie i strukturę algorytmu, nie składnię C++20.

Kolejność:

1. Detektory. Tu Javowy ZXing 3.5.4 zostaje w tyle, a BoofCV 1.5 nie ma Data Matrix. Pierwsze: Data Matrix (duże i nierówne symbole, wewnętrzne wzorce wyrównania), finder QR (wersja 1, okrągłe wzorce, wiele małych symboli na jednym obrazie), Aztec po wewnętrznych wzorcach czasowych, Micro QR.
2. Binaryzacja. `LocalAverage` z symetryczną interpolacją progu oraz próby odpowiadające `tryInvert`, `tryRotate` (90/180/270) i `tryDownscale`. Obrót kodu 1D o kąt inny niż wielokrotność 90° zostaje warstwą przed silnikiem: zxing-cpp też czyta kod liniowy tylko wtedy, gdy cały symbol przecina poziomy albo pionowy wiersz pikseli.
3. Dekodery bitów, symbologia po symbologii. Priorytet: QR Code, Data Matrix, Code 128, PDF417, EAN/UPC, Code 39. Dopóki dekoder danej symbologii nie przejdzie testu okrężnego i zbioru, jej odczyt zostaje przy Javowym ZXingu.
4. Zejście rusztowania. BoofCV i przechodnie Trove (LGPL 2.1) schodzą, gdy progi, morfologia i detektory są nasze, a `mvn test` zostaje zielony. `zxing-core` przy tym zostaje: niesie binaryzator hybrydowy, Reed-Solomon, korekcję PDF417 i formaty spoza własnego zestawu.

Zapis kodów zostaje poza portem. Od zxing-cpp 3.0 domyślny writer stoi na C-owej bibliotece zint.

## Stan rusztowania

Data: 2026-10-12, ten sam dzień co pomiar w sekcji [Pomiar](#pomiar). Repo startowało od szkieletu Mavena (`org.krzywanski:barcode-pro`).

| Faza | Zakres | Stan w kodzie |
| --- | --- | --- |
| 0 | Zbiór trudnych kodów, baseline ZXing, metryki | Generator syntetyczny i benchmark w testach. Liczby z ostatniego uruchomienia są w [Pomiar](#pomiar). |
| 1 | Preprocessing i wieloetapowe próby | Zaimplementowane. Tryb `FAST` i `AGGRESSIVE`. Progi i morfologia są własne. |
| 2 | Lokalizacja regionów, crop, deskew | Własne findery QR, Micro QR, Aztec i Data Matrix. Przerywany obrys: sześć dylatacji i próba `blob-quad`. Mały symbol 2D, którego wcześniejsze próby nie wezmą, idzie z kafelka. Pochylony kod 1D, którego nie biorą obroty ±15° i ±30°, idzie z pasma `linear-band`. |
| 3 | Ścieżki specjalne, odblaski, wiele kodów | Częściowo: osobna ścieżka QR/Aztec/Micro QR, normalizacja oświetlenia, `findAll`, dwa tryby. |
| 4 | Stabilizacja i metadane | Częściowo: wynik niesie tekst, symbologię, narożniki, orientację, heurystyczną pewność i nazwę próby. Optymalizacja pod duże obrazy (limit pikseli, budżet czasu) jest wstępna. |

Priorytet symbologii: QR Code, Data Matrix, Code 128, PDF417, EAN/UPC, Code 39. Dodatkowo Aztec i Micro QR. Micro QR nie ma formatu w ZXingu; czyta go portowany dekoder.

## Poza zakresem

- JNI, JavaCV, natywny OpenCV, libdmtx, zint oraz podpięcie zxing-cpp jako biblioteki C++.
- Port ścieżki zapisu kodów.
- Gwarancja jakości komercyjnych SDK na przypadkowych zdjęciach z produkcji.
- Parytet prędkości z binarką C++.

## Stos

Zostaje Java 17+ i kod przeniesiony z zxing-cpp.

- Java 17+ (bytecode tego modułu: `--release 17`; projekt kompiluje się JDK 21).
- ZXing `core` i `javase` 3.5.4 (Apache 2.0). Zostają przy binaryzatorze hybrydowym obrazu oryginalnego (`BufferedImageLuminanceSource`), Reed-Solomonie, korekcji PDF417 i formatach spoza własnego zestawu.
- Java 2D: `BufferedImage` jako wejście publiczne.
- JUnit 5 tylko w testach.

`boofcv-ip`, `boofcv-recognition` i przechodnie Trove nie są w `pom.xml`. Konwersja `BufferedImage` → obraz szarości, progi, morfologia i detektory są własne.

## Wejście i wyjście

```
BufferedImage
      ↓
BarcodeReader.read(image, ReadOptions)
      ↓
1. Tania drabina
   original-hybrid, original-global, inverted-luma, margines 4 px,
   rozciągnięcie kontrastu, lokalna normalizacja oświetlenia,
   obroty 90/180/270, adaptive mean, Otsu, skala 2×
      ↓
2. Findery
   qr-trace, mqr-trace, aztec-trace, dm-trace
      ↓
3. Tylko AGGRESSIVE
   obroty ±15° i ±30°, gdy prostokąt obrotu mieści się w 13 Mpx,
   do dziewięciu pasów liniowych (hybryda, rozciągnięcie, Otsu, średnia,
   potem ciaśniejsze okno: hybryda i Otsu),
   piramida, siatka kafelków 3×3 (tylko symbologie 2D),
   próg Gaussa, Sauvola, morfologia, skale 1.5× i 0.5×,
   blob-quad, a gdy nadal pusto albo findAll: te same findery na odwróconej szarości
      ↓
Lista BarcodeHit
   tekst, symbologia, narożniki w pikselach źródła,
   orientacja w stopniach, pewność 0..1, nazwa próby
```

Kolejność jest celowa. Czysty kod kończy się na pierwszej próbie, kosztowo blisko zwykłego ZXinga. Droższe etapy ruszają, gdy wcześniejsze nic nie znalazły, albo gdy `findAll` każe szukać dalej.

## API

Pakiet publiczny: `org.krzywanski.barcode`.

- `BarcodeReader` — bezstanowe wejście. `read` można wołać z wielu wątków na tym samym obiekcie (każde wywołanie ma własny stan dekodera).
- `ReadOptions.fast()` — domyślne. Mało prób, budżet 2,5 s, 40 prób.
- `ReadOptions.aggressive()` — pełny harmonogram, budżet 8 s, 100 prób.
- `findAll(boolean)` — po pierwszym trafieniu nie przerywa. Domyślnie `false`.
- `symbologies(...)` — podzbiór. Domyślnie priorytetowa lista plus Aztec i Micro QR.
- `BarcodeHit` — jeden odczyt.
- `Symbology`, `DecodeMode`, `ImagePoint`.

`confidence` jest heurystyką kolejności próby (wcześniejsza, czystsza próba → wyżej), nie skalibrowanym prawdopodobieństwem. `orientationDegrees` to stopnie zgodne z ruchem wskazówek zegara na obrazie o osi Y w dół; `-1` znaczy „nieznana”. `attempt` jest metadaną diagnostyczną (`original-hybrid`, `inverted-luma`, `illumination`, `qr-trace`, `blob-quad`, `sauvola`, …) i może się zmienić między wersjami.

Fałszywe trafienia 1D są ograniczone trojako. Warianty morfologiczne dekodują tylko symbologie 2D. Odczyt 1D bez dwóch punktów odległych o co najmniej 30 px jest odrzucany. Odczyt 1D musi powtórzyć się na równoległym pasku (8 px grubości, 4 px od linii, którą przyjął ZXing, z zapasem 40 px na cichą strefę): ta sama symbologia i ta sama treść. Jedna linia skośnych kresek, która sumą kontrolną wychodzi jako krótki UPC-E, odpada. Gdy obraz nie mieści takiego paska, trafienie zostaje.

Na próbie, której nazwa zaczyna się od `linear-band`, EAN ma dwie dodatkowe bramki. Krótki przebieg odpada, gdy ta sama bitmapa niesie inny tekst o rozpiętości co najmniej 8 px i ściśle większej niż przebieg trafienia. Przebieg, który sam ma co najmniej 8 px, zostaje. Odczyt z progu binarnego (Otsu albo średnia lokalna) zostaje tylko wtedy, gdy ten sam tekst wraca na co najmniej czterech wierszach albo na wierszach odległych o co najmniej 8 px. Hybryda i rozciągnięcie kontrastu tej drugiej bramki nie dostają, bo jednowierszowy właściwy EAN ma zostać.

## Baseline pomiaru

„Standardowy ZXing” w benchmarku to dokładnie to, co robi typowy fragment z dokumentacji:

- `MultiFormatReader.decode`
- `HybridBinarizer` + `BufferedImageLuminanceSource`
- hint `TRY_HARDER`
- bez `ALSO_INVERTED`, bez piramidy, bez obrotów poza tym jednym obrotem o 90°, który `TRY_HARDER` robi wewnątrz czytnika 1D, bez drugiego kodu na tym samym obrazie

`GenericMultipleBarcodeReader` nie wchodzi do baseline. Obsługa wielu kodów jest osobną zdolnością biblioteki (`findAll`).

Metryki:

- recall — ile oczekiwanych treści zostało odczytanych, dzielone przez liczbę oczekiwanych treści;
- fałszywe trafienia — odczyty, których treści nie ma w oczekiwaniach obrazu (na obrazach bez kodu każdy odczyt jest fałszywy);
- czas — ścienny czas `read` na jednym obrazie.

Obrazy testowe są syntetyczne i deterministyczne (uszkodzenia rysowane w Javie 2D / homografią): czyste kody, inwersja QR, Data Matrix i Code 128, perspektywa QR, QR o poziomie korekcji H trafiający w złą fazę bloków HybridBinarizera, odblask (biały owal o alfie 180), szum 2%, Data Matrix obrócony o 25° i wpisany w czworokąt, Code 128 obrócony o 30° i o 45°, mały Data Matrix na dużej etykiecie, stres (moduł 1 px, rozmycie, gradient), dwa QR, QR obok Code 128 oraz negatywy (pusto, szum, gradient). To jest zbiór fazy 0. Mianownik 22 zostaje. Prawdziwe zdjęcia leżą poza `src/test/resources`, żeby `mvn test` nie kopiował ich do `target/`. Arte-Lab jest w `datasets/artelab-medium-barcode-1d/`.

## Harmonogram prób

### FAST

1. Oryginał, `HybridBinarizer` na `BufferedImageLuminanceSource` (luminancja ZXinga). Próba `original-hybrid`. Własne dekodery macierzowe i priorytetowe liniowe czytają tę bitmapę. Biały symbol macierzowy na czarnym to `flipAll` tej samej bitmapy, nadal pod tą nazwą próby.
2. Ten sam obraz, `GlobalHistogramBinarizer` (`original-global`).
3. Odwrócona luminancja (`255 − wartość`), potem `HybridBinarizer` (`inverted-luma`). `flipAll` odwraca już zbinearyzowaną macierz i nie czyta białego Code 128 na czarnym tle. Ten kod wygrywa tutaj.
4. Biały margines 4 px (`pad-4`). Przesuwa fazę bloków 8×8 HybridBinarizera.
5. Rozciągnięcie kontrastu (percentyle 1%–99%), tylko gdy obraz się zmienia.
6. Lokalna normalizacja oświetlenia (średnia pudełkowa).
7. Obroty ortogonalne 90°, 180°, 270° (przepisanie pikseli, bez interpolacji).
8. Próg adaptacyjny średniej lokalnej i Otsu. Obraz binarny wraca do luminancji i idzie przez `GlobalHistogramBinarizer`.
9. Skala 2×, najbliższy sąsiad, gdy krótszy bok ≤ 900 px i wynik mieści się w 1 600 000 pikseli. To jest limit skali. Obrót ±15° i ±30° ma osobny limit i go nie podnosi.
10. Własne findery: `qr-trace`, `mqr-trace`, `aztec-trace`, `dm-trace`. W `qr-trace`, gdy LocalAverage nic nie zwróci, ten sam finder dostaje medianę 3×3 i czyta jeszcze raz, pod tą samą nazwą próby. Aztec o wymiarze co najmniej 35 jest próbkowany z lokalnej siatki wewnątrz `aztec-trace`. FAST tu wraca.

Przy `findAll` pętla nie staje na pierwszym trafieniu. W jednej próbie własny odczyt zwraca symbole znalezione na tej bitmapie. `GenericMultipleBarcodeReader` zostaje dla formatu, który nadal idzie do ZXinga, na oryginale hybrydowym i na obrotach ortogonalnych.

### AGGRESSIVE

Wszystko z FAST, a potem, zanim ruszą droższe progi:

- obroty ±15° i ±30° z interpolacją dwuliniową. Limit skali zostaje 1 600 000 pikseli, więc FAST nie podwaja klatki 1152×864. Obrót ma osobny limit 13 000 000 pikseli prostokąta po obrocie: 2976×2232 o 30° się mieści, ten sam kadr o 45° już nie. ±45° nie ma na drabinie.
- pasmo liniowe, zanim zejdzie piramida. Finder daje co najwyżej dziewięć wycinków i dekoduje tylko symbologie liniowe, więc wycinek nie składa fałszywego PDF417. Na pełnym wycinku kolejno: hybryda (`linear-band`), rozciągnięcie kontrastu, Otsu, średnia lokalna. Ciaśniejsze okno powtarza tylko hybrydę i Otsu (`linear-band-tight`, `linear-band-otsu-tight`). Każdy taki odczyt zużywa jeden slot budżetu. Otsu w skali 2× na wycinku nie wchodzi. Na pudle dziewięć wycinków schodzi hybrydą, rozciągnięciem, Otsu, średnią i ciaśniejszym oknem, i zostaje miejsce na Sauvolę oraz skalę 0,5×. Dodatkowe Otsu 2× na ciaśniejszym oknie (dziewięć prób) spycha tę skalę za setny slot.
- piramida o progu 500 i czynniku 3;
- siatka 3×3 kafelków o boku 55% obrazu, tylko gdy krótszy bok ma co najmniej 240 px. Dekodowane są wyłącznie symbologie 2D. Kafelki są na mały symbol, którego wcześniejsze próby nie wzięły.

Dopiero później:

- próg Gaussa i Sauvola (wąski promień, a gdy różni się od szerokiego, także szeroki);
- odszumienie punktowe, erozja, dylacja, otwarcie i zamknięcie na wąskim obrazie Sauvoli (tylko symbologie 2D);
- skala 1,5×, gdy wynik mieści się w limicie skali 1 600 000 pikseli, oraz 0,5×, gdy obraz ma więcej pikseli niż 800×800 i wynik też się mieści;
- do pięciu największych plam po sześciu dylatacjach wąskiego Sauvoli. Cztery skrajne punkty konturu idą do deskew jako próba `blob-quad` (Data Matrix, QR, Aztec, a przy wydłużonym boku także PDF417);
- gdy nadal nic nie ma albo włączone jest `findAll`: findery QR, Micro QR, Aztec i Data Matrix na odwróconej szarości. Nazwy prób zostają `qr-trace`, `mqr-trace`, `aztec-trace`, `dm-trace`.

Każda próba z harmonogramu zużywa jeden slot budżetu. Potwierdzenie paska 1D jest częścią tej samej próby i nie zużywa drugiego slotu. Po przekroczeniu liczby prób albo czasu pętla się zatrzymuje. FAST to 40 prób i 2,5 s, AGGRESSIVE to 100 prób i 8 s. `mode` zmienia tylko drabinę i nie przepisuje budżetu już ustawionego na opcjach. Pojedyncze wywołanie dekodera nie jest przerywane w środku.

Lokalizacja na obrazie dłuższym niż 1800 px najpierw zmniejsza obraz. Narożniki są mapowane z powrotem do współrzędnych źródła.

## Fazy — kryteria wyjścia

### Faza 0

- Jest powtarzalny zbiór i jedna komenda: `mvn test`.
- Raport podaje recall, fałszywe trafienia i czas dla baseline, FAST i AGGRESSIVE.
- Baseline jest zamrożony w teście, a nie „trochę ulepszony ZXing”.

### Faza 1

- Czyste kody priorytetowych symbologii czytają się w FAST.
- Na zbiorze przewag FAST ma ściśle wyższy recall niż baseline i zero fałszywych trafień na negatywach.
- Obrót 1D spoza wielokrotności 90° jest zadaniem trybu AGGRESSIVE.

### Faza 2

- QR w wyraźnej perspektywie, którego nie czyta baseline, jest czytany na ścieżce skanu. Na zbiorze wygrywa `original-hybrid`.
- Data Matrix ma ścieżkę `blob-quad`. Na zbiorze perspektywiczny symbol i symbol na etykiecie wygrywają na `original-hybrid`, zanim dojdzie kolejka kafelków i `blob-quad`.
- Narożniki wyniku są w układzie obrazu wejściowego, także po obrocie i skali.

### Faza 3

- Osobne ścieżki QR i Data Matrix da się włączać symbologią.
- Heurystyka odblasku i nierównego światła jest osobną próbą (dziś: lokalna normalizacja), a nie ręcznym progiem pod jeden plik.
- `findAll` zwraca dwa kody z jednego obrazu, których pojedynczy `decode` ZXinga nie zwraca razem. Działa to dla dwóch QR oraz dla QR obok Code 128.
- Tryb agresywny jest opt-in i ma twardszy budżet.

### Faza 4

- Priorytet symbologii pokryty testem okrężnym (zapis ZXingiem, odczyt naszą biblioteką).
- Limit prób, limit pikseli i deduplikacja są domyślne.
- Błąd jednego wariantu nie wyrzuca całego `read`. Brak kodu to pusta lista, nie wyjątek.
- Metadane: pozycja, orientacja, pewność, nazwa próby.

Zbiór produkcyjny (błysk, motion blur, folia, wiele etykiet na jednym zdjęciu) zostaje fazą 0 w wersji ciągłej: dokładamy pliki, nie zmieniamy definicji baseline.

### Port A — detektory

- Data Matrix według detektora zxing-cpp, łącznie z dużymi i nierównymi symbolami.
- Finder QR z poprawkami nieobecnymi w Javie 3.5.4: wersja 1, okrągłe wzorce, wiele małych symboli.
- Aztec czytany z wewnętrznych wzorców czasowych na symbolu, który nie leży płasko.
- Micro QR w naszym kodzie.
- Kryterium: `perspective-qr`, `qr-noise`, `perspective-datamatrix` i `datamatrix-on-label` czytają się tą ścieżką bez wywołania BoofCV. Baseline zostaje zamrożony. Negatywy zostają puste.

### Port B — binaryzacja

- `LocalAverage` z symetryczną interpolacją progu.
- Próby `tryInvert`, `tryRotate` i `tryDownscale` siedzą w harmonogramie obok już działających obrotów ±15° i ±30°.
- Kryterium: recall zbioru nie spada względem tabeli w [Pomiar](#pomiar).

### Port C — dekodery bitów

- Jedna symbologia na raz, w kolejności priorytetu.
- ZXing schodzi z tej symbologii dopiero po teście okrężnym (zapis ZXingiem, odczyt naszym dekoderem) i po zielonym `mvn test`.

### Port D — zejście rusztowania

- BoofCV, a z nim Trove, znika po Portach A i B.
- `zxing-core` i `javase` zostają. Niosą binaryzator hybrydowy obrazu oryginalnego, Reed-Solomon, korekcję PDF417 i formaty spoza własnego zestawu. Sam port dekoderów ich nie usuwa.

## Oczekiwania

Rusztowanie faz 1–2 wygrywa z czystym ZXingiem na inwersji, perspektywie QR i Data Matrix, odblasku, szumie, obrocie kodów 1D o 30° i 45° oraz na małym Data Matrix na dużej etykiecie. Na zbiorze z sekcji [Pomiar](#pomiar) ta przewaga jest na własnym kodzie. Zdjęcia, na których komercyjne SDK wygrywa dzięki własnej lokalizacji, mogą nadal paść. Kolejny krok na takim zdjęciu to nowy przypadek w zbiorze i heurystyka w Javie.

## Układ kodu

| Pakiet | Rola |
| --- | --- |
| `org.krzywanski.barcode` | API i harmonogram prób (`Scan`) |
| `org.krzywanski.barcode.image` | Luminancja, obroty, skale, homografia |
| `org.krzywanski.barcode.preprocess` | Progi i morfologia: Otsu, średnia lokalna, Gauss, Sauvola, erozja, dylacja, odszumienie |
| `org.krzywanski.barcode.decode` | Odczyt bitów i deduplikacja. Priorytetowe symbologie i kody macierzowe są własne. ZXing zostaje przy binaryzatorze hybrydowym, Reed-Solomonie, korekcji PDF417 i formatach spoza zestawu |
| `org.krzywanski.barcode.locate` | Findery QR, Micro QR, Aztec i Data Matrix oraz `blob-quad` |
| `org.krzywanski.Main` | CLI: `Main <plik> [--aggressive] [--all]` |

Testy w `src/test/java` generują zbiór i porównują go z baseline.

## Pomiar

Liczby poniżej pochodzą z `mvn test` na zbiorze syntetycznym tego repozytorium. Zbiór nie jest jeszcze zbiorem zdjęć produkcyjnych.

<!-- BENCHMARK-START -->
Jedno uruchomienie `mvn test` w tym repozytorium, 2026-10-12, już bez `boofcv-ip` i `boofcv-recognition`. Czasy to ścienny czas jednego `read` na obraz, bez mediany. Kolumny baseline / fast / aggressive to recall (odczytane oczekiwane treści / oczekiwane treści). `ok` na negatywie oznacza zero odczytów. `FP` w tej tabeli nie występuje.

| case | kind | baseline | fast | aggressive | fast ms | aggressive ms |
| --- | --- | --- | --- | --- | ---: | ---: |
| qr | CLEAN | 1/1 | 1/1 | 1/1 | 5 | 5 |
| datamatrix | CLEAN | 1/1 | 1/1 | 1/1 | 2 | 1 |
| code128 | CLEAN | 1/1 | 1/1 | 1/1 | 9 | 9 |
| pdf417 | CLEAN | 1/1 | 1/1 | 1/1 | 5 | 8 |
| ean13 | CLEAN | 1/1 | 1/1 | 1/1 | 6 | 6 |
| ean8 | CLEAN | 1/1 | 1/1 | 1/1 | 7 | 7 |
| upca | CLEAN | 1/1 | 1/1 | 1/1 | 9 | 8 |
| code39 | CLEAN | 1/1 | 1/1 | 1/1 | 11 | 10 |
| inverted-qr | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 7 | 6 |
| inverted-datamatrix | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 4 | 3 |
| inverted-code128 | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 36 | 23 |
| perspective-qr | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 9 | 6 |
| qr-block-phase | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 4 | 4 |
| qr-glare | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 17 | 15 |
| qr-noise | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 48 | 63 |
| perspective-datamatrix | FAST_ADVANTAGE | 0/1 | 1/1 | 1/1 | 11 | 7 |
| code128-rotated-30 | AGGRESSIVE_ADVANTAGE | 0/1 | 0/1 | 1/1 | 170 | 329 |
| code128-rotated-45 | AGGRESSIVE_ADVANTAGE | 0/1 | 0/1 | 1/1 | 144 | 231 |
| datamatrix-on-label | AGGRESSIVE_ADVANTAGE | 0/1 | 1/1 | 1/1 | 24 | 24 |
| tiny-qr | STRESS | 1/1 | 1/1 | 1/1 | 2 | 2 |
| qr-blur | STRESS | 1/1 | 1/1 | 1/1 | 4 | 4 |
| qr-gradient | STRESS | 1/1 | 1/1 | 1/1 | 3 | 3 |
| blank | NEGATIVE | ok | ok | ok | 18 | 30 |
| gradient | NEGATIVE | ok | ok | ok | 13 | 48 |
| noise | NEGATIVE | ok | ok | ok | 16 | 42 |
| two-qr | MULTI | 1/2 | 2/2 | — | 43 | — |
| qr-and-code128 | MULTI | 1/2 | 2/2 | — | 48 | — |

Suma treści w `Samples.all()` (bez wierszy MULTI): baseline 11/22, FAST 20/22, AGGRESSIVE 22/22. Fałszywych trafień brak, także na trzech negatywach w obu trybach.

Czyste kody priorytetu wygrywają na `original-hybrid`. Orientacja QR, Data Matrix i PDF417 jest 0, kodów liniowych −1.

Przewagi FAST, których baseline nie czyta (8/8 vs 0/8): inwersja QR i Data Matrix na `original-hybrid` (orientacja 0; biała macierz to `flipAll` w tej samej próbie), biały Code 128 na czarnym (`inverted-luma`, orientacja −1), QR w perspektywie (`original-hybrid`, orientacja 5, treść `QR-SKEW`), QR o poziomie korekcji H w złej fazie bloków (`original-hybrid`, orientacja 0), odblask (`illumination`, treść `QR-GLARE`), szum solno-pieprzowy 2% (`qr-trace`, treść `QR-NOISE`; mediana 3×3 jest drugim spojrzeniem w tej samej próbie, a surowy `QrDetector.read` na tej szarości zostaje pusty), Data Matrix obrócony i wpisany w czworokąt (`original-hybrid`, orientacja 24, treść `DM-SKEW`).

Z koszyka AGGRESSIVE_ADVANTAGE tylko dwa kody zostają wyłącznie przy trybie agresywnym (baseline i FAST po 0/2): Code 128 o 30° próbą `rotate--15.0` (orientacja 345, treść `ANGLE-128`) i Code 128 o 45° próbą `rotate--30.0` (orientacja 330, treść `ANGLE-45`). Mały Data Matrix na etykiecie 960×960 ma w tabeli nadal ten koszyk, a kolumna fast jest 1/1: czyta go `original-hybrid`, orientacja 0, treść `DM-TILE`. Baseline zostaje przy 0/1.

Ścieżka `blob-quad` jest sprawdzona osobno: po sześciu dylatacjach cztery skrajne punkty plamy prostują perspektywiczny Data Matrix do treści `DM-SKEW`. W skanie ten obraz wygrywa wcześniej, na `original-hybrid`.

Dwa QR: baseline 1/2, FAST z `findAll` 2/2 (`QR-LEFT`, potem `QR-RIGHT`, obie `original-hybrid`, orientacja 0). QR obok Code 128: baseline 1/2. Przy wyłączonym `findAll` zostaje sam QR (`QR-AND`, `original-hybrid`). FAST z `findAll` zwraca najpierw ten QR, potem `AND-128` jako Code 128, obie próby `original-hybrid`.

Stres (moduł 1 px, rozmycie 5×5, łagodny gradient) czytają też baseline i FAST. Te wiersze nie wchodzą do przewagi.
<!-- BENCHMARK-END -->

## Arte-Lab

Zbiór Medium Barcode 1D leży w `datasets/artelab-medium-barcode-1d/` (Dataset1 z autofokusem, Dataset2 bez, po 215 JPEG-ów). Pliki nie wchodzą do `Samples.all()` ani na classpath testów. `mvn test` sprawdza tylko obecność i etykiety oraz kilka zamrożonych fałszywych odczytów.

Nazwany zestaw, którego tekst jest kontraktem, odpala się osobno:

```
mvn -Partelab test
```

Są to klatki pochylone, które wygrywa obrót albo pasmo, kontrola `Foto(542)` na FAST (`illumination`, `8002205319804`), puste `Foto(725)` i `Foto(707)` oraz wydrukowany GTIN na pustej etykiecie `Foto(844)`.

Pełny pomiar 430 klatek nie jest testem. Z katalogu repozytorium:

```
mvn -q -Partelab-eval test-compile exec:java -Dexec.args="--out target/artelab-report.tsv"
```

Program czyta baseline (jeden `MultiFormatReader`, `HybridBinarizer`, `TRY_HARDER`), potem FAST, a AGGRESSIVE tylko wtedy, gdy FAST nie zwrócił etykiety. Drabina FAST jest przedrostkiem agresywnej i na trafieniu staje, więc pominięta kolumna ma klasę `skipped`.

Klasa odczytu:

- `exact` — tekst równy 13 cyfrom z pliku etykiety;
- `upca` — etykieta ma 13 cyfr i zaczyna się od zera, a tekst to te cyfry bez jednego zera wiodącego. Ośmiocyfrowy UPC-E nie jest trafieniem;
- `empty` — brak odczytu;
- `allowed` — plik etykiety jest pusty, a tekst jest wydrukowanym kodem tej klatki: `05102009199` to `8002330112752`, `Foto(844)` i `Foto(849)` to `7321925005431`;
- `fp` — każdy inny tekst;
- `error` — klatka nie dała się wczytać.

`unionHit` to `exact` albo `upca` na wyniku agresywnym, a gdy ta kolumna jest `skipped`, na wyniku FAST. `allowed` nie wlicza się do trafień ani do `fp`. Suma `unionHit + empty + allowed + fp + error` jest liczbą klatek. `target/` jest w `.gitignore`.

Jeden przebieg tej komendy, 2026-10-12, trwał 549 s. 430 klatek, zero błędów wczytania. Baseline i FAST, klatka po klatce, są te same co w pomiarze sprzed pasma liniowego.

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 208 | 1 | 1 | 5 | 1746 | 5649 |
| Dataset2 | 215 | 22 | 46 | 113 | 101 | 1 | 0 | 648 | 2778 |
| razem | 430 | 205 | 235 | 321 | 102 | 2 | 5 | 1746 | 5649 |

Wobec tamtego pomiaru przybyło 32 trafienia etykiety i żadne wcześniejsze nie zniknęło. Puste etykiety z wydrukowanym kodem to `05102009199` (`8002330112752`, `linear-band`) i `Foto(844)` (`7321925005431`, `scale-0.5`). `Foto(849)` zostaje puste. Pięć `fp` to zamknięta klasa: Data Matrix `2A127693515` na `05102009132`, Data Matrix `372260C` na `05102009161`–`163` oraz UPC-E `10058084` na `05102009159`. Najdłuższa klatka agresywna ma 5649 ms. Żadna nie doszła do 8 s, a FAST do 2,5 s.
