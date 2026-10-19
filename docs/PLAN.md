# barcode-pro — specification

A pure Java library for reading barcodes. The read algorithms are ported from [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) 3.1.1 (Apache 2.0) to plain Java bytecode. The C++ library is not a dependency: no JNI, no native OpenCV, no zint and no other platform binaries.

This document is the plan and the implementation contract. The first version said that Java ZXing stays the engine for good and that an own bit decoder is out of scope; that was wrong. BoofCV left the build together with the transitive Trove: thresholds, morphology and detectors are our own. Of ZXing only `core` remains: the hybrid and global binarizers, `BitMatrix`/`BitArray`, Reed-Solomon and PDF417 error correction. Every symbology in `Symbology` has its own decoder, so `MultiFormatReader` is no longer called. `javase` is only in the tests (baseline). A further piece of this scaffolding goes when its replacement reads the same set and adds no false reads.

On hard images the library must be clearly better than the standard Java ZXing call. Matching commercial libraries (Dynamsoft, Aspose) on dirty production photos is not a guaranteed outcome of the port: on published photo sets zxing-cpp itself stays below those SDKs. The run time of the C++ binary is not a criterion. Java keeps the algorithm, without SIMD and without manual memory management.

## Direction of the port

The source of behaviour is the read path of zxing-cpp 3.1.1. The first file ported from that tree brings in `NOTICE` with the notice of the ZXing and zxing-cpp authors. The comment above a class names the source file and the tag. We port the behaviour and the structure of the algorithm, not the C++20 syntax.

Order:

1. Detectors. This is where Java ZXing 3.5.4 falls behind, and BoofCV 1.5 has no Data Matrix. First: Data Matrix (large and uneven symbols, internal alignment patterns), the QR finder (version 1, round patterns, many small symbols in one image), Aztec by its internal timing patterns, Micro QR.
2. Binarization. `LocalAverage` with symmetric threshold interpolation, and attempts matching `tryInvert`, `tryRotate` (90/180/270) and `tryDownscale`. Rotating a 1D code by an angle that is not a multiple of 90° stays a layer in front of the engine: zxing-cpp also reads a linear code only when a horizontal or vertical pixel row crosses the whole symbol.
3. Bit decoders, symbology by symbology. Priority: QR Code, Data Matrix, Code 128, PDF417, EAN/UPC, Code 39. Until the decoder of a symbology passes the round-trip test and the set, its reading stays with Java ZXing.
4. Scaffolding removal. BoofCV and the transitive Trove (LGPL 2.1) go when thresholds, morphology and detectors are ours and `mvn test` stays green. `zxing-core` then still remained for the binarizers, Reed-Solomon and PDF417 correction; it went later (step 5).
5. ZXing removal. The thresholds of `GlobalHistogramBinarizer` and `HybridBinarizer` and Reed-Solomon (GF(2^n) and GF(929) for PDF417) are ported from ZXing 3.5.4 bit for bit, and `zxing-core` is only in the tests (baseline and writers for synthetic images). The binarizer returns a row as `boolean[]` and the matrix as one `boolean` per pixel, like `BitMatrix` in zxing-cpp, so unpacking `BitArray`/`BitMatrix` 32 bits at a time on every attempt is gone. Every step gave an empty Snapshot diff against the run before the change.
6. Writing QR. `QrCode.encode` (public API next to `BarcodeReader`) is a port of the ZXing 3.5.4 encoder in the `qr` package: one segment in the most compact mode, ECI for a character set other than ISO-8859-1 (by default UTF-8 with ECI 26 when the text does not fit in ISO-8859-1), FNC1 for GS1, the smallest version and the mask with the lowest penalty, plus an image and SVG with a four-module quiet zone. The tests compare every module with ZXing's `Encoder` for the same choices and read the result with both readers. The version table `qr.QrVersion` is shared with the QR detector.
7. Reed-Solomon with erasures. All decoders (QR, Micro QR, Data Matrix, Aztec, MaxiCode, PDF417) correct through a port of `librscpp` from zxing-cpp 3.1.1: syndromes, erasure locator, the Sugiyama algorithm, the Forney formula and a second syndrome check after correction. ZXing computed the erasure locator but did not use it, so PDF417 with missing codewords repaired at most numEC/2 of them; now 2t + e ≤ numEC, as in zxing-cpp, with the limit e ≤ numEC − 2. The final syndrome check rejects wrong corrections that ZXing accepted: on `aztec-1/rune-64.png` the false second read "192" is gone.
8. One row for all linear readers. A FAST profile on Arte-Lab showed that every scanner (EAN/UPC, Code 39, Code 128 and the rest) binarized the same rows again and again computed their run lengths, forwards and backwards: `OneDRow.runs` had 8% of self time, `getBlackRow` 7%. `BinaryBitmap` now keeps the row and its runs for the whole attempt, just as `ODReader` in zxing-cpp builds the pattern row once for all readers; the runs of a row read from the right are the runs from the left in reverse order. `BlackMatrix` keeps one array per row, which PDF417 and DataBar take without copying. Reads unchanged, FAST 7–11% less CPU. Read time is measured by `mvn -q -Pspeed test-compile exec:java -Dexec.args="datasets/artelab-medium-barcode-1d"`.

9. Writing every symbology. `BarcodeWriter.encode(BarcodeType, content, WriteOptions)` writes every symbology of the ZXing 3.5.4 and Barcode-Lib4J 3.2.0 writers: QR (also GS1), Data Matrix (also GS1, square or rectangle), PDF417 (also truncated), Aztec (compact and full), Code 128 (A, B, C and automatic), GS1-128, EAN-14, SSCC-18, Code 39 (with extended and the mod 43 check character), PZN, PZN8, Code 93 (with extended), Codabar, Code 11, ITF, EAN-13, EAN-8, UPC-A, UPC-E with 2- and 5-digit add-ons, ISBN-13 and ISMN. The 2D and linear encoders are ports of ZXing in the `encode` package, and Code 11, PZN, ISBN, ISMN, EAN-14, SSCC-18, add-ons, quiet zones, GS1 validation and GS1 Data Matrix outside the minimal encoder are a port of Barcode-Lib4J. The tests compare module by module with ZXing and Barcode-Lib4J (the private fields of `TwoDSymbol` through reflection) and read the result with our reader. Two bugs of ZXing's minimal Data Matrix encoder are fixed: the rectangle capacity table has 33 instead of 22 for 12×36, so ZXing throws for 17–33 codewords in a rectangle, and EDIFACT at the end of the data without an unlatch makes the padding read as text (252 of 15,000 symbols of random texts). Defaults: PDF417 level 2 and a row of 3 modules (ZXing draws 4), Aztec 23% correction as ISO/IEC 24778 recommends (ZXing 33%), quiet zones as in Barcode-Lib4J. CLI: `write --type=...`.

10. Human-readable text and PDF. A drawing (`Drawing`) is bar rectangles and glyph outlines in modules; image, SVG and PDF fill the same shapes, so the text looks the same everywhere and the PDF embeds no font. The text layout is a port of `Barcode.draw` and `UPCEANFamily.draw` from Barcode-Lib4J: a plain code has one line centred under the bars (10 modules, less when it does not fit), EAN and UPC have digit groups of 7 modules per digit (step 0.1 as in Barcode-Lib4J), guard bars 5 modules longer, the add-on digits above its shortened bars, ISBN/ISMN above the code. `toPdf(mm)` writes one PDF 1.4 page with the dimensions of the drawing, without a library: `re` for bars, paths with cubic curves for glyphs, a Deflate stream. The test renders the PDF with PDFBox at 600 dpi and reads the code with our reader. The font comes from AWT; a headless JDK without fonts draws no text, `humanReadable(false)` leaves it out. `draw(Graphics2D, x, y, moduleSize)` draws the same on any `Graphics2D` (a PDFBox/OpenPDF page, printing): only `fill`, one rectangle per bar and the text outlines, without changing the transform, clip or hints; the test compares pixels with `toImage` and passes the bars through `BarExporter` from Barcode-Lib4J, whose `Graphics2D` knows only rectangles.

11. Data Matrix alignment patterns. As at the end of `Scan` in zxing-cpp's `DMDetector.cpp`, every corner of a traced symbol and every crossing of the alignment patterns between its data regions is looked for with `LocalGrid.findPattern` around its flat `mod2Pix` estimate, and each region between four of them is sampled with its own transform (`SampleGrid` over ROIs). A crossing that is not found keeps the flat projection, so a flat symbol samples as before. Large symbols with 2×2 data regions on labels that do not lie flat now read: seven more Data Matrix in the zxing-cpp samples in both modes (DigiKey labels `[)>␞06…`, the 2D-Doc `DC04FR…`), none lost and no new wrong text. The corners move by a pixel or two on most Data Matrix, because they now come from the found corner patterns. On BarBeR and KAIST the new reads are real symbols the sets do not label.

12. PDF417 without its left guards. zxing-cpp's detector skips an image in which no row has a start pattern, and looks for the stop pattern only after one. When that finds no symbol, `Pdf417Detector` now makes a second pass over stop patterns alone, as ZXing's `Detector.findVertices` does, so a symbol whose start pattern and left row indicator are cut off or damaged is read from its right row indicator. ZXing's `pdf417-3/13` to `15` now read in both modes (blackbox 689 → 692 in FAST, 701 → 704 in AGGRESSIVE), and on BarBeR two boarding passes and ID cards that were not read before; the zxing-cpp samples and every other set give an empty `diff`, in 167 s instead of 170 s. On two BarBeR photos with several codes the PDF417, found earlier now, takes the place of the labelled linear code in the modes that return one symbol; its text is right, but BarBeR labels it in code page 437 or in hex.

13. Code 128 edges in quarter pixels. On BarBeR's ParcelBar photos most Code 128 symbols have modules of 1.1 to 2.2 pixels. A row thresholded pixel by pixel puts every edge on a pixel boundary, up to a third of a module or more off, and the edge-to-edge widths round to another symbol; rectified crops of the labelled codes read 425 of 1,110 at their own size and 717 at twice it. When no row reads, `Code128Scanner` now scans the rows again with `BinaryBitmap.fineRuns`: the same sharpened row and black point, but each edge where the row, linearly interpolated between pixel centres, crosses the black point, in quarters of a pixel. A row that read before reads the same. BarBeR in FAST 3,183 → 3,453, AGGRESSIVE 3,509 → 3,768, FAST with `findAll` 3,445 → 3,750 (ParcelBar 283 → 532 and 368 → 612, InventBar 442 → 462); every other set gives the same tables, KAIST's photos without a barcode included, in the same time. The new texts that BarBeR does not match are real symbols it leaves unlabelled (`^`) or does not mark at all, such as a second Code 128 on a parcel label.

14. Code 39 and EAN/UPC edges in quarter pixels. The second scan of item 13 now also runs in `Code39Scanner` and `UpcEanScanner`, through `BinaryBitmap.runs(y, reversed, OneDRow.FINE)`, which keeps the fine rows of a bitmap as it keeps the whole-pixel ones. An EAN hit from the fine rows is confirmed on fine rows too: the parallel strip, the other rows of the bitmap, `widerRival` and `separatedEanRows` read at the scale of the hit. Rectified BarBeR crops had shown the gain only for modules of one to one and a half pixels, +105 EAN and +15 Code 39 at twice the size. All of BarBeR's labelled EAN/UPC in FAST 4,109 → 4,296 of 5,365, Code 39 127 → 145 of 198. In the snapshot BarBeR without its Arte-Lab and KAIST parts goes FAST 3,453 → 3,488, AGGRESSIVE 3,768 → 3,792, FAST with `findAll` 3,750 → 3,794 (InventBar 462 → 485, Open Food Facts 110 → 114); the KAIST test split 1,437 → 1,574, 1,576 → 1,695 and 1,459 → 1,596 with fewer wrong texts (38 → 35, 47 → 40, 18 → 13); Arte-Lab 243 → 249 and 378 → 389; the ZXing blackbox 692 → 705 and 704 → 714, most of it UPC-A; the zxing-cpp samples 485 → 486. KAIST's photos without a barcode still give 0, 1 and 0. Of the new texts that match no label one is a misread, 8502684222652 for KAIST's 3256221386707 on a 90° turn, and two more come with `findAll` (Arte-Lab `Foto(745)`, KAIST `7610058216310`); blackbox `ean13-2/21` is the Japanese price code next to the ISBN. The whole snapshot took 4,858 s instead of 4,445; the Arte-Lab speed run gives the same time a photo in both modes.

15. A coarse band search in fast mode. Most labelled codes that only aggressive mode read came from `linear-band` crops: 186 EAN-13 and 100 Code 128 on BarBeR. Fast mode now ends a miss with `LinearBand.find` on a ~280 px view every 6°, refined to ±3°, about a third of the full search, and reads each crop as it is and stretched. A hit never gets there. The full `readBand` with Otsu and local mean read 69 more of BarBeR's labels but returned seven wrong EAN texts, and the full search took as long on a photo without a code as aggressive mode, so both stay aggressive. BarBeR without its Arte-Lab and KAIST parts goes FAST 3,488 → 3,603 (ParcelBar 533 → 575, SKUU 155 → 178, Muenster 902 → 923, ZVZ 423 → 437), FAST with `findAll` 3,794 → 3,991; Arte-Lab 249 → 282, KAIST's test split 1,574 → 1,626 and 1,596 → 1,652, its EAN-8 80 → 81; ZXing's blackbox, the zxing-cpp samples and KAIST's photos without a barcode do not change. With `findAll` the crops also cut EAN-13 symbols that were already read in half, and the six L- and G-coded digits of a left half read as a UPC-E (`10004924` from `4000492512476`, ten times on Muenster); `HitCollector` now drops such a UPC-E next to its EAN-13 or UPC-A, and wrong `findAll` texts on BarBeR go 126 → 130 instead of 141. On this 20-thread desktop the Arte-Lab speed run goes from 0.079 to 0.103 s a photo in fast mode (0.18 → 0.26 s of CPU), a photo without a code from 0.066 to 0.131 s (aggressive: 0.46 s), and the whole snapshot from 4,858 to 5,619 s. The synthetic Code 128 at 30° and 45° now read in fast mode too.
16. Downscaled photos and thresholded band crops in fast mode. On large photos a symbol blurred over several pixels reads once a box average has shrunk the blur: the ladder now also reads 2× (shorter side from 1,000 px) and 4× (from 1,400 px) box downscales, in both modes, as `downscale-2` and `downscale-4`. When no crop of the coarse band search of item 15 reads, fast mode thresholds each crop with Otsu and with the local mean for EAN-13, UPC-A, Code 128 and Code 39; with UPC-E and EAN-8 these reads had returned six wrong texts. The thresholded crops run only on an image nothing has read: with `findAll`, beside a symbol already read, the mean threshold returned 60 EAN-13 with that symbol's right half and a wrong left one, and wrong `findAll` texts on BarBeR went 130 → 161; read only after a miss they go 130 → 136. BarBeR without its Arte-Lab and KAIST parts goes FAST 3,603 → 3,698 (ZVZ 437 → 475, Muenster 923 → 957, SKUU 178 → 184), AGGRESSIVE 3,792 → 3,801 with 122 wrong texts instead of 126, FAST with `findAll` 3,991 → 4,091; Arte-Lab 282 → 289; KAIST's test split 1,626 → 1,671, 1,695 → 1,696 and 1,652 → 1,697, its EAN-8 81 → 82; BoofCV's QR codes 410 → 421, 429 → 431 and 1,005 → 1,024; ZXing's blackbox 705 → 707 (two PDF417). The zxing-cpp samples do not change, and KAIST's photos without a barcode still give 0, 1 and 0. On this desktop the Arte-Lab speed run goes from 0.103 to 0.116 s a photo in fast mode (0.26 → 0.29 s of CPU) and from 0.20 to 0.22 s in aggressive mode, a photo without a code from 0.131 to 0.153 s in fast mode and 0.46 to 0.48 s in aggressive mode; the whole snapshot took 5,556 s.
17. Code 128 fitted to the gray profile. Under about 1.3 pixels a module, a one-module bar of a ParcelBar code never crosses a threshold cleanly, and neither the row scan of item 13 nor a thresholded crop reads it; the gray profile across the bars still matches the symbol's patterns blurred. `BarStrips` finds every patch of parallel bars from the structure tensor of 16-pixel blocks and samples it from the original with the bars vertical; `LinearBand` settles on the printed text of a parcel label at the angles it tries. `Code128Fit` averages the strip's rows into a profile in quarter pixels, takes the outer edges of the symbol from it, screens the number of symbols, the blur and a quarter pixel at each end on the start and stop patterns, and fits the best 16: every position gets the least-squares residual of every pattern, and a search over the weighted sum modulo 103 picks the cheapest sequence with a right check symbol. A read counts when the check changed no symbol, the mean residual is under 0.2, and every symbol fits at least 0.05 better than its runner-up; without the last test texture and text lines passed as short codes. It is the last step of a read that found nothing, in both modes: it decodes no image variant, so it takes no attempt from the ladder, whose band reads use up all forty on these photos, and only the time budget stops it. ParcelBar goes FAST 578 → 628, AGGRESSIVE 613 → 658, FAST with `findAll` 688 → 743, InventBar 495 → 497, 492 → 494 and 516 → 518; BarBeR without its Arte-Lab and KAIST parts FAST 3,698 → 3,750, AGGRESSIVE 3,801 → 3,848, FAST with `findAll` 4,091 → 4,148. Every other set gives the same tables, KAIST's photos without a barcode still 0, 1 and 0. Of the three new texts BarBeR does not match, two are order numbers on the label that it leaves unlabelled (`754282`, `228104`) and one is wrong (`EX550064373TH` for `EX550064323TH`); with `findAll` one more order number. On this desktop the Arte-Lab speed run goes from 0.124 to 0.135 s a photo in fast mode (0.29 → 0.30 s of CPU) and stays at 0.21 s in aggressive mode, a photo without a code from 0.154 to 0.179 s in fast mode and 0.467 to 0.479 s in aggressive mode; the whole snapshot took 5,531 s.
18. Faster rotations and band ranking, with the same results. A profile of the Arte-Lab speed run in both modes put 13% of the samples in `Geometry.paint` and 11% in `LinearBand.spacingCv`. Along a destination row the source point of a rotation moves monotonically, so the pixels inside the source form one run: `paint` now finds it per row, fills the rest white and samples the run without bounds checks, with the same formula, so every pixel is the same (13,539 rotations of random images and photos at quarter degrees and edge angles hash the same); a rotation takes about two thirds of the time. `spacingCv` ran for every run of hot columns and now runs only for a run that passed the cheaper tests of `scoreRun`. Every row of the snapshot is the same. Measured alternately on this desktop, fast mode does not change on Arte-Lab (0.28 s of CPU a photo) and goes from 0.485 to 0.459 s of CPU on a photo without a code; aggressive mode goes from 0.94 to 0.86 s of CPU on Arte-Lab and from 2.16 to 1.93 s on a photo without a code, 0.47 → 0.45 s a photo. The Vector API would not help a library here: it is still incubating in JDK 25, needs `--add-modules` from every user, and the hot loops are gathers and data-dependent branches; nor would virtual threads, as the work never blocks.
19. The patches of bars in fast mode. `BarStrips` of item 17 finds every patch of parallel bars at the angle of its own gradients, where the band search of item 15 tries a few angles and ranks bands of edges, so it cuts a symbol between its angles, or beside a block of printed text, badly or not at all. After a miss fast mode now reads the four strips with the most gradient energy with the linear readers before the band search; each is an attempt. With all eight strips the thresholded band crops at the end lost 20 of BarBeR's codes to the forty attempts, with four 5, for one code fewer. A strip is a resampled crop like a band crop, so an EAN read from it gets the same confirmation (another row of the same bitmap confirms nothing, and a wider rival wins); with the looser rule of a full photo two wrong EAN-13 came back. BarBeR without its Arte-Lab and KAIST parts goes FAST 3,750 → 3,787 (ParcelBar 628 → 656, SKUU 184 → 192), FAST with `findAll` 4,148 → 4,253 (ParcelBar 743 → 821, ZVZ 595 → 612); KAIST's test split 1,671 → 1,686 and 1,697 → 1,714, its EAN-8 82 → 83, Arte-Lab 289 → 291. Aggressive mode does not change: the strips before its band search read 16 more of BarBeR's codes and 14 of KAIST's but pushed 14 Arte-Lab photos past the hundredth attempt, where steps tuned on those photos read them, and at the end of the ladder they read almost nothing, so it waits until those steps are revisited. KAIST's photos without a barcode still give 0, 1 and 0. Of the new texts BarBeR does not match in fast mode, one is an EAN-13 labelled `-1` and one a parcel's order number; with `findAll` three are EAN-13 with one half wrong, such as `2064261037234` for `4000007037234`, and Open Food Facts loses two codes that the mean threshold of the band crops read on the last attempts. Measured alternately on this desktop, speed does not change: fast mode takes 0.126 s a photo on Arte-Lab where it took 0.138 s, and 0.158 s on a photo without a code where it took 0.160 s; the whole snapshot took 5,664 s.
20. EAN-13 and UPC-A fitted to the gray profile. `EanFit` reads the strips of `BarStrips` as `Code128Fit` of item 17 does, with the profile code both share moved to `GrayProfile`. The symbol always spans 95 modules, so the outer edges, where the profile drops 30% of the way to its darkest, give the module. The true edge can lie a module and a half either side, so each end is screened on its own, on its guard and the cheapest digit beside it, a quarter pixel at a time; the four best of each end are tried together with the middle guard, and the twelve best geometries at blurs of 0.6 to 1.2 modules get the full fit: every digit position gets the residual of each of its L, G or R patterns, and a search over the parity of the left half and the weighted sum modulo 10 finds the cheapest digits with a right check digit, reading both ways. A read counts when its mean residual is under 0.28, the cheapest other digits with a right check cost at least 0.18 more, and the same digits fit on a second of three bands of rows: on one band texture and printed text passed as EAN-13, on two of them none did. EAN-8 is not fitted, as every EAN-8 the fit returned was false. It runs after the Code 128 fit, in both modes, only on an image nothing has read. KAIST's test split goes FAST 1,686 → 1,710, AGGRESSIVE 1,696 → 1,719, FAST with `findAll` 1,714 → 1,740, its `rec` split, which was not looked at while tuning, 1,021 → 1,029; BarBeR's KAIST part 2,588 → 2,608. Of BarBeR's new texts it does not match, fifteen are EAN-13 on photos it labels `-1`, each the code in the file name, and one the fourth EAN-13 of a photo with four, which it leaves unlabelled. Arte-Lab, the zxing-cpp samples and KAIST's photos without a barcode do not change. Using the fit to veto threshold reads instead, to cut EAN-13 with one wrong half, rejected more right reads than wrong ones (30 to 7). A third band is fitted only when one of the first two fitted something, as it has nothing else to confirm; the 218 reads of the fit in the snapshot stay the same. Measured alternately on 12 of the desktop's 24 threads, items 20 to 22 together take fast mode on Arte-Lab from 0.114 to 0.128 s a photo (0.22 → 0.24 s of CPU) and on a photo without a code from 0.147 to 0.158 s (0.36 → 0.37 s of CPU), nearly all of it the fit; aggressive mode goes from 0.157 to 0.161 s and from 0.38 to 0.39 s.
21. QR on a bent grid. On a can or a bottle a QR code bends, and a 45° symbol on a cylinder bends along its diagonal, so neither the homography nor a correction of rows and columns from the timing patterns alone fits it. When every regular grid of a candidate fails, `QrDetector.sampleRefined` starts a field of offsets from the timing patterns and in three rounds walks every row and column of modules, moves every module edge it crosses to the nearest edge in the image, and fits a cubic in (u, v) by least squares, dropping points more than a third of a module off. Each grid is checked by a full decode. A QR wrapped on a cylinder of radius 145 pixels with 6-pixel modules, turned 15° to 60°, reads only with it (`QrBentTest`). BoofCV's QR codes go FAST 421 → 442 (curved 35 → 41, glare 27 → 35, damaged 10 → 14), AGGRESSIVE 431 → 455, FAST with `findAll` 1,024 → 1,048; ZXing's blackbox 707 → 715, 714 → 722 and 712 → 720; BarBeR's Szentandrasi-Dubska QR 84 → 86 and 195 → 199. The one new QR text BarBeR does not match is a receipt's QR on a ZVZ photo it labels `^`. On two KAIST photos and one BarBeR photo a real, unlabelled QR on the package now comes before the labelled EAN, so without `findAll` the EAN is lost and the QR counts as a wrong text.
22. Experimental symbologies, read only when asked for. `MAXICODE`, `TELEPEN`, `DX_FILM_EDGE`, `RMQR` and `MICRO_PDF417` are in `Symbology` but not in `defaults()`, and their javadoc says they are lightly tested. MaxiCode is read from the whole matrix of an attempt as a clean, upright symbol, as zxing-cpp reads it, without a detector for photos; DX film edge from the rows of the matrix, and Telepen from rows walked out from the middle, confirmed on a second row. These three report the corners of the whole image. rMQR is a port of zxing-cpp's version table, function patterns, codeword reader and `SampleRMQR`, which zxing-cpp marks as work in progress; the sub finder pattern is also searched at several points along the axis of the symbol, and a second geometry runs the long axis from the finder to the sub finder, each checked by a full decode. `QrDecoder` corrects and decodes rMQR's streams with their 3-bit modes and the character counts of ISO/IEC 23941. MicroPDF417 is a port of zxing-cpp's `MicroPDFReader` with its row address patterns, clusters and size table of ISO/IEC 24728; the codewords go through the PDF417 decoder. The zxing-cpp samples read: MaxiCode 8 of 8 and ZXing's blackbox 9 of 9, Telepen 4 of 4, rMQR 3 of 3, MicroPDF417 7 of 7, also at 35° and in perspective, and DX film edge 2 of 3, where `dxfilmedge-1/1.jpg` gives `36-2/20A` and zxing-cpp's test expects `36-2/21`. rMQR turned every 15° reads 24 of 24 at R17x139 and 21 of 24 at R7x43, which reads 24 of 24 at twice the scale. On 15,599 images without these symbols, KAIST, ZXing's blackbox, BoofCV, BarBeR, Arte-Lab and the other zxing-cpp samples, rMQR and MicroPDF417 read nothing in either mode, and the other three nothing in fast mode on 10,059.
23. BarBeR's texts as its annotation tool saved them. Of the reads BarBeR did not match, 21 binary PDF417 payloads, 12 Aztec and Data Matrix payloads and some texts with line breaks were the labelled codes: the tool broke lines where the symbol has none or has a carriage return, and wrote binary payloads with NUL as `^`, as the hex of their UTF-16 code units, in ISO-8859-1 for Aztec and Data Matrix and in code page 437 for PDF417, or as code page 437 text (`PD4Nalog|CP1251|…` in Cyrillic bytes). `Labels.sameBarberText` accepts these forms; no read is changed. ZVZ goes ZXing 300 → 321, FAST 480 → 512 with 44 wrong texts instead of 76, AGGRESSIVE 494 → 526, FAST with `findAll` 614 → 652, so BarBeR without its Arte-Lab and KAIST parts goes 2,463 → 2,484, 3,797 → 3,829, 3,859 → 3,891 and 4,265 → 4,303. ZXing gains less as it prints Aztec bytes differently. The scorer change was checked with `--rescore` on the same TSV.
24. Royal Mail and Japan Post. BarBeR's ZVZ part has 199 RM4SCC codes (seven of them labelled KIX, but printed with the Royal Mail start, stop and check) and 25 Japan Post codes, which no reader here decoded. `FourStateReader` reads both from the matrix of every attempt when they are asked for, as `ROYAL_MAIL` and `JAPAN_POST`. A row through the tracker meets every bar of a symbol as a run of the same width at the same pitch; at least 14 such runs make a candidate, and each is followed up and down, row by row, so a lean of a few degrees still gives its top and bottom. The tops split into two lines, of the bars that ascend and of those that do not, by Otsu's split of the residuals to one line and then two least-squares lines refitted three times, and the bottoms the same way; a bar more than a third of the separation off its line rejects the candidate. An end bar that touches a dark background merges into it on the tracker row; when a wide run starts where the next bar would be, one bar width of it is taken, kept to its own columns, and read as any state within what was measured, as the background can only lengthen it. RM4SCC needs an ascender to start, a full bar to stop, two ascenders and two descenders in every four bars, and its check character, which the text keeps as BarBeR does; Japan Post needs 67 bars, its start and stop and its check modulo 19, and its control codes become letters and its padding goes. Both are tried the other way round too. They read 192 of the 199 Royal Mail codes and 13 of the 17 labelled Japan Post codes, and Japan Post codes with a right check on all seven photos BarBeR labels `^`; the misses are bars that run into a textured background along their whole ascender and pale yellow labels. One text differs from BarBeR's, `0223456ABCG` labelled `223456ABCG`, whose check fits the leading zero. On 16,400 images, every set here, they read nothing else in either mode. The postal types stay out of BarBeR's counts, as the snapshot reads the default symbologies.

## State of the scaffolding

Date: 2026-10-12, the same day as the measurement in [Measurement](#measurement). The repository started from a Maven skeleton (`org.krzywanski:barcode-pro`).

| Phase | Scope | State in the code |
| --- | --- | --- |
| 0 | Set of hard codes, ZXing baseline, metrics | Synthetic generator and benchmark in the tests. The numbers of the last run are in [Measurement](#measurement). |
| 1 | Preprocessing and multi-stage attempts | Implemented. `FAST` and `AGGRESSIVE` modes. Thresholds and morphology are our own. |
| 2 | Region location, crop, deskew | Own QR, Micro QR, Aztec and Data Matrix finders. Broken outline: six dilations and the `blob-quad` attempt. A small 2D symbol that earlier attempts miss comes from a tile. A tilted 1D code that the ±15° and ±30° rotations miss comes from the `linear-band` strip. |
| 3 | Special paths, glare, multiple codes | Partly: a separate QR/Aztec/Micro QR path, illumination normalization, `findAll`, two modes. |
| 4 | Stabilization and metadata | Partly: a result carries text, symbology, corners, orientation, a heuristic confidence and the attempt name. Optimization for large images (pixel limit, time budget) is preliminary. |

Symbology priority: QR Code, Data Matrix, Code 128, PDF417, EAN/UPC, Code 39. In addition Aztec and Micro QR. Micro QR has no format in ZXing; the ported decoder reads it.

## Out of scope

- JNI, JavaCV, native OpenCV, libdmtx, zint and binding zxing-cpp as a C++ library.
- The zint writer from zxing-cpp 3.x, EPS and embedding fonts in PDF.
- A guarantee of commercial SDK quality on random production photos.
- Speed parity with the C++ binary.

## Stack

Java 17+ and the code ported from zxing-cpp remain.

- Java 17+ (bytecode of this module: `--release 17`; the project compiles with JDK 21).
- No runtime dependency. ZXing `core` and `javase` 3.5.4 (Apache 2.0) only in the tests: baseline, luminance from `BufferedImageLuminanceSource` and writers for synthetic images. The binarizers and Reed-Solomon are ported from ZXing (packages `image` and `ecc`, see `NOTICE`).
- `module-info`: the module `io.github.kamilkrzywanski.barcode` exports only the API package.
- Java 2D: `BufferedImage` as the public input.
- JUnit 5 only in the tests.

`boofcv-ip`, `boofcv-recognition` and the transitive Trove are not in `pom.xml`. The `BufferedImage` → grayscale conversion, thresholds, morphology and detectors are our own.

## Input and output

```
BufferedImage
      ↓
BarcodeReader.read(image, ReadOptions)
      ↓
1. Cheap ladder
   original-hybrid, original-global, inverted-luma, 4 px margin,
   contrast stretch, local illumination normalization,
   rotations 90/180/270, adaptive mean, Otsu, 2× scale
      ↓
2. Finders
   qr-trace, mqr-trace, aztec-trace, dm-trace
      ↓
3. AGGRESSIVE only
   rotations ±15° and ±30°, when the rotated rectangle fits in 13 Mpx,
   up to nine linear strips (hybrid, stretch, Otsu, mean,
   then a tighter window: hybrid and Otsu),
   pyramid, 3×3 tile grid (2D symbologies only),
   Gaussian threshold, Sauvola, morphology, scales 1.5× and 0.5×,
   blob-quad, and when still empty or findAll: the same finders on inverted grayscale
      ↓
List of BarcodeHit
   text, symbology, corners in source pixels,
   orientation in degrees, confidence 0..1, attempt name
```

The order is deliberate. A clean code ends on the first attempt, at a cost close to plain ZXing. The more expensive stages start when the earlier ones found nothing, or when `findAll` asks to keep looking.

## API

Public package: `io.github.kamilkrzywanski.barcode`.

- `BarcodeReader` — stateless entry point. `read` can be called from many threads on the same object (every call has its own decoder state). `new BarcodeReader()` computes independent attempts in parallel on a shared pool of daemon threads (processors − 1). `new BarcodeReader(executor)` uses the user's pool. `BarcodeReader.sequential()` computes everything on the calling thread and stops at the first hit. The calling thread itself finishes every attempt the pool has not started yet, so a call from a thread of the same pool does not deadlock. All three give the same hits when the time budget does not interrupt the read.
- `read(byte[] luminance, width, height, options)` and the variant with `offset` and `rowStride` — 8-bit luminance, such as the Y plane of a camera. The array is copied. Both first attempts and the ladder read the same bytes.
- `ReadOptions.fast()` — the default. Few attempts, a budget of 2.5 s, 40 attempts.
- `ReadOptions.aggressive()` — the full schedule, a budget of 8 s, 100 attempts.
- `findAll(boolean)` — does not stop after the first hit. `false` by default.
- `symbologies(...)` — a subset. By default the priority list plus Aztec and Micro QR. Code 93, ITF, Codabar and `DATABAR`, `DATABAR_LIMITED` and `DATABAR_EXPANDED` only on request. DataBar corners are `EstimatePosition` from zxing-cpp: a line through the middle of the rows on which the pairs are visible, or the four corners of a stacked symbol. After a read the scanner keeps reading rows downwards while the same pairs are visible, so the outline reaches the last row of the bottom tier; zxing-cpp remembers only the first row of each pair.
- `eanAddOn(EanAddOn)` — the EAN-2/EAN-5 add-on to the right of EAN/UPC: `IGNORE` (default, as in zxing-cpp), `READ` or `REQUIRE`. The add-on comes back in `BarcodeHit.addOn()`, the text stays the main symbol. With `READ`, when the first row with the symbol does not cross the add-on, the scanner takes another row with the same symbol and the add-on. The EAN correlator does not see the add-on, so it does not start with `REQUIRE`.
- `BarcodeHit.bytes()` — the content before the character set is applied: for QR, Micro QR, Data Matrix, Aztec and PDF417 the data bytes without ECI markers (`Content::bytes` in zxing-cpp), so binary content comes back without loss; for linear symbols the characters of the text. `equals` compares the array contents.
- `timeBudgetMillis(Long.MAX_VALUE)` — no time limit. The read is then stopped only by the attempt budget and the result does not depend on the speed or load of the machine. The `-Partelab` tests read this way.
- `BarcodeHit` — one read.
- `Symbology`, `DecodeMode`, `ImagePoint`.

`confidence` is a heuristic of the attempt order (earlier, cleaner attempt → higher), not a calibrated probability. `orientationDegrees` are clockwise degrees on an image with the Y axis pointing down; `-1` means "unknown". `attempt` is diagnostic metadata (`original-hybrid`, `inverted-luma`, `illumination`, `qr-trace`, `blob-quad`, `sauvola`, …) and may change between versions.

False 1D reads are limited in three ways. Morphological variants decode only 2D symbologies. A 1D read without two points at least 30 px apart is rejected. A 1D read must repeat on a parallel strip (8 px thick, 4 px from the line ZXing accepted, with a 40 px margin for the quiet zone): the same symbology and the same content. A single line of slanted bars that by its checksum comes out as a short UPC-E is dropped. When the image has no room for such a strip, the hit stays. A full EAN-13 or UPC-A may instead of the strip repeat on any other row of the same bitmap (the `minLineCount = 2` rule from zxing-cpp), but not on a downscaled image (`scale-0.5`, `downscale-*`) nor on a strip crop: there neighbouring rows are nearly copies and on Arte-Lab they repeated wrong codes with a valid checksum, such as `6328925005431`. Short UPC-E and EAN-8 still require the strip.

On an attempt whose name starts with `linear-band`, EAN has two extra gates. A short run is dropped when the same bitmap carries another text with a span of at least 8 px and strictly larger than the hit's run. A run that itself has at least 8 px stays. A read from a binary threshold (Otsu or local mean) stays when the same text comes back on at least four rows or on rows at least 8 px apart. A 48 px high strip still requires this condition. A taller crop may stay with two rows: the glare on `05102009191` leaves only such a pair, and the local mean reads the label from it. Hybrid and contrast stretch do not get this second gate, because a correct single-row EAN must stay.

## Measurement baseline

"Standard ZXing" in the benchmark is exactly what a typical snippet from the documentation does:

- `MultiFormatReader.decode`
- `HybridBinarizer` + `BufferedImageLuminanceSource`
- the `TRY_HARDER` hint
- no `ALSO_INVERTED`, no pyramid, no rotations apart from the one 90° rotation `TRY_HARDER` does inside the 1D reader, no second code in the same image

`GenericMultipleBarcodeReader` is not part of the baseline. Reading multiple codes is a separate capability of the library (`findAll`).

Metrics:

- recall — how many expected contents were read, divided by the number of expected contents;
- false reads — reads whose content is not among the image's expectations (on images without a code every read is false);
- time — the wall time of `read` on one image.

The test images are synthetic and deterministic (damage drawn with Java 2D / a homography): clean codes, inverted QR, Data Matrix and Code 128, QR in perspective, a QR with error correction level H hitting the wrong block phase of HybridBinarizer, glare (a white oval with alpha 180), 2% noise, Data Matrix rotated by 25° and fitted into a quadrilateral, Code 128 rotated by 30° and by 45°, a small Data Matrix on a large label, stress (1 px module, blur, gradient), two QR codes, a QR next to Code 128, and negatives (blank, noise, gradient). This is the phase 0 set. The denominator of 22 stays. Real photos live outside `src/test/resources`, so that `mvn test` does not copy them to `target/`. Arte-Lab is in `datasets/artelab-medium-barcode-1d/`.

## Attempt schedule

### FAST

1. The original, `HybridBinarizer` on ZXing's luminance: `(306 R + 601 G + 117 B + 512) >> 10`, a fully transparent pixel as white, raw image samples for `BYTE_GRAY`. These bytes equal `BufferedImageLuminanceSource` (`GrayConversionTest`), and they are computed in the same pass as the ladder's grayscale. Attempt `original-hybrid`. The own matrix decoders and the priority linear ones read this bitmap. A white matrix symbol on black is `flipAll` of the same bitmap, still under this attempt name.
2. The same image, `GlobalHistogramBinarizer` (`original-global`).
3. Inverted luminance (`255 − value`), then `HybridBinarizer` (`inverted-luma`). `flipAll` inverts the already binarized matrix and does not read white Code 128 on a black background. That code wins here.
4. A 4 px white margin (`pad-4`). It shifts the 8×8 block phase of HybridBinarizer.
5. Contrast stretch (percentiles 1%–99%), only when the image changes.
6. Local illumination normalization (box mean).
7. Orthogonal rotations 90°, 180°, 270° (pixel rewrite, no interpolation). Only linear symbologies and PDF417: the QR, Micro QR, Aztec and Data Matrix finders do not depend on the angle, and on 1225 images from Arte-Lab and the zxing-cpp samples they read nothing on a rotation that the upright image did not take. The only matrix read on a rotation was a false Aztec in MaxiCode. The same applies to the ±15° and ±30° rotations in AGGRESSIVE.
8. Adaptive local mean threshold and Otsu. The binary image goes back to luminance and through `GlobalHistogramBinarizer`.
9. 2× scale, nearest neighbour, when the shorter side is ≤ 900 px and the result fits in 1,600,000 pixels. This is the scale limit. The ±15° and ±30° rotation has a separate limit and does not raise it.
10. Own finders: `qr-trace`, `mqr-trace`, `aztec-trace`, `dm-trace`. In `qr-trace`, when LocalAverage returns nothing, the same finder gets a 3×3 median and reads again, under the same attempt name. The middle of the median window is computed without repeating the edge pixel; the result is the same as sorting a clamped window. An Aztec of size at least 35 is sampled from a local grid inside `aztec-trace`. FAST returns here.

After `original-global`, when the set of symbologies is not empty, the attempts from inverted luminance to the 2× scale are computed together and accepted in the order of the list above. A hit on the hybrid or on the global threshold no longer builds those images. In AGGRESSIVE the four angles ±15° and ±30° that fit in the rotation limit are also computed together and accepted from −15° upwards. `stop()` before an attempt is counted discards a later result. The order of the winner stays the same. Without `findAll` these attempts do not look for Data Matrix; Data Matrix is read by `original-*` and `dm-trace` (see [Measuring changes](#measuring-changes)).

With `findAll` the loop does not stop at the first hit. Within one attempt our own read returns the symbols found on that bitmap. `GenericMultipleBarcodeReader` stays for a format that still goes to ZXing, on the hybrid original and on the orthogonal rotations.

### AGGRESSIVE

Everything from FAST, and then, before the more expensive thresholds start:

- rotations ±15° and ±30° with bilinear interpolation. The scale limit stays 1,600,000 pixels, so FAST does not double a 1152×864 frame. The rotation has a separate limit of 13,000,000 pixels of the rotated rectangle: 2976×2232 at 30° fits, the same frame at 45° no longer does. ±45° is not on the ladder.
- the linear strip, before the pyramid goes down. The finder gives at most nine crops and decodes only linear symbologies, so a crop does not assemble a false PDF417. On the full crop in turn: hybrid (`linear-band`), contrast stretch, Otsu, local mean. The tighter window repeats only hybrid and Otsu (`linear-band-tight`, `linear-band-otsu-tight`). Each such read uses one budget slot. After the regular steps, and only when they still miss and the whole photo has at most 1,000,000 pixels, fourteen attempts are added: a horizontal unsharp mask (radius 3, weight 3) on the first crop, percentile cuts at 35, 45 and 50 of averaged rows, a local mean of the sharpest rows and a short sharpening of that mean, the mean of those rows cut at the 50th and the 40th percentile, on the second crop a sharpening of that mean with radius 2 and weight 4, on the third the same mean plus the column median and the darkest column pixel, on the fourth a mask with radius 2 and weight 2.5 plus Otsu, and on the tighter window of the fifth a 2× nearest neighbour and Otsu, when 2× fits in 2,000,000 pixels. A photo above a million pixels skips these attempts: sharpening the whole crop on a 2976×2232 frame eats about three seconds. On a full miss at 1152×864 the 0.5× scale is the hundredth attempt and still runs. One extra attempt before it loses `Foto(799)` and `Foto(848)`.
- a pyramid with threshold 500 and factor 3;
- a 3×3 grid of tiles with a side of 55% of the image, only when the shorter side has at least 240 px. Only 2D symbologies are decoded. The tiles are for a small symbol that earlier attempts missed.

Only later:

- Sauvola (narrow radius, and when it differs from the wide one, the wide one too);
- 1.5× scale, when the result fits in the 1,600,000 pixel scale limit, and 0.5×, when the image has more pixels than 800×800 and the result fits too;
- the slot after the 0.5× scale. A Gaussian threshold on the full frame did not recover a label. When the strip search has already run, `findAll` is off and there are linear symbologies, this slot instead of Gauss sharpens the first crop with a mask of radius 4 and weight 4, then Otsu (`linear-band-us4-otsu`). Otherwise the Gaussian threshold stays. The angle grid has a 3° step, so a soft code can end up 2° off the crop. The following slots, while the attempt budget lasts, rotate the crop by −2° (`linear-band-us4-rot`). The first takes the widest soft crop, at least 400×100, with the lowest edge energy, and sharpens it with radius 3: radius 4 loses `Foto(837)` and `Foto(516)`, and radius 3 still reads `Foto(849)`, `Foto(846)` and `Foto(851)`. Further crops stay at radius 4. Then come crops number 1, 2, 3, 0 and 4, skipping the one already checked, still at −2°. When these miss and a slot remains, crop 1 goes once more at −1°: that is how `Foto(698)` reads. Crop 0 at −1° misreads `Foto(828)`, so that angle is not there. The same crop 0 at +2° reads `Foto(515)` and `Foto(824)`, and does not misread on the old misses. When that misses too, three angles follow, each without a false read on the old misses: crop 2 at +0.5° reads `Foto(823)`, crop 3 at +1° reads `Foto(828)`, and crop 3 at +2° reads `Foto(830)`. All three are `8011642111896`. When that misses too, three other masks stay at the end. Crop 2 rotated by −1° and cut by the median of the sharpest rows after sharpening reads `Foto(484)`. Crop 3 rotated by +1°, pulled from a trapezoid into a rectangle, then the same mask and Otsu, reads `Foto(496)`. Crop 0 rotated by −1° and sharpened on the mean of the sharpest rows with radius 2 reads UPC-A `Foto(761)`. The last slot, one for all wide crops, correlates the sharpest rows with EAN digit templates (`ean-correlate`): a blurred bar does not pass the threshold, but the module shape still matches one digit. That is how `Foto(312)` (`8011642115887`) and `Foto(827)` (`8011642111896`) and UPC-A `Foto(760)` and `Foto(762)` read. The end guard is not required, because on a tight crop it merges with the dark edge. A digit must have a correlation of at least 0.52 and a margin of 0.02 over the second template, and the whole code parity and checksum. When this grid misses on every wide crop, the same slot goes down to a coarser one: module step 0.2, start at least every pixel, no guard test, digit threshold 0.55. A smeared bar has a negative guard correlation, and the digits still match. That is how `Foto(751)` (`4902520242204`) and UPC-A `Foto(771)` read. The same start step and the same digit threshold, but module step 0.1, reads `Foto(714)` (`8001410088710`): the real module is 3.1 and does not lie on the 0.2 step. A fine start step, without guards, accepts a false code on `Foto(811)` and takes seconds, so the start stays at least one pixel. Both loose grids go after the guarded one, because otherwise they would replace its hit. Number 6 is not on this list: on `Foto(839)` it is the only read at −2°, and the text is the printed GTIN, not a typo from the label, so it would be false. When number 6 is itself the softest crop, as on `Foto(849)`, it goes first anyway. With `findAll` these slots are not there. Then point denoising, erosion, dilation, opening and closing on the narrow Sauvola image (2D symbologies only); these still recovered nothing. On a full miss at 1152×864 the budget ends at the 0.5× scale and none of these slots starts;
- up to five largest blobs after six dilations of the narrow Sauvola. The four extreme points of the contour go to deskew as the `blob-quad` attempt (Data Matrix, QR, Aztec, and with an elongated side also PDF417);
- when there is still nothing, or `findAll` is on: the QR, Micro QR, Aztec and Data Matrix finders on inverted grayscale. The attempt names stay `qr-trace`, `mqr-trace`, `aztec-trace`, `dm-trace`.

Every attempt in the schedule uses one budget slot. Confirming a 1D strip is part of the same attempt and does not use a second slot. After the number of attempts or the time is exceeded, the loop stops. FAST is 40 attempts and 2.5 s, AGGRESSIVE is 100 attempts and 8 s. `mode` changes only the ladder and does not overwrite a budget already set on the options. A single decoder call is not interrupted in the middle.

Location on an image longer than 1800 px first downscales the image. The corners are mapped back to source coordinates.

## Phases — exit criteria

### Phase 0

- There is a repeatable set and one command: `mvn test`.
- The report gives recall, false reads and time for baseline, FAST and AGGRESSIVE.
- The baseline is frozen in the test, not "a slightly improved ZXing".

### Phase 1

- Clean codes of the priority symbologies read in FAST.
- On the advantage set FAST has strictly higher recall than the baseline and zero false reads on the negatives.
- A 1D rotation outside multiples of 90° is the job of AGGRESSIVE mode.

### Phase 2

- A QR in clear perspective that the baseline does not read is read on the scan path. On the set `original-hybrid` wins.
- Data Matrix has a `blob-quad` path. On the set the perspective symbol and the symbol on a label win on `original-hybrid`, before the tile queue and `blob-quad` are reached.
- The result's corners are in the coordinate system of the input image, also after rotation and scaling.

### Phase 3

- The separate QR and Data Matrix paths can be switched on by symbology.
- The glare and uneven light heuristic is a separate attempt (today: local normalization), not a hand-tuned threshold for one file.
- `findAll` returns two codes from one image that a single ZXing `decode` does not return together. This works for two QR codes and for a QR next to Code 128.
- Aggressive mode is opt-in and has a harder budget.

### Phase 4

- The symbology priority is covered by a round-trip test (write with ZXing, read with our library).
- The attempt limit, the pixel limit and deduplication are defaults.
- An error in one variant does not throw out the whole `read`. No code is an empty list, not an exception.
- Metadata: position, orientation, confidence, attempt name.

The production set (flash, motion blur, foil, many labels in one photo) stays phase 0 in a continuous version: we add files, we do not change the definition of the baseline.

### Port A — detectors

- Data Matrix after the zxing-cpp detector, including large and uneven symbols.
- A QR finder with fixes missing from Java 3.5.4: version 1, round patterns, many small symbols.
- Aztec read from the internal timing patterns on a symbol that does not lie flat.
- Micro QR in our code.
- Criterion: `perspective-qr`, `qr-noise`, `perspective-datamatrix` and `datamatrix-on-label` read on this path without calling BoofCV. The baseline stays frozen. The negatives stay empty.

### Port B — binarization

- `LocalAverage` with symmetric threshold interpolation.
- The `tryInvert`, `tryRotate` and `tryDownscale` attempts sit in the schedule next to the already working ±15° and ±30° rotations.
- Criterion: the recall of the set does not drop against the table in [Measurement](#measurement).

### Port C — bit decoders

- One symbology at a time, in priority order.
- ZXing leaves a symbology only after the round-trip test (write with ZXing, read with our decoder) and a green `mvn test`.

### Port D — scaffolding removal

- BoofCV, and with it Trove, disappears after Ports A and B.
- `zxing-core` went down to the tests: the binarizers and Reed-Solomon are ported, and the bit matrix is `BlackMatrix`, one `boolean` per pixel.

## Text without ECI

Bytes not covered by any ECI go through `TextDecoder.guessEci`, that is `GuessTextEncoding` from `Content.cpp` of zxing-cpp 3.1.1: first valid UTF-8 with a multi-byte character (or a BOM), then Shift_JIS, and on a tie ISO-8859-1. QR and Micro QR read this way; the official `SimpleSJIS` test gives katakana. Data Matrix, Aztec and PDF417 use `guessUtf8OrLatin1`: UTF-8 when the bytes are valid UTF-8, otherwise ISO-8859-1. zxing-cpp guesses Shift_JIS there too, which turns a short Latin-1 text like `25°C` or `£5` into katakana, while these standards default to ISO-8859-1. MaxiCode stays at ISO-8859-1, like `defaultCharset` in zxing-cpp. Before this change a UTF-8 QR without ECI (e.g. `Zażółć gęślą jaźń`) came out as mojibake; `TextEncodingTest` reads it.

## Measuring changes

A change to the ladder or a decoder goes with a snapshot before and after:

```
mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot-after.tsv"
diff target/snapshot-before.tsv target/snapshot-after.tsv
```

`Snapshot` reads all 1225 images (Arte-Lab and the zxing-cpp samples in the `datasets/` submodule and the samples in `src/test/resources`) with plain ZXing (`zxing`) and in FAST, AGGRESSIVE and FAST with `findAll`, without a time budget. The output has no timings, so two runs of the same code give the same bytes on every machine, and `diff` shows every read whose text, symbology, attempt, orientation, confidence or corners changed. A purely performance change must give an empty `diff`. Next to the TSV comes `<file>.summary.md`: for each set and folder the number of images read in line with the label, the number read wrong, reads on images without a code, and for QR known only by corners the number of codes found.

Sets from outside the repository are fetched by `scripts/fetch-datasets.sh` (by default to `~/.cache/barcode-pro/datasets`, it checks SHA-256 and unpacks). None needs an account:

- KAIST DEAL (Do and Kim, Zenodo 13586402, CC BY 4.0): the standard set `barcode_bb` (EAN-13, the code in the file name; the test split `single_test` with 2000 photos and the training split `rec` with 1200), `e8_bb` (EAN-8), `multi_bb` (20 photos with several codes each, the labels are only boxes, so an EAN/UPC read with its centre in a box counts) and `background` (photos without a code). `rec` has 482 photos in the camera resolutions of Arte-Lab, including photos of GTINs from Arte-Lab labels, and AGGRESSIVE was tuned on Arte-Lab, so the report shows it separately and does not count it in the totals; `single_test` has 6 such photos. The authors' older archive `real.zip` contains crops of Arte-Lab photos and the script does not fetch it.
- The ZXing 3.5.4 test images with their expected text (Apache 2.0).
- BoofCV QR v3: hard QR codes in 16 `detection` categories, the labels are only corners; a code counts as found when the centre of a read lies in the label or the other way round. The `decoding` folder (27 codes with text) counts like ordinary text labels.

```
scripts/fetch-datasets.sh
mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot.tsv --external $HOME/.cache/barcode-pro/datasets"
```

Zenodo serves one connection slowly (about 60 KB/s), so the script fetches a large file in eight ranges at once and resumes an interrupted part on the next run. BarBeR (Vezzali et al., Modena: 8748 photos from 12 sets, every code with its type, polygon and text) needs an account, so the script does not fetch it, it only checks and unpacks the given archive: `BARBER_ZIP=<path> scripts/fetch-datasets.sh`. A code counts as read when one of the reads has its text; codes without text (the authors wrote `-1` or `^`) and types that no reader here reads (postal codes, IATA 2 of 5, a lone EAN-2) are not in the denominator. The report shows the subsets `Artelab`, `Artelab Extended` and `Deal Kaist` separately, outside the totals: they are the same Arte-Lab and KAIST photos that are already above.

The first run with BarBeR showed a weakness in Code 128: on 82 ParcelBar photos (module about 2 px, vertical codes) ZXing read the code and FAST nothing. On the same binarized rows ZXing's `Code128Reader` read 590 rows, the zxing-cpp port 157. Three causes: the port lost the `ODReader::DoDecode` loop, which with `tryHarder` keeps searching the row after a failed attempt (a false start in the print before the code ruled out the whole row); E2E matching at 2 px confuses characters (wrong checksum, unrecognized start); and a rejected strip confirmation ended the attempt instead of scanning the following rows. After the fixes (a loop as in zxing-cpp, a second variance attempt as in ZXing only after a failed E2E, a scan to the first confirmed row) the port reads 613 rows there. This costs about 5% CPU on the sets in the repository. The second variance attempt did however give a false read in AGGRESSIVE: `Special_0118` from ZVZ (`100514565003`) as `10051456500@`, two symbols wrong and the checksum matching by chance, and the same on several rows, so the parallel strip confirmed it. Neither a conflict with the E2E match (1131 of 1249 correct variance rows on ParcelBar have one) nor the margin of the best pattern told the bad reads apart; the module width did: correct ParcelBar variance reads have a module of 1.3–2.9 px, and this false one 3.1–3.2 px. The second attempt therefore accepts a read only with a module below 3 px; ParcelBar unchanged, `Special_0118` reads correctly. The first attempt at a fix (E2E before variance in the second attempt) took away about 100 ParcelBar reads and added false ones. Along the way `HitCollector` merges two scan lines of one linear code the way `Barcode::operator==` in zxing-cpp does; before, `findAll` returned the same EAN-13 several times on 64 photos.

2026-10-14: moving the packages, removing the dead `MultiFormatReader` path, one image conversion instead of three, lazy decoders in an attempt, `Fanout`, a faster 3×3 median, an EAN correlator on integer sums and `Bits` straight from `BitMatrix` gave an empty `diff` (3675 reads). On 70 Arte-Lab photos (50 from Dataset2, 20 from Dataset1) CPU time fell from 90.7 s to 61.1 s in FAST and from 197 s to 141 s in AGGRESSIVE, with the same hits; measured on a loaded machine, two rounds alternating. The rewritten `traceStep` of the Data Matrix detector (no `P` allocation per sample) also gave an empty `diff`. Encoding guessing fixed 4 images (`microqrcode-1/M3-Binary`, `M4-Binary`, `qrcode-2/10`, `qrcode-2/30`), and skipping the matrix detectors on rotations removed the false AZTEC `083` from `maxicode-1/mode5-sequence2of3` and changed nothing else.

The two-row rule for EAN-13/UPC-A: FAST on Arte-Lab 235 → 243 with the same 5 `fp`, AGGRESSIVE with unchanged numbers (378, 5 `fp`; some frames win earlier, on `original-hybrid`), zxing-cpp samples 442 → 457 in FAST and 454 → 458 in AGGRESSIVE. Every UPC-A and EAN-13 that only ZXing read reads in FAST (`RetailTwoLineTest`). The same rule without the exception for downscaled images and crops gave 384 hits in AGGRESSIVE, but 10 `fp`.

Data Matrix no longer runs in the FAST ladder variants (`inverted-luma`, `pad-4`, `contrast-stretch`, `illumination`, `adaptive-mean`, `otsu`, `scale-2`), unless `findAll` is on. The detector traces every edge of the image in four directions and both polarities; on a photo of a linear code this was the largest cost of FAST mode, and on 1225 images these variants read no Data Matrix that `original-*` and `dm-trace` had not taken. Snapshot `diff` empty. On the same 70 photos, at a similar load: the code before the 2026-10-14 changes FAST 67.1 s CPU and 18.5 s wall, AGGRESSIVE 172 s and 50.8 s; now FAST 22.7 s and 8.2 s, AGGRESSIVE 104 s and 28.9 s.

## Expectations

The scaffolding of phases 1–2 beats plain ZXing on inversion, QR and Data Matrix perspective, glare, noise, rotation of 1D codes by 30° and 45° and on a small Data Matrix on a large label. On the set from [Measurement](#measurement) this advantage is in our own code. Photos on which a commercial SDK wins thanks to its own location may still fail. The next step on such a photo is a new case in the set and a heuristic in Java.

## Code layout

| Package | Role |
| --- | --- |
| `io.github.kamilkrzywanski.barcode` | API and the attempt schedule (`Scan`) |
| `io.github.kamilkrzywanski.barcode.image` | Luminance, rotations, scales, homography, binarization (`BinaryBitmap`, `BlackMatrix`) |
| `io.github.kamilkrzywanski.barcode.ecc` | Reed-Solomon: decoder with erasures from librscpp (zxing-cpp 3.1.1), encoder from ZXing 3.5.4 |
| `io.github.kamilkrzywanski.barcode.preprocess` | Thresholds and morphology: Otsu, local mean, Gauss, Sauvola, erosion, dilation, denoising |
| `io.github.kamilkrzywanski.barcode.decode` | Bit reading and deduplication. Every symbology is our own; `BitmapDecoder` reads them from one binarized attempt |
| `io.github.kamilkrzywanski.barcode.locate` | QR, Micro QR, Aztec and Data Matrix finders and `blob-quad` |
| `io.github.kamilkrzywanski.barcode.cli` | CLI: `java -jar barcode-pro.jar <file> [--aggressive] [--all]` |

The tests in `src/test/java` generate the set and compare it with the baseline.

## Measurement

The numbers below come from `mvn test` on the synthetic set of this repository. The set is not yet a set of production photos.

<!-- BENCHMARK-START -->
One run of `mvn test` in this repository, 2026-10-12, already without `boofcv-ip` and `boofcv-recognition`. Times are the wall time of one `read` per image, no median. The baseline / fast / aggressive columns are recall (expected contents read / expected contents). `ok` on a negative means zero reads. `FP` does not occur in this table.

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
| code128-rotated-30 | AGGRESSIVE_ADVANTAGE | 0/1 | 1/1 | 1/1 | 197 | 38 |
| code128-rotated-45 | AGGRESSIVE_ADVANTAGE | 0/1 | 1/1 | 1/1 | 129 | 46 |
| datamatrix-on-label | AGGRESSIVE_ADVANTAGE | 0/1 | 1/1 | 1/1 | 24 | 24 |
| tiny-qr | STRESS | 1/1 | 1/1 | 1/1 | 2 | 2 |
| qr-blur | STRESS | 1/1 | 1/1 | 1/1 | 4 | 4 |
| qr-gradient | STRESS | 1/1 | 1/1 | 1/1 | 3 | 3 |
| blank | NEGATIVE | ok | ok | ok | 18 | 30 |
| gradient | NEGATIVE | ok | ok | ok | 13 | 48 |
| noise | NEGATIVE | ok | ok | ok | 16 | 42 |
| two-qr | MULTI | 1/2 | 2/2 | — | 43 | — |
| qr-and-code128 | MULTI | 1/2 | 2/2 | — | 48 | — |

Total contents in `Samples.all()` (without the MULTI rows): baseline 11/22, FAST 22/22, AGGRESSIVE 22/22. No false reads, also on the three negatives in both modes.

Clean priority codes win on `original-hybrid`. The orientation of QR, Data Matrix and PDF417 is 0, of linear codes −1.

FAST advantages the baseline does not read (8/8 vs 0/8): inverted QR and Data Matrix on `original-hybrid` (orientation 0; the white matrix is `flipAll` in the same attempt), white Code 128 on black (`inverted-luma`, orientation −1), QR in perspective (`original-hybrid`, orientation 5, content `QR-SKEW`), a QR with error correction level H in the wrong block phase (`original-hybrid`, orientation 0), glare (`illumination`, content `QR-GLARE`), 2% salt-and-pepper noise (`qr-trace`, content `QR-NOISE`; the 3×3 median is a second look in the same attempt, and a raw `QrDetector.read` on this grayscale stays empty), Data Matrix rotated and fitted into a quadrilateral (`original-hybrid`, orientation 24, content `DM-SKEW`).

The AGGRESSIVE_ADVANTAGE bucket is no longer exclusive to aggressive mode: aggressive reads Code 128 at 30° by the `rotate--15.0` attempt (orientation 345, content `ANGLE-128`) and Code 128 at 45° by the `rotate--30.0` attempt (orientation 330, content `ANGLE-45`), and fast, since item 15, reads both after its ladder misses, by `linear-band` (orientation 330 and 315). The baseline reads neither. The small Data Matrix on a 960×960 label still has this bucket in the table, and the fast column is 1/1: `original-hybrid` reads it, orientation 0, content `DM-TILE`. The baseline stays at 0/1.

The `blob-quad` path is checked separately: after six dilations the four extreme points of the blob straighten the perspective Data Matrix to the content `DM-SKEW`. In the scan this image wins earlier, on `original-hybrid`.

Two QR codes: baseline 1/2, FAST with `findAll` 2/2 (`QR-LEFT`, then `QR-RIGHT`, both `original-hybrid`, orientation 0). QR next to Code 128: baseline 1/2. With `findAll` off only the QR remains (`QR-AND`, `original-hybrid`). FAST with `findAll` returns this QR first, then `AND-128` as Code 128, both attempts `original-hybrid`.

Stress (1 px module, 5×5 blur, a mild gradient) is read by the baseline and FAST too. These rows do not count towards the advantage.
<!-- BENCHMARK-END -->

## Arte-Lab

The Medium Barcode 1D set lives in `datasets/artelab-medium-barcode-1d/` (Dataset1 with autofocus, Dataset2 without, 215 JPEGs each). `datasets/` is a submodule with the [barcode-pro-datasets](https://github.com/kamilkrzywanski/barcode-pro-datasets) repository (`git submodule update --init datasets`), so that the project history does not drag 224 MB of photos along. The files are not part of `Samples.all()` nor on the test classpath. `mvn test` does not need the submodule: eight photos of frozen false reads are copied to `src/test/resources/artelab`, and the presence and labels of the whole set are checked by `ArteLabCorpusTest` in the `artelab` profile.

The named set, whose text is a contract, runs separately:

```
mvn -Partelab test
```

These are tilted frames won by a rotation or the strip, the control `Foto(542)` on FAST (`illumination`, `8002205319804`), the recovered `Foto(503)`, `Foto(543)`, `Foto(707)`, `Foto(725)`, `Foto(481)`, `Foto(573)`, `Foto(728)`, `Foto(501)`, `Foto(556)`, `Foto(817)`, `Foto(820)`, `Foto(406)`, `Foto(483)`, `Foto(738)`, `Foto(504)`, `Foto(480)`, `Foto(676)`, `Foto(829)`, `Foto(427)`, `Foto(677)`, `Foto(846)`, `Foto(851)`, `Foto(489)`, `Foto(675)`, `Foto(678)`, `Foto(683)`, `Foto(722)`, `Foto(516)`, `Foto(837)`, `Foto(842)`, `Foto(698)`, `Foto(515)`, `Foto(824)`, `Foto(823)`, `Foto(828)`, `Foto(830)`, `Foto(484)`, `Foto(496)`, `Foto(312)`, `Foto(827)`, `Foto(751)` and `Foto(714)`, `05102009191`, UPC-A `Foto(765)`, `Foto(776)`, `Foto(778)`, `Foto(779)`, `Foto(763)`, `Foto(766)`, `Foto(769)`, `Foto(770)`, `Foto(772)`, `Foto(767)`, `Foto(761)`, `Foto(760)`, `Foto(762)` and `Foto(771)`, and the printed GTIN on an empty label `Foto(844)` and `Foto(849)`.

The full measurement of 430 frames is not a test. From the repository directory:

```
mvn -q -Partelab-eval test-compile exec:java -Dexec.args="--out target/artelab-report.tsv"
```

The program reads the baseline (one `MultiFormatReader`, `HybridBinarizer`, `TRY_HARDER`), then FAST, and AGGRESSIVE only when FAST did not return the label. The FAST ladder is a prefix of the aggressive one and stops on a hit, so a skipped column has the class `skipped`.

Read class:

- `exact` — the text equals the 13 digits from the label file;
- `upca` — the label has 13 digits and starts with a zero, and the text is those digits without one leading zero. An eight-digit UPC-E is not a hit;
- `empty` — no read;
- `allowed` — the label file is empty, and the text is the printed code of this frame: `05102009199` is `8002330112752`, `Foto(844)` and `Foto(849)` are `7321925005431`;
- `fp` — any other text;
- `error` — the frame could not be loaded.

`unionHit` is `exact` or `upca` on the aggressive result, and when that column is `skipped`, on the FAST result. `allowed` counts neither as a hit nor as `fp`. The sum `unionHit + empty + allowed + fp + error` is the number of frames. `target/` is in `.gitignore`.

The latest run of this command, 2026-10-17, took 128 s with compilation from a clean `target/`. 430 frames, zero load errors.

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 193 | 209 | 0 | 1 | 5 | 416 | 960 |
| Dataset2 | 215 | 22 | 50 | 169 | 44 | 2 | 0 | 115 | 497 |
| total | 430 | 205 | 243 | 378 | 44 | 3 | 5 | 416 | 960 |

Against the 234 s run FAST reads eight labels more, four in each set: the two-row rule for EAN-13/UPC-A (see [Measuring changes](#measuring-changes)). Union, `empty`, `allowed` and `fp` are unchanged; some of the eight frames now win in FAST and their aggressive column is `skipped`. The five `fp` are the same closed class: a Data Matrix read in the print of `05102009132` and of `05102009161`–`05102009163`, and the UPC-E `10058084` on `05102009159`. `Foto(312)`, `Foto(714)`, `Foto(751)`, `Foto(827)` and UPC-A `Foto(771)` still read from `ean-correlate`, `Foto(799)` and `Foto(848)` on `scale-0.5`, and `Foto(849)` stays an allowed read on `linear-band-us4-rot`. `Foto(811)` and `Foto(839)` stay empty. The longest frames take about half the time of the 234 s run; in between came the binarization without ZXing's bit containers, the row shared by all linear readers and the Data Matrix skipped in the FAST ladder variants. The longest aggressive frame takes 960 ms (`PICawdT0024`, `sauvola-0`), in Dataset2 497 ms (`Foto(753)`, empty). FAST at most 416 ms (`05102009127`, class `empty`), in Dataset2 115 ms (`Foto(573)`, class `empty`).

The 234 s run, 2026-10-13, had union 378. 430 frames, zero load errors. The baseline and FAST text, frame by frame, was the same as in the 240 s run.

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 909 | 1808 |
| Dataset2 | 215 | 22 | 46 | 169 | 44 | 2 | 0 | 264 | 2475 |
| total | 430 | 205 | 235 | 378 | 44 | 3 | 5 | 909 | 2475 |

Against the measurement with union 377 one hit was added and nothing disappeared. `Foto(714)` reads `8001410088710` from `ean-correlate`, from the module step 0.1 in the same slot. This grid starts only when the guarded correlation and the 0.2 step miss on all wide crops. The start step stays at least a pixel, and the digit threshold 0.55. A fine start step on the same crops accepts the false code `Foto(811)` and takes several seconds, so it is not there. `Foto(811)` stays empty. No earlier read changed its attempt, there is no new `fp`, and the FAST cells are unchanged. `Foto(751)` stays at `4902520242204`, `Foto(771)` at UPC-A `051122414831`, both still from the 0.2 step. `Foto(799)` and `Foto(848)` stay on `scale-0.5`. `Foto(849)` stays an allowed read `7321925005431`. `Foto(839)` still misses: the label has a typo, and the printed GTIN is not a hit. The five `fp` are the same closed class. The longest aggressive frame takes 2475 ms (`Foto(753)`, empty): the 0.1 step is reached on a full miss. In Dataset1 the longest takes 1808 ms (`PICT00gfhh38`, `linear-band-mean`). FAST at most 909 ms, also `PICT00gfhh38` (class `empty`). In Dataset2 FAST at most 264 ms (`Foto(477)`, class `empty`). None reached 8 s, and none reached 2.5 s in FAST.

The 240 s run had union 377. 430 frames, zero load errors. The baseline and FAST text, frame by frame, was the same as in the 226 s run.

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 914 | 1880 |
| Dataset2 | 215 | 22 | 46 | 168 | 45 | 2 | 0 | 363 | 1430 |
| total | 430 | 205 | 235 | 377 | 45 | 3 | 5 | 914 | 1880 |

Against the measurement with union 375 two hits were added and nothing disappeared. Both come from `ean-correlate`, from a coarser grid in the same slot, which starts only when the guarded correlation misses on all wide crops. `Foto(751)` reads `4902520242204`. `Foto(771)` reads UPC-A `051122414831`. The smeared bar has a negative start guard correlation, and the digits still match; the module step 0.2 and the digit threshold 0.55 hold this read. A fine grid without guards on the same crops accepts the false code `Foto(811)` and takes several seconds, so it is not there. No earlier read changed its attempt, there is no new `fp`, and the FAST cells are unchanged. `Foto(799)` and `Foto(848)` stay on `scale-0.5`. `Foto(849)` stays an allowed read `7321925005431`. `Foto(839)` still misses: the label has a typo, and the printed GTIN is not a hit. `Foto(811)` stays empty. The five `fp` are the same closed class. The longest aggressive frame takes 1880 ms (`PICT00gfhh38`, `linear-band-mean`). In Dataset2 the longest takes 1430 ms (`Foto(719)`, empty): the coarser grid is reached on a full miss. FAST at most 914 ms, also `PICT00gfhh38` (class `empty`). In Dataset2 FAST at most 363 ms (`Foto(518)`, class `empty`). None reached 8 s, and none reached 2.5 s in FAST.

The 226 s run had union 375. 430 frames, zero load errors. The baseline and FAST text, frame by frame, was the same as in the 227 s run.

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 895 | 1847 |
| Dataset2 | 215 | 22 | 46 | 166 | 47 | 2 | 0 | 271 | 760 |
| total | 430 | 205 | 235 | 375 | 47 | 3 | 5 | 895 | 1847 |

Against the measurement with union 371 four hits were added and nothing disappeared. All come from `ean-correlate`, the digit correlation on the sharpest rows of a crop. `Foto(312)` reads `8011642115887`. `Foto(827)` reads `8011642111896`. `Foto(760)` and `Foto(762)` read UPC-A `051122414831`. No read changed its attempt, there is no new `fp`, and the FAST cells are unchanged. `Foto(799)` and `Foto(848)` stay on `scale-0.5`. `Foto(849)` stays an allowed read `7321925005431`. `Foto(839)` still misses: the label has a typo, and the printed GTIN is not a hit. The five `fp` are the same closed class. The longest aggressive frame takes 1847 ms (`PICT00gfhh38`, `linear-band-mean`). In Dataset2 the longest takes 760 ms (`Foto(555)`, empty). FAST at most 895 ms, also `PICT00gfhh38` (class `empty`). None reached 8 s, and none reached 2.5 s in FAST.

The 227 s run had union 371. Against the measurement with union 368 three hits were added then and nothing disappeared. `Foto(484)` reads `8011642115962` from `linear-band-us4-top`, crop 2 rotated by −1°. `Foto(496)` reads `4902030187590` from `linear-band-key`, crop 3 rotated by +1° and pulled from a trapezoid. `Foto(761)` reads UPC-A `051122414831` from `linear-band-su2-rot`, crop 0 rotated by −1°. The longest aggressive frame took 1990 ms (`PICT00gfhh38`, `linear-band-mean`). In Dataset2 the longest took 787 ms (`Foto(555)`, empty). FAST at most 973 ms, also `PICT00gfhh38` (class `empty`).

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 973 | 1990 |
| Dataset2 | 215 | 22 | 46 | 162 | 51 | 2 | 0 | 276 | 787 |
| total | 430 | 205 | 235 | 371 | 51 | 3 | 5 | 973 | 1990 |

The 223 s run had union 368. Against the measurement with union 365 three hits were added then, all on `linear-band-us4-rot` and all with the label `8011642111896`. `Foto(823)` comes from crop 2 rotated by +0.5°. `Foto(828)` comes from crop 3 rotated by +1°. `Foto(830)` comes from crop 3 rotated by +2°. The longest aggressive frame took 1837 ms (`PICT00gfhh38`, `linear-band-mean`), and in Dataset2 736 ms (`Foto(518)`, `scale-0.5`). FAST at most 895 ms, also `PICT00gfhh38` (class `empty`).

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 895 | 1837 |
| Dataset2 | 215 | 22 | 46 | 159 | 54 | 2 | 0 | 262 | 736 |
| total | 430 | 205 | 235 | 368 | 54 | 3 | 5 | 895 | 1837 |

The 227 s run had union 365. Against the measurement with union 363 two hits were added then, both on `linear-band-us4-rot`, from crop 0 rotated by +2°. `Foto(515)` reads `5030930017491`. `Foto(824)` reads `8011642111896`. The longest aggressive frame took 1839 ms (`PICT00gfhh38`, `linear-band-mean`), and in Dataset2 743 ms (`Foto(516)`, a hit). FAST at most 922 ms, also `PICT00gfhh38` (class `empty`).

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 922 | 1839 |
| Dataset2 | 215 | 22 | 46 | 156 | 57 | 2 | 0 | 279 | 743 |
| total | 430 | 205 | 235 | 365 | 57 | 3 | 5 | 922 | 1839 |

The 228 s run had union 363. Against the measurement with union 362 one hit was added then: `Foto(698)` reads `4902030195397` from crop 1 rotated by −1°. The longest aggressive frame took 1857 ms (`PICT00gfhh38`, `linear-band-mean`), and in Dataset2 989 ms (`Foto(515)`, still empty then).

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 915 | 1857 |
| Dataset2 | 215 | 22 | 46 | 154 | 59 | 2 | 0 | 266 | 989 |
| total | 430 | 205 | 235 | 363 | 59 | 3 | 5 | 915 | 1857 |

The 239 s run had union 362. Against the measurement with union 359 three hits were added then, all on `linear-band-us4-rot`. The soft crop is sharpened with radius 3. `Foto(516)` reads `5030930017491`. `Foto(837)` and `Foto(842)` read `7321925005431`. The longest aggressive frame took 1988 ms then (`PICT0036`, `linear-band`), and in Dataset2 946 ms (`Foto(515)`, empty). FAST at most 1075 ms (`PICT00daw23`).

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 1075 | 1988 |
| Dataset2 | 215 | 22 | 46 | 153 | 60 | 2 | 0 | 318 | 946 |
| total | 430 | 205 | 235 | 362 | 60 | 3 | 5 | 1075 | 1988 |

The 227 s run had union 359. Against the measurement with union 353 six hits were added then, all from Dataset2 and all on `linear-band-us4-rot`. `Foto(489)`, `Foto(675)`, `Foto(678)` and `Foto(722)` read `4902030187590`. `Foto(683)` reads `8011642115962`. `Foto(767)` reads UPC-A `051122414831`. Nothing disappeared and there was no new `fp`. `Foto(799)` and `Foto(848)` stay on `scale-0.5`. `Foto(849)` stays an allowed read. The longest aggressive frame took 1813 ms (`PICT00gfhh38`, `linear-band-mean`), and in Dataset2 872 ms (`Foto(515)`, empty). FAST at most 918 ms, also `PICT00gfhh38` (class `empty`).

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 918 | 1813 |
| Dataset2 | 215 | 22 | 46 | 150 | 63 | 2 | 0 | 282 | 872 |
| total | 430 | 205 | 235 | 359 | 63 | 3 | 5 | 918 | 1813 |

The 225 s run had union 353. Against the measurement with union 344 nine hits and one allowed read were added then. Nothing disappeared and there was no new `fp`. `Foto(427)` (`8002330002534`), `Foto(677)` (`4902030187590`), `Foto(846)` and `Foto(851)` (both `7321925005431`) and UPC-A `051122414831` on `Foto(763)`, `Foto(766)`, `Foto(769)`, `Foto(770)` and `Foto(772)` come from `linear-band-us4-rot`. `Foto(849)` has an empty label; the same rotation reads the printed `7321925005431`. `Foto(799)` and `Foto(848)` stay on `scale-0.5`. The longest aggressive frame took 1840 ms (`PICT00gfhh38`, `linear-band-mean`), and in Dataset2 862 ms (`Foto(515)`, empty).

| set | n | baseline | fast | union | empty | allowed | fp | max fast ms | max aggressive ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dataset1 | 215 | 183 | 189 | 209 | 0 | 1 | 5 | 886 | 1840 |
| Dataset2 | 215 | 22 | 46 | 144 | 69 | 2 | 0 | 271 | 862 |
| total | 430 | 205 | 235 | 353 | 69 | 3 | 5 | 886 | 1840 |

The 230 s run had union 344. Against the measurement with union 340 four hits were added then. `05102009191` (`8002330112752`) comes from `linear-band-mean`: the taller crop has this text on two rows free of glare. `Foto(480)`, `Foto(676)` and `Foto(829)` come from `linear-band-us4-otsu` (GTIN `8011642111896`, `4902030187590` and again `8011642111896`). `Foto(741)` stays at `8011642115887`, but wins earlier, on `linear-band-otsu` instead of `linear-band`. The baseline and FAST text was the same as in the 368 s run. The times were shorter, because the attempts after `original-global` and the four aggressive angles are computed in parallel, and the 3×3 median no longer clamps the middle of the window. The longest aggressive frame took 1784 ms then, and in Dataset2 886 ms.

The 368 s run had union 340. Against the measurement with union 332 eight hits were added then, all from Dataset2: UPC-A `051122414831` on `Foto(765)`, `Foto(776)`, `Foto(778)` and `Foto(779)` (`linear-band-level-top`), `Foto(483)` (`linear-band-level-top40`, `8011642115962`), `Foto(406)` (column median, `8011642115887`), `Foto(738)` (darkest column pixel, the same GTIN) and `Foto(504)` (radius 2 sharpening of the mean of sharp rows, `8002330097875`). These stay too: `Foto(481)` and `Foto(728)` on the 35th percentile, `Foto(573)` on the 45th, `Foto(556)` on the 50th percentile, `Foto(501)` on the sharpening of that mean, `Foto(817)` and `Foto(820)` on the local mean, `Foto(503)` and `Foto(543)` on the crop sharpening, `Foto(707)` on 2×, and `Foto(508)`, `Foto(725)` and `Foto(729)` on the 35th percentile. The same GTIN `8011642111896` also comes earlier on `Foto(816)`, `Foto(818)` and `Foto(819)`. `Foto(724)` stays at the same GTIN on `linear-band-unsharp-otsu`. The angle search builds the run table once and ranks it separately by density and by rhythm. The angles are computed in parallel and assembled in angle order, so a tie stays with the earlier one. On all 430 frames the crops are identical. The search alone, without loading the JPEGs, fell from 289 s to 52 s. An earlier merge of the two rankings into one pass went from 408 s to 312 s.
