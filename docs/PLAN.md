# barcode-pro — specyfikacja

Czysto javowa biblioteka do odczytu kodów kreskowych. Algorytmy odczytu przenosimy z [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) 3.1.1 (Apache 2.0) do zwykłego bytecode Javy. Biblioteka C++ nie jest zależnością: brak JNI, brak natywnego OpenCV, brak zint i innych binariów platformowych.

Dokument jest planem i kontraktem implementacji. Zapis z pierwszej wersji, że silnikiem na stałe zostaje Javowy ZXing, a własny dekoder bitów leży poza zakresem, był błędny. BoofCV zeszło z buildu razem z przechodnim Trove: progi, morfologia i detektory są własne. Z ZXinga zostaje sam `core`: binaryzatory hybrydowy i globalny, `BitMatrix`/`BitArray`, Reed-Solomon i korekcja PDF417. Każda symbologia z `Symbology` ma własny dekoder, więc `MultiFormatReader` nie jest już wołany. `javase` jest tylko w testach (baseline). Dalszy kawałek tego rusztowania schodzi, gdy zamiennik czyta ten sam zbiór i nie dokłada fałszywych trafień.

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
- ZXing `core` 3.5.4 (Apache 2.0): binaryzatory, macierze bitów, Reed-Solomon, korekcja PDF417. `javase` tylko w testach: baseline i porównanie luminancji z `BufferedImageLuminanceSource`.
- `module-info`: moduł `io.github.kamilkrzywanski.barcode` eksportuje tylko pakiet API.
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

Pakiet publiczny: `io.github.kamilkrzywanski.barcode`.

- `BarcodeReader` — bezstanowe wejście. `read` można wołać z wielu wątków na tym samym obiekcie (każde wywołanie ma własny stan dekodera). `new BarcodeReader()` liczy niezależne próby równolegle na współdzielonej puli wątków-demonów (procesory − 1). `new BarcodeReader(executor)` używa puli użytkownika. `BarcodeReader.sequential()` liczy wszystko w wątku wołającym i staje na pierwszym trafieniu. Wątek wołający sam dokańcza każdą próbę, której pula jeszcze nie zaczęła, więc wywołanie z wątku tej samej puli się nie zakleszcza. Wszystkie trzy dają te same trafienia, gdy budżet czasu nie przerwie odczytu.
- `read(byte[] luminance, width, height, options)` i wariant z `offset` i `rowStride` — 8-bitowa luminancja, np. płaszczyzna Y z kamery. Tablica jest kopiowana. Obie pierwsze próby i drabina czytają te same bajty.
- `ReadOptions.fast()` — domyślne. Mało prób, budżet 2,5 s, 40 prób.
- `ReadOptions.aggressive()` — pełny harmonogram, budżet 8 s, 100 prób.
- `findAll(boolean)` — po pierwszym trafieniu nie przerywa. Domyślnie `false`.
- `symbologies(...)` — podzbiór. Domyślnie priorytetowa lista plus Aztec i Micro QR. Code 93, ITF, Codabar oraz `DATABAR`, `DATABAR_LIMITED` i `DATABAR_EXPANDED` są tylko na życzenie. Narożniki DataBar to `EstimatePosition` z zxing-cpp: linia przez środek wierszy, na których widać pary, albo cztery narożniki symbolu piętrowego. Po odczycie skaner czyta dalej wiersze w dół, dopóki widać te same pary, więc obrys sięga ostatniego wiersza dolnego rzędu; zxing-cpp zapamiętuje tylko pierwszy wiersz każdej pary.
- `eanAddOn(EanAddOn)` — dodatek EAN-2/EAN-5 po prawej od EAN/UPC: `IGNORE` (domyślnie, jak w zxing-cpp), `READ` albo `REQUIRE`. Dodatek wraca w `BarcodeHit.addOn()`, tekst zostaje głównym symbolem. Przy `READ`, gdy pierwszy wiersz z symbolem nie przecina dodatku, skaner bierze inny wiersz z tym samym symbolem i dodatkiem. Korelator EAN nie widzi dodatku, więc przy `REQUIRE` nie startuje.
- `BarcodeHit.bytes()` — treść przed zastosowaniem zestawu znaków: dla QR, Micro QR, Data Matrix, Aztec i PDF417 bajty danych bez znaczników ECI (`Content::bytes` w zxing-cpp), więc treść binarna wraca bez strat; dla symboli liniowych znaki tekstu. `equals` porównuje zawartość tablicy.
- `timeBudgetMillis(Long.MAX_VALUE)` — brak limitu czasu. Wtedy odczyt zatrzymuje tylko budżet prób i wynik nie zależy od szybkości ani obciążenia maszyny. Testy `-Partelab` tak czytają.
- `BarcodeHit` — jeden odczyt.
- `Symbology`, `DecodeMode`, `ImagePoint`.

`confidence` jest heurystyką kolejności próby (wcześniejsza, czystsza próba → wyżej), nie skalibrowanym prawdopodobieństwem. `orientationDegrees` to stopnie zgodne z ruchem wskazówek zegara na obrazie o osi Y w dół; `-1` znaczy „nieznana”. `attempt` jest metadaną diagnostyczną (`original-hybrid`, `inverted-luma`, `illumination`, `qr-trace`, `blob-quad`, `sauvola`, …) i może się zmienić między wersjami.

Fałszywe trafienia 1D są ograniczone trojako. Warianty morfologiczne dekodują tylko symbologie 2D. Odczyt 1D bez dwóch punktów odległych o co najmniej 30 px jest odrzucany. Odczyt 1D musi powtórzyć się na równoległym pasku (8 px grubości, 4 px od linii, którą przyjął ZXing, z zapasem 40 px na cichą strefę): ta sama symbologia i ta sama treść. Jedna linia skośnych kresek, która sumą kontrolną wychodzi jako krótki UPC-E, odpada. Gdy obraz nie mieści takiego paska, trafienie zostaje. Pełny EAN-13 albo UPC-A może zamiast paska powtórzyć się na dowolnym innym wierszu tej samej bitmapy (reguła `minLineCount = 2` z zxing-cpp), ale nie na obrazie zmniejszonym (`scale-0.5`, `downscale-*`) ani na wycinku pasma: tam sąsiednie wiersze są prawie kopiami i na Arte-Lab powtarzały błędne kody z poprawną sumą kontrolną, np. `6328925005431`. Krótki UPC-E i EAN-8 dalej wymagają paska.

Na próbie, której nazwa zaczyna się od `linear-band`, EAN ma dwie dodatkowe bramki. Krótki przebieg odpada, gdy ta sama bitmapa niesie inny tekst o rozpiętości co najmniej 8 px i ściśle większej niż przebieg trafienia. Przebieg, który sam ma co najmniej 8 px, zostaje. Odczyt z progu binarnego (Otsu albo średnia lokalna) zostaje, gdy ten sam tekst wraca na co najmniej czterech wierszach albo na wierszach odległych o co najmniej 8 px. Pasek o wysokości 48 px nadal wymaga tego warunku. Wyższy wycinek może zostać przy dwóch wierszach: odblask na `05102009191` zostawia tylko taką parę, a średnia lokalna czyta z niej etykietę. Hybryda i rozciągnięcie kontrastu tej drugiej bramki nie dostają, bo jednowierszowy właściwy EAN ma zostać.

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

1. Oryginał, `HybridBinarizer` na luminancji ZXinga: `(306 R + 601 G + 117 B + 512) >> 10`, piksel w pełni przezroczysty jako biały, surowe próbki obrazu `BYTE_GRAY`. Te bajty są równe `BufferedImageLuminanceSource` (`GrayConversionTest`), a liczą się w tym samym przebiegu co szarość drabiny. Próba `original-hybrid`. Własne dekodery macierzowe i priorytetowe liniowe czytają tę bitmapę. Biały symbol macierzowy na czarnym to `flipAll` tej samej bitmapy, nadal pod tą nazwą próby.
2. Ten sam obraz, `GlobalHistogramBinarizer` (`original-global`).
3. Odwrócona luminancja (`255 − wartość`), potem `HybridBinarizer` (`inverted-luma`). `flipAll` odwraca już zbinearyzowaną macierz i nie czyta białego Code 128 na czarnym tle. Ten kod wygrywa tutaj.
4. Biały margines 4 px (`pad-4`). Przesuwa fazę bloków 8×8 HybridBinarizera.
5. Rozciągnięcie kontrastu (percentyle 1%–99%), tylko gdy obraz się zmienia.
6. Lokalna normalizacja oświetlenia (średnia pudełkowa).
7. Obroty ortogonalne 90°, 180°, 270° (przepisanie pikseli, bez interpolacji). Tylko symbologie liniowe i PDF417: findery QR, Micro QR, Aztec i Data Matrix nie zależą od kąta, a na 1225 obrazach z Arte-Lab i próbek zxing-cpp nie przeczytały na obrocie nic, czego nie wziął obraz prosty. Jedyny odczyt macierzowy na obrocie był fałszywym Aztekiem w MaxiCode. To samo dotyczy obrotów ±15° i ±30° w AGGRESSIVE.
8. Próg adaptacyjny średniej lokalnej i Otsu. Obraz binarny wraca do luminancji i idzie przez `GlobalHistogramBinarizer`.
9. Skala 2×, najbliższy sąsiad, gdy krótszy bok ≤ 900 px i wynik mieści się w 1 600 000 pikseli. To jest limit skali. Obrót ±15° i ±30° ma osobny limit i go nie podnosi.
10. Własne findery: `qr-trace`, `mqr-trace`, `aztec-trace`, `dm-trace`. W `qr-trace`, gdy LocalAverage nic nie zwróci, ten sam finder dostaje medianę 3×3 i czyta jeszcze raz, pod tą samą nazwą próby. Środek okna mediany liczy się bez powtarzania piksela brzegowego; wynik jest ten sam co sortowanie okna z zaciskiem. Aztec o wymiarze co najmniej 35 jest próbkowany z lokalnej siatki wewnątrz `aztec-trace`. FAST tu wraca.

Po `original-global`, gdy zbiór symbologii nie jest pusty, próby od odwróconej luminancji do skali 2× są liczone razem i przyjmowane w kolejności z listy wyżej. Trafienie na hybrydzie albo na progu globalnym tych obrazów już nie buduje. Przy AGGRESSIVE cztery kąty ±15° i ±30°, które mieszczą się w limicie obrotu, też są liczone razem i przyjmowane od −15° w górę. `stop()` przed zaliczeniem próby odrzuca późniejszy wynik. Kolejność zwycięzcy zostaje ta sama. Bez `findAll` te próby nie szukają Data Matrix; Data Matrix czytają `original-*` i `dm-trace` (patrz [Pomiar zmian](#pomiar-zmian)).

Przy `findAll` pętla nie staje na pierwszym trafieniu. W jednej próbie własny odczyt zwraca symbole znalezione na tej bitmapie. `GenericMultipleBarcodeReader` zostaje dla formatu, który nadal idzie do ZXinga, na oryginale hybrydowym i na obrotach ortogonalnych.

### AGGRESSIVE

Wszystko z FAST, a potem, zanim ruszą droższe progi:

- obroty ±15° i ±30° z interpolacją dwuliniową. Limit skali zostaje 1 600 000 pikseli, więc FAST nie podwaja klatki 1152×864. Obrót ma osobny limit 13 000 000 pikseli prostokąta po obrocie: 2976×2232 o 30° się mieści, ten sam kadr o 45° już nie. ±45° nie ma na drabinie.
- pasmo liniowe, zanim zejdzie piramida. Finder daje co najwyżej dziewięć wycinków i dekoduje tylko symbologie liniowe, więc wycinek nie składa fałszywego PDF417. Na pełnym wycinku kolejno: hybryda (`linear-band`), rozciągnięcie kontrastu, Otsu, średnia lokalna. Ciaśniejsze okno powtarza tylko hybrydę i Otsu (`linear-band-tight`, `linear-band-otsu-tight`). Każdy taki odczyt zużywa jeden slot budżetu. Po zwykłych krokach, i tylko gdy nadal pudłują oraz całe zdjęcie ma co najwyżej 1 000 000 pikseli, dochodzi czternaście prób: pozioma maska wyostrzająca (promień 3, waga 3) na pierwszym wycinku, cięcia percentylowe 35, 45 i 50 uśrednionych wierszy, lokalna średnia najostrzejszych wierszy i krótkie wyostrzenie tej średniej, średnia tych wierszy cięta w 50. i w 40. percentylu, na drugim wycinku wyostrzenie tej średniej o promieniu 2 i wadze 4, na trzecim ta sama średnia oraz mediana i najciemniejszy piksel kolumny, na czwartym maska o promieniu 2 i wadze 2,5 plus Otsu, a na ciaśniejszym oknie piątego najbliższy sąsiad 2× i Otsu, gdy 2× mieści się w 2 000 000 pikseli. Zdjęcie powyżej miliona pikseli pomija te próby: wyostrzenie całego wycinka na kadrze 2976×2232 zjada około trzech sekund. Na pełnym pudle 1152×864 skala 0,5× jest setną próbą i jeszcze się wykonuje. Jedna dodatkowa próba przed nią gubi `Foto(799)` i `Foto(848)`.
- piramida o progu 500 i czynniku 3;
- siatka 3×3 kafelków o boku 55% obrazu, tylko gdy krótszy bok ma co najmniej 240 px. Dekodowane są wyłącznie symbologie 2D. Kafelki są na mały symbol, którego wcześniejsze próby nie wzięły.

Dopiero później:

- Sauvola (wąski promień, a gdy różni się od szerokiego, także szeroki);
- skala 1,5×, gdy wynik mieści się w limicie skali 1 600 000 pikseli, oraz 0,5×, gdy obraz ma więcej pikseli niż 800×800 i wynik też się mieści;
- slot po skali 0,5×. Próg Gaussa na pełnej klatce nie odzyskał etykiety. Gdy szukanie pasma już było, `findAll` jest wyłączone i są symbologie liniowe, ten slot zamiast Gaussa wyostrza pierwszy wycinek maską o promieniu 4 i wadze 4, potem Otsu (`linear-band-us4-otsu`). Inaczej zostaje próg Gaussa. Siatka kątów ma krok 3°, więc miękki kod potrafi zostać o 2° obok wycinka. Kolejne sloty, dopóki starcza budżetu prób, obracają wycinek o −2° (`linear-band-us4-rot`). Pierwszy bierze najszerszy miękki wycinek, co najmniej 400×100, o najniższej energii krawędzi, i wyostrza go promieniem 3: promień 4 gubi `Foto(837)` i `Foto(516)`, a promień 3 nadal czyta `Foto(849)`, `Foto(846)` i `Foto(851)`. Dalsze wycinki zostają przy promieniu 4. Potem idą wycinki o numerach 1, 2, 3, 0 i 4, z pominięciem już sprawdzonego, nadal o −2°. Gdy te pudłują i zostaje slot, wycinek 1 idzie jeszcze raz o −1°: tak czyta się `Foto(698)`. Wycinek 0 o −1° fałszuje `Foto(828)`, więc tego kąta tam nie ma. Ten sam wycinek 0 o +2° czyta `Foto(515)` i `Foto(824)`, i na dawnych pudłach nie fałszuje. Gdy i to pudłuje, dochodzą trzy kąty, każdy bez fałszywego odczytu na dawnych pudłach: wycinek 2 o +0,5° czyta `Foto(823)`, wycinek 3 o +1° czyta `Foto(828)`, a wycinek 3 o +2° czyta `Foto(830)`. Wszystkie trzy to `8011642111896`. Gdy i to pudłuje, trzy inne maski zostają na końcu. Wycinek 2 obrócony o −1° i ścięty medianą najostrzejszych wierszy po wyostrzeniu czyta `Foto(484)`. Wycinek 3 obrócony o +1°, ściągnięty z trapezu do prostokąta, potem ta sama maska i Otsu, czyta `Foto(496)`. Wycinek 0 obrócony o −1° i wyostrzony na średniej najostrzejszych wierszy promieniem 2 czyta UPC-A `Foto(761)`. Ostatni slot, jeden na wszystkie szerokie wycinki, koreluje najostrzejsze wiersze z szablonami cyfr EAN (`ean-correlate`): rozmyty pręt nie przechodzi progu, ale kształt modułu nadal pasuje do jednej cyfry. Tak czyta się `Foto(312)` (`8011642115887`) i `Foto(827)` (`8011642111896`) oraz UPC-A `Foto(760)` i `Foto(762)`. Strażnik końcowy nie jest wymagany, bo na ciasnym wycinku zlewa się z ciemną krawędzią. Cyfra musi mieć korelację co najmniej 0,52 i margines 0,02 nad drugim wzorcem, a całość parzystość i sumę kontrolną. Gdy ta siatka pudłuje na każdym szerokim wycinku, ten sam slot schodzi na grubszą: krok modułu 0,2, początek co najmniej co piksel, bez testu strażników, próg cyfry 0,55. Rozmazany pręt ma ujemną korelację strażnika, a cyfry nadal pasują. Tak czyta się `Foto(751)` (`4902520242204`) i UPC-A `Foto(771)`. Ten sam krok początku i ten sam próg cyfry, ale krok modułu 0,1, czyta `Foto(714)` (`8001410088710`): prawdziwy moduł ma 3,1 i nie leży na kroku 0,2. Drobny krok początku, bez strażników, przyjmuje fałszywy kod na `Foto(811)` i trwa sekundy, więc początek zostaje przy co najmniej jednym pikselu. Obie luźne siatki idą po strzeżonej, bo inaczej podmieniłyby jej trafienie. Numer 6 nie wchodzi na tę listę: na `Foto(839)` jest jedynym odczytem przy −2°, a tekst to wydrukowany GTIN, nie literówka z etykiety, więc byłby fałszywy. Gdy numer 6 sam jest najmiększym wycinkiem, jak na `Foto(849)`, i tak idzie pierwszy. Przy `findAll` tych slotów nie ma. Potem odszumienie punktowe, erozja, dylacja, otwarcie i zamknięcie na wąskim obrazie Sauvoli (tylko symbologie 2D); te nadal nic nie odzyskały. Na pełnym pudle 1152×864 budżet kończy się na skali 0,5× i żaden z tych slotów już nie startuje;
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
- `zxing-core` zostaje: binaryzatory, macierze bitów, Reed-Solomon, korekcja PDF417. `javase` zeszło do testów razem z `BufferedImageLuminanceSource`.

## Tekst bez ECI

Bajty, których nie obejmuje żaden ECI, idą przez `TextDecoder.guessEci`, czyli `GuessTextEncoding` z `Content.cpp` zxing-cpp 3.1.1: najpierw poprawny UTF-8 ze znakiem wielobajtowym (albo BOM), potem Shift_JIS, a przy remisie ISO-8859-1. Tak czytają QR i Micro QR; oficjalny test `SimpleSJIS` daje katakanę. Data Matrix, Aztec i PDF417 używają `guessUtf8OrLatin1`: UTF-8, gdy bajty są poprawnym UTF-8, w innym razie ISO-8859-1. zxing-cpp zgaduje tam też Shift_JIS, przez co krótki tekst Latin-1 jak `25°C` albo `£5` wychodzi katakaną, a te standardy domyślnie są ISO-8859-1. MaxiCode zostaje przy ISO-8859-1, jak `defaultCharset` w zxing-cpp. Przed tą zmianą QR z UTF-8 bez ECI (np. `Zażółć gęślą jaźń`) wychodził jako mojibake; czyta to `TextEncodingTest`.

## Pomiar zmian

Zmiana w drabinie albo w dekoderze idzie z migawką przed i po:

```
mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot-po.tsv"
diff target/snapshot-przed.tsv target/snapshot-po.tsv
```

`Snapshot` czyta wszystkie 1225 obrazów (Arte-Lab, próbki zxing-cpp w `datasets/` i w `src/test/resources`) zwykłym ZXingiem (`zxing`) oraz w FAST, AGGRESSIVE i FAST z `findAll`, bez budżetu czasu. Wynik nie ma czasów, więc dwa przebiegi tego samego kodu dają te same bajty na każdej maszynie, a `diff` pokazuje każdy odczyt, któremu zmienił się tekst, symbologia, próba, orientacja, pewność albo narożniki. Zmiana czysto wydajnościowa ma dać pusty `diff`. Obok TSV powstaje `<plik>.summary.md`: dla każdego zbioru i folderu liczba obrazów przeczytanych zgodnie z etykietą, liczba przeczytanych błędnie, odczyty na obrazach bez kodu, a dla QR znanych tylko z narożników liczba znalezionych kodów.

Zbiory spoza repozytorium pobiera `scripts/fetch-datasets.sh` (domyślnie do `~/.cache/barcode-pro/datasets`, sprawdza SHA-256, rozpakowuje). Żadne nie wymaga konta:

- KAIST DEAL (Do i Kim, Zenodo 13586402, CC BY 4.0): standardowy zbiór `barcode_bb` (EAN-13, kod w nazwie pliku; podział testowy `single_test` 2000 zdjęć i treningowy `rec` 1200), `e8_bb` (EAN-8), `multi_bb` (20 zdjęć po kilka kodów, etykiety to tylko ramki, więc liczy się odczyt EAN/UPC ze środkiem w ramce) i `background` (zdjęcia bez kodu). `rec` ma 482 zdjęcia w rozdzielczościach aparatów Arte-Lab, w tym zdjęcia o GTIN-ach z etykiet Arte-Lab, a AGGRESSIVE był strojony na Arte-Lab, więc raport pokazuje go osobno i nie wlicza do sum; `single_test` ma takich zdjęć 6. Starsze archiwum `real.zip` autorów zawiera wycinki zdjęć Arte-Lab i skrypt go nie pobiera.
- Obrazy testowe ZXinga 3.5.4 z oczekiwanym tekstem (Apache 2.0).
- BoofCV QR v3: trudne QR w 16 kategoriach `detection`, etykiety to tylko narożniki; kod liczy się jako znaleziony, gdy środek odczytu leży w etykiecie albo odwrotnie. Folder `decoding` (27 kodów z tekstem) liczy się jak zwykłe etykiety tekstowe.

```
scripts/fetch-datasets.sh
mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot.tsv --external $HOME/.cache/barcode-pro/datasets"
```

Zenodo podaje jedno połączenie wolno (ok. 60 KB/s), więc skrypt pobiera duży plik ośmioma zakresami naraz i wznawia przerwaną część przy następnym uruchomieniu. BarBeR (Vezzali i in., Modena: 8748 zdjęć z 12 zbiorów, każdy kod z typem, wielokątem i tekstem) wymaga konta, więc skrypt go nie pobiera, tylko sprawdza i rozpakowuje podane archiwum: `BARBER_ZIP=<ścieżka> scripts/fetch-datasets.sh`. Kod liczy się jako przeczytany, gdy któryś odczyt ma jego tekst; kody bez tekstu (autorzy zapisali `-1` lub `^`) i typy, których nie czyta żaden czytnik tutaj (kody pocztowe, IATA 2 of 5, samotny EAN-2), nie wchodzą do mianownika. Podzbiory `Artelab`, `Artelab Extended` i `Deal Kaist` raport pokazuje osobno, poza sumami: to te same zdjęcia Arte-Lab i KAIST, które są już wyżej.

2026-10-14: przeniesienie pakietów, usunięcie martwej ścieżki `MultiFormatReader`, jedna konwersja obrazu zamiast trzech, leniwe dekodery w próbie, `Fanout`, szybsza mediana 3×3, korelator EAN na sumach całkowitych i `Bits` prosto z `BitMatrix` dały pusty `diff` (3675 odczytów). Na 70 zdjęciach Arte-Lab (50 z Dataset2, 20 z Dataset1) czas CPU spadł z 90,7 s do 61,1 s w FAST i ze 197 s do 141 s w AGGRESSIVE, przy tych samych trafieniach; pomiar na obciążonej maszynie, dwie rundy na przemian. Przepisany `traceStep` detektora Data Matrix (bez alokacji `P` na próbkę) też dał pusty `diff`. Zgadywanie kodowania naprawiło 4 obrazy (`microqrcode-1/M3-Binary`, `M4-Binary`, `qrcode-2/10`, `qrcode-2/30`), a pominięcie detektorów macierzowych na obrotach usunęło fałszywy AZTEC `083` z `maxicode-1/mode5-sequence2of3` i nic więcej nie zmieniło.

Reguła dwóch wierszy dla EAN-13/UPC-A: FAST na Arte-Lab 235 → 243 przy tych samych 5 `fp`, AGGRESSIVE bez zmiany liczb (378, 5 `fp`; część klatek wygrywa wcześniej, na `original-hybrid`), próbki zxing-cpp 442 → 457 w FAST i 454 → 458 w AGGRESSIVE. Wszystkie UPC-A i EAN-13, które czytał tylko ZXing, czytają się w FAST (`RetailTwoLineTest`). Ta sama reguła bez wyjątku dla obrazów zmniejszonych i wycinków dawała w AGGRESSIVE 384 trafienia, ale 10 `fp`.

Data Matrix w wariantach drabiny FAST (`inverted-luma`, `pad-4`, `contrast-stretch`, `illumination`, `adaptive-mean`, `otsu`, `scale-2`) już nie biegnie, chyba że jest `findAll`. Detektor śledzi każdą krawędź obrazu w czterech kierunkach i obu polaryzacjach; na zdjęciu kodu liniowego był to największy koszt trybu FAST, a na 1225 obrazach te warianty nie przeczytały żadnego Data Matrix, którego nie wzięły `original-*` i `dm-trace`. `diff` migawki pusty. Na tych samych 70 zdjęciach, przy podobnym obciążeniu: kod sprzed zmian 2026-10-14 FAST 67,1 s CPU i 18,5 s ściennie, AGGRESSIVE 172 s i 50,8 s; teraz FAST 22,7 s i 8,2 s, AGGRESSIVE 104 s i 28,9 s.

## Oczekiwania

Rusztowanie faz 1–2 wygrywa z czystym ZXingiem na inwersji, perspektywie QR i Data Matrix, odblasku, szumie, obrocie kodów 1D o 30° i 45° oraz na małym Data Matrix na dużej etykiecie. Na zbiorze z sekcji [Pomiar](#pomiar) ta przewaga jest na własnym kodzie. Zdjęcia, na których komercyjne SDK wygrywa dzięki własnej lokalizacji, mogą nadal paść. Kolejny krok na takim zdjęciu to nowy przypadek w zbiorze i heurystyka w Javie.

## Układ kodu

| Pakiet | Rola |
| --- | --- |
| `io.github.kamilkrzywanski.barcode` | API i harmonogram prób (`Scan`) |
| `io.github.kamilkrzywanski.barcode.image` | Luminancja, obroty, skale, homografia |
| `io.github.kamilkrzywanski.barcode.preprocess` | Progi i morfologia: Otsu, średnia lokalna, Gauss, Sauvola, erozja, dylacja, odszumienie |
| `io.github.kamilkrzywanski.barcode.decode` | Odczyt bitów i deduplikacja. Priorytetowe symbologie i kody macierzowe są własne. ZXing zostaje przy binaryzatorze hybrydowym, Reed-Solomonie, korekcji PDF417 i formatach spoza zestawu |
| `io.github.kamilkrzywanski.barcode.locate` | Findery QR, Micro QR, Aztec i Data Matrix oraz `blob-quad` |
| `io.github.kamilkrzywanski.barcode.cli` | CLI: `java -jar barcode-pro.jar <plik> [--aggressive] [--all]` |

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

Są to klatki pochylone, które wygrywa obrót albo pasmo, kontrola `Foto(542)` na FAST (`illumination`, `8002205319804`), odzyskane `Foto(503)`, `Foto(543)`, `Foto(707)`, `Foto(725)`, `Foto(481)`, `Foto(573)`, `Foto(728)`, `Foto(501)`, `Foto(556)`, `Foto(817)`, `Foto(820)`, `Foto(406)`, `Foto(483)`, `Foto(738)`, `Foto(504)`, `Foto(480)`, `Foto(676)`, `Foto(829)`, `Foto(427)`, `Foto(677)`, `Foto(846)`, `Foto(851)`, `Foto(489)`, `Foto(675)`, `Foto(678)`, `Foto(683)`, `Foto(722)`, `Foto(516)`, `Foto(837)`, `Foto(842)`, `Foto(698)`, `Foto(515)`, `Foto(824)`, `Foto(823)`, `Foto(828)`, `Foto(830)`, `Foto(484)`, `Foto(496)`, `Foto(312)`, `Foto(827)`, `Foto(751)` i `Foto(714)`, `05102009191`, UPC-A `Foto(765)`, `Foto(776)`, `Foto(778)`, `Foto(779)`, `Foto(763)`, `Foto(766)`, `Foto(769)`, `Foto(770)`, `Foto(772)`, `Foto(767)`, `Foto(761)`, `Foto(760)`, `Foto(762)` i `Foto(771)` oraz wydrukowany GTIN na pustej etykiecie `Foto(844)` i `Foto(849)`.

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

Kolejny przebieg tej komendy, 2026-10-13, trwał 234 s. 430 klatek, zero błędów wczytania. Tekst baseline i FAST, klatka po klatce, jest ten sam co w przebiegu 240 s.

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 909 | 1808 |
| Dataset2 | 215 | 22 | 46 | 169 | 44 | 2 | 0 | 264 | 2475 |
| razem | 430 | 205 | 235 | 378 | 44 | 3 | 5 | 909 | 2475 |

Wobec pomiaru z union 377 przybyło jedno trafienie i nic nie zniknęło. `Foto(714)` czyta `8001410088710` z `ean-correlate`, z kroku modułu 0,1 w tym samym slocie. Ta siatka startuje dopiero gdy strzeżona korelacja i krok 0,2 pudłują na wszystkich szerokich wycinkach. Krok początku zostaje co najmniej piksel, a próg cyfry 0,55. Drobny krok początku na tych samych wycinkach przyjmuje fałszywy kod `Foto(811)` i trwa kilka sekund, więc go nie ma. `Foto(811)` zostaje puste. Żaden wcześniejszy odczyt nie zmienił próby, nie ma nowego `fp`, a komórki FAST stoją. `Foto(751)` zostaje na `4902520242204`, `Foto(771)` na UPC-A `051122414831`, oba nadal z kroku 0,2. `Foto(799)` i `Foto(848)` zostają na `scale-0.5`. `Foto(849)` zostaje dozwolonym odczytem `7321925005431`. `Foto(839)` dalej pudłuje: etykieta ma literówkę, a wydrukowany GTIN nie jest trafieniem. Pięć `fp` to ta sama zamknięta klasa. Najdłuższa klatka agresywna ma 2475 ms (`Foto(753)`, pusto): krok 0,1 dochodzi na pełnym pudle. W Dataset1 najdłuższa ma 1808 ms (`PICT00gfhh38`, `linear-band-mean`). FAST najdłużej 909 ms, też `PICT00gfhh38` (klasa `empty`). W Dataset2 FAST najdłużej 264 ms (`Foto(477)`, klasa `empty`). Żadna nie doszła do 8 s, a FAST do 2,5 s.

Przebieg 240 s miał union 377. 430 klatek, zero błędów wczytania. Tekst baseline i FAST, klatka po klatce, był ten sam co w przebiegu 226 s.

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 914 | 1880 |
| Dataset2 | 215 | 22 | 46 | 168 | 45 | 2 | 0 | 363 | 1430 |
| razem | 430 | 205 | 235 | 377 | 45 | 3 | 5 | 914 | 1880 |

Wobec pomiaru z union 375 przybyły dwa trafienia i nic nie zniknęło. Oba schodzą z `ean-correlate`, z grubszej siatki w tym samym slocie, która startuje dopiero gdy strzeżona korelacja pudłuje na wszystkich szerokich wycinkach. `Foto(751)` czyta `4902520242204`. `Foto(771)` czyta UPC-A `051122414831`. Rozmazany pręt ma ujemną korelację strażnika początku, a cyfry nadal pasują; krok modułu 0,2 i próg cyfry 0,55 ten odczyt trzymają. Drobna siatka bez strażników na tych samych wycinkach przyjmuje fałszywy kod `Foto(811)` i trwa kilka sekund, więc jej nie ma. Żaden wcześniejszy odczyt nie zmienił próby, nie ma nowego `fp`, a komórki FAST stoją. `Foto(799)` i `Foto(848)` zostają na `scale-0.5`. `Foto(849)` zostaje dozwolonym odczytem `7321925005431`. `Foto(839)` dalej pudłuje: etykieta ma literówkę, a wydrukowany GTIN nie jest trafieniem. `Foto(811)` zostaje puste. Pięć `fp` to ta sama zamknięta klasa. Najdłuższa klatka agresywna ma 1880 ms (`PICT00gfhh38`, `linear-band-mean`). W Dataset2 najdłuższa ma 1430 ms (`Foto(719)`, pusto): grubsza siatka dochodzi na pełnym pudle. FAST najdłużej 914 ms, też `PICT00gfhh38` (klasa `empty`). W Dataset2 FAST najdłużej 363 ms (`Foto(518)`, klasa `empty`). Żadna nie doszła do 8 s, a FAST do 2,5 s.

Przebieg 226 s miał union 375. 430 klatek, zero błędów wczytania. Tekst baseline i FAST, klatka po klatce, był ten sam co w przebiegu 227 s.

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 895 | 1847 |
| Dataset2 | 215 | 22 | 46 | 166 | 47 | 2 | 0 | 271 | 760 |
| razem | 430 | 205 | 235 | 375 | 47 | 3 | 5 | 895 | 1847 |

Wobec pomiaru z union 371 przybyły cztery trafienia i nic nie zniknęło. Wszystkie schodzą z `ean-correlate`, korelacji cyfr na najostrzejszych wierszach wycinka. `Foto(312)` czyta `8011642115887`. `Foto(827)` czyta `8011642111896`. `Foto(760)` i `Foto(762)` czytają UPC-A `051122414831`. Żaden odczyt nie zmienił próby, nie ma nowego `fp`, a komórki FAST stoją. `Foto(799)` i `Foto(848)` zostają na `scale-0.5`. `Foto(849)` zostaje dozwolonym odczytem `7321925005431`. `Foto(839)` dalej pudłuje: etykieta ma literówkę, a wydrukowany GTIN nie jest trafieniem. Pięć `fp` to ta sama zamknięta klasa. Najdłuższa klatka agresywna ma 1847 ms (`PICT00gfhh38`, `linear-band-mean`). W Dataset2 najdłuższa ma 760 ms (`Foto(555)`, pusto). FAST najdłużej 895 ms, też `PICT00gfhh38` (klasa `empty`). Żadna nie doszła do 8 s, a FAST do 2,5 s.

Przebieg 227 s miał union 371. Wobec pomiaru z union 368 przybyły wtedy trzy trafienia i nic nie zniknęło. `Foto(484)` czyta `8011642115962` z `linear-band-us4-top`, wycinek 2 obrócony o −1°. `Foto(496)` czyta `4902030187590` z `linear-band-key`, wycinek 3 obrócony o +1° i ściągnięty z trapezu. `Foto(761)` czyta UPC-A `051122414831` z `linear-band-su2-rot`, wycinek 0 obrócony o −1°. Najdłuższa klatka agresywna miała 1990 ms (`PICT00gfhh38`, `linear-band-mean`). W Dataset2 najdłuższa miała 787 ms (`Foto(555)`, pusto). FAST najdłużej 973 ms, też `PICT00gfhh38` (klasa `empty`).

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 973 | 1990 |
| Dataset2 | 215 | 22 | 46 | 162 | 51 | 2 | 0 | 276 | 787 |
| razem | 430 | 205 | 235 | 371 | 51 | 3 | 5 | 973 | 1990 |

Przebieg 223 s miał union 368. Wobec pomiaru z union 365 przybyły wtedy trzy trafienia, wszystkie na `linear-band-us4-rot` i wszystkie z etykietą `8011642111896`. `Foto(823)` schodzi z wycinka 2 obróconego o +0,5°. `Foto(828)` schodzi z wycinka 3 obróconego o +1°. `Foto(830)` schodzi z wycinka 3 obróconego o +2°. Najdłuższa klatka agresywna miała 1837 ms (`PICT00gfhh38`, `linear-band-mean`), a w Dataset2 736 ms (`Foto(518)`, `scale-0.5`). FAST najdłużej 895 ms, też `PICT00gfhh38` (klasa `empty`).

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 895 | 1837 |
| Dataset2 | 215 | 22 | 46 | 159 | 54 | 2 | 0 | 262 | 736 |
| razem | 430 | 205 | 235 | 368 | 54 | 3 | 5 | 895 | 1837 |

Przebieg 227 s miał union 365. Wobec pomiaru z union 363 przybyły wtedy dwa trafienia, oba na `linear-band-us4-rot`, z wycinka 0 obróconego o +2°. `Foto(515)` czyta `5030930017491`. `Foto(824)` czyta `8011642111896`. Najdłuższa klatka agresywna miała 1839 ms (`PICT00gfhh38`, `linear-band-mean`), a w Dataset2 743 ms (`Foto(516)`, trafienie). FAST najdłużej 922 ms, też `PICT00gfhh38` (klasa `empty`).

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 922 | 1839 |
| Dataset2 | 215 | 22 | 46 | 156 | 57 | 2 | 0 | 279 | 743 |
| razem | 430 | 205 | 235 | 365 | 57 | 3 | 5 | 922 | 1839 |

Przebieg 228 s miał union 363. Wobec pomiaru z union 362 przybyło wtedy jedno trafienie: `Foto(698)` czyta `4902030195397` z wycinka 1 obróconego o −1°. Najdłuższa klatka agresywna miała 1857 ms (`PICT00gfhh38`, `linear-band-mean`), a w Dataset2 989 ms (`Foto(515)`, wtedy jeszcze pusto).

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 915 | 1857 |
| Dataset2 | 215 | 22 | 46 | 154 | 59 | 2 | 0 | 266 | 989 |
| razem | 430 | 205 | 235 | 363 | 59 | 3 | 5 | 915 | 1857 |

Przebieg 239 s miał union 362. Wobec pomiaru z union 359 przybyły wtedy trzy trafienia, wszystkie na `linear-band-us4-rot`. Miękki wycinek jest wyostrzany promieniem 3. `Foto(516)` czyta `5030930017491`. `Foto(837)` i `Foto(842)` czytają `7321925005431`. Najdłuższa klatka agresywna miała wtedy 1988 ms (`PICT0036`, `linear-band`), a w Dataset2 946 ms (`Foto(515)`, pusto). FAST najdłużej 1075 ms (`PICT00daw23`).

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 1075 | 1988 |
| Dataset2 | 215 | 22 | 46 | 153 | 60 | 2 | 0 | 318 | 946 |
| razem | 430 | 205 | 235 | 362 | 60 | 3 | 5 | 1075 | 1988 |

Przebieg 227 s miał union 359. Wobec pomiaru z union 353 przybyło wtedy sześć trafień, wszystkie z Dataset2 i wszystkie na `linear-band-us4-rot`. `Foto(489)`, `Foto(675)`, `Foto(678)` i `Foto(722)` czytają `4902030187590`. `Foto(683)` czyta `8011642115962`. `Foto(767)` czyta UPC-A `051122414831`. Nic nie zniknęło i nie było nowego `fp`. `Foto(799)` i `Foto(848)` zostają na `scale-0.5`. `Foto(849)` zostaje dozwolonym odczytem. Najdłuższa klatka agresywna miała 1813 ms (`PICT00gfhh38`, `linear-band-mean`), a w Dataset2 872 ms (`Foto(515)`, pusto). FAST najdłużej 918 ms, też `PICT00gfhh38` (klasa `empty`).

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 918 | 1813 |
| Dataset2 | 215 | 22 | 46 | 150 | 63 | 2 | 0 | 282 | 872 |
| razem | 430 | 205 | 235 | 359 | 63 | 3 | 5 | 918 | 1813 |

Przebieg 225 s miał union 353. Wobec pomiaru z union 344 przybyło wtedy dziewięć trafień i jeden dozwolony odczyt. Nic nie zniknęło i nie było nowego `fp`. `Foto(427)` (`8002330002534`), `Foto(677)` (`4902030187590`), `Foto(846)` i `Foto(851)` (oba `7321925005431`) oraz UPC-A `051122414831` na `Foto(763)`, `Foto(766)`, `Foto(769)`, `Foto(770)` i `Foto(772)` schodzą na `linear-band-us4-rot`. `Foto(849)` ma pustą etykietę; ten sam obrót czyta wydrukowane `7321925005431`. `Foto(799)` i `Foto(848)` zostają na `scale-0.5`. Najdłuższa klatka agresywna miała 1840 ms (`PICT00gfhh38`, `linear-band-mean`), a w Dataset2 862 ms (`Foto(515)`, pusto).

| zbiór | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 886 | 1840 |
| Dataset2 | 215 | 22 | 46 | 144 | 69 | 2 | 0 | 271 | 862 |
| razem | 430 | 205 | 235 | 353 | 69 | 3 | 5 | 886 | 1840 |

Przebieg 230 s miał union 344. Wobec pomiaru z union 340 przybyły wtedy cztery trafienia. `05102009191` (`8002330112752`) schodzi na `linear-band-mean`: wyższy wycinek ma ten tekst na dwóch wierszach wolnych od odblasku. `Foto(480)`, `Foto(676)` i `Foto(829)` schodzą na `linear-band-us4-otsu` (GTIN `8011642111896`, `4902030187590` i znowu `8011642111896`). `Foto(741)` zostaje przy `8011642115887`, ale wygrywa wcześniej, na `linear-band-otsu` zamiast `linear-band`. Tekst baseline i FAST był ten sam co w przebiegu 368 s. Czasy były krótsze, bo próby po `original-global` i cztery kąty agresywne liczą się równolegle, a mediana 3×3 nie zaciska już środka okna. Najdłuższa klatka agresywna miała wtedy 1784 ms, a w Dataset2 886 ms.

Przebieg 368 s miał union 340. Wobec pomiaru z union 332 przybyło wtedy osiem trafień, wszystkie z Dataset2: UPC-A `051122414831` na `Foto(765)`, `Foto(776)`, `Foto(778)` i `Foto(779)` (`linear-band-level-top`), `Foto(483)` (`linear-band-level-top40`, `8011642115962`), `Foto(406)` (mediana kolumn, `8011642115887`), `Foto(738)` (najciemniejszy piksel kolumny, ten sam GTIN) oraz `Foto(504)` (wyostrzenie promieniem 2 średniej ostrych wierszy, `8002330097875`). Zostają też: `Foto(481)` i `Foto(728)` na 35. percentylu, `Foto(573)` na 45., `Foto(556)` na 50. percentylu, `Foto(501)` na wyostrzeniu tej średniej, `Foto(817)` i `Foto(820)` na lokalnej średniej, `Foto(503)` i `Foto(543)` na wyostrzeniu wycinka, `Foto(707)` na 2× oraz `Foto(508)`, `Foto(725)` i `Foto(729)` na 35. percentylu. Ten sam GTIN `8011642111896` schodzi też wcześniej na `Foto(816)`, `Foto(818)` i `Foto(819)`. `Foto(724)` zostaje przy tym samym GTIN na `linear-band-unsharp-otsu`. Szukanie kąta buduje tabelę przebiegów raz i rankuje ją osobno gęstością oraz rytmem. Kąty są liczone równolegle i składane w kolejności kąta, więc remis zostaje przy wcześniejszym. Na wszystkich 430 klatkach wycinki są identyczne. Samo szukanie, bez wczytywania JPEG, spadło z 289 s do 52 s. Wcześniejsze złożenie dwóch rankingów w jeden przebieg zeszło z 408 s do 312 s.
