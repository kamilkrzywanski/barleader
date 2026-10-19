# Barleader — architecture

Barleader reads and writes barcodes in plain Java bytecode: no JNI, no native OpenCV, no zint and no other platform binaries. The read path is a port of [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) 3.1.1, the writers are ports of ZXing 3.5.4 and Barcode-Lib4J 3.2.0, all Apache 2.0 (see `NOTICE`). The comment above a ported class names its source file and tag. The behaviour and the structure of each algorithm are ported, not the C++ syntax; the run time of the C++ binary is not a goal.

On hard images the library must read clearly more than the standard Java ZXing call, and it must not trade that for false reads. Commercial SDKs on dirty production photos are not a promised target.

## Packages

The module `io.github.kamilkrzywanski.barleader` exports only its package of that name. Everything else is internal and may change in any release.

| Package | Role |
| --- | --- |
| `io.github.kamilkrzywanski.barleader` | API: `BarcodeReader`, `BarcodeWriter`, `QrCode`, `Barcode`, the options; the attempt ladder (`Scan`), strips of bars (`BarStrips`, `LinearBand`), drawing and the PDF writer |
| `…barleader.image` | Luminance, rotations, scales, homographies, the binarizers ported from ZXing (`BinaryBitmap`, `BlackMatrix`) |
| `…barleader.preprocess` | Thresholds and morphology: Otsu, local mean, Gauss, Sauvola, erosion, dilation, denoising |
| `…barleader.locate` | QR, Micro QR, rMQR, Aztec and Data Matrix finders, `blob-quad` |
| `…barleader.decode` | Bit decoders of every symbology, the linear scanners, the gray-profile fits (`Code128Fit`, `EanFit`), deduplication |
| `…barleader.qr` | QR version tables and the QR encoder |
| `…barleader.encode` | The other encoders, ported from ZXing and Barcode-Lib4J |
| `…barleader.ecc` | Reed-Solomon: the decoder with erasures from zxing-cpp's librscpp, the encoder from ZXing |
| `…barleader.cli` | `java -jar barleader.jar` |

There is no runtime dependency. ZXing `core` and `javase`, Barcode-Lib4J and PDFBox are in the tests only: as the baseline, to draw synthetic symbols, and to compare written symbols module by module.

## Reading

```
BufferedImage or 8-bit luminance
      ↓
BarcodeReader.read(image, ReadOptions)
      ↓
1. original-hybrid, original-global
2. the cheap ladder, computed together, accepted in order
   inverted-luma, pad-4, contrast-stretch, illumination,
   rotate-90/180/270 (linear and PDF417 only), adaptive-mean, otsu,
   scale-2, downscale-2, downscale-4
3. finders: qr-trace, mqr-trace, aztec-trace, dm-trace
      ↓ FAST after a miss
4. bar-strip, then the coarse linear-band search
      ↓ AGGRESSIVE instead
4. rotate ±15° and ±30°, the full linear-band search, pyramid,
   tiles (2D only), Sauvola, scale-1.5, scale-0.5,
   sharpened and turned band crops, ean-correlate,
   morphology (2D only), blob-quad, finders on inverted grayscale
      ↓ both, on an image nothing has read
5. code128-fit, ean-fit
      ↓
List<BarcodeHit>
```

The order is deliberate. A clean symbol ends on the first attempt, at a cost close to plain ZXing; the expensive stages start only when the earlier ones found nothing, or when `findAll` asks to keep looking. Every attempt takes one slot of the attempt budget: fast mode has 40 attempts and 2.5 s, aggressive 100 attempts and 8 s. `timeBudgetMillis(Long.MAX_VALUE)` makes a read depend only on the attempt budget, so its result is the same on every machine. A single decoder call is never interrupted.

`new BarcodeReader()` computes independent attempts on a shared pool of daemon threads, `new BarcodeReader(executor)` on the caller's pool, and `BarcodeReader.sequential()` on the calling thread. All three return the same hits when the time budget does not cut the read. A reader is stateless and can be shared between threads.

### Stages

- **Binarization.** `HybridBinarizer` and `GlobalHistogramBinarizer` are ported from ZXing bit for bit, on ZXing's luminance `(306 R + 601 G + 117 B + 512) >> 10`. A row is kept with its runs for the whole attempt, so every linear reader shares one binarization, as `ODReader` does in zxing-cpp.
- **Matrix finders.** The QR, Micro QR, rMQR, Aztec and Data Matrix detectors of zxing-cpp. Data Matrix with several data regions samples each region with its own transform between the alignment crossings it finds. A QR that no regular grid decodes is sampled once more on a bent grid fitted to the module edges (a symbol on a can). The finders do not depend on the angle, so they do not run on rotated images.
- **Linear scanners.** zxing-cpp's row readers. When no whole-pixel row reads, Code 128, Code 39 and EAN/UPC scan the rows again with edges in quarter pixels, for modules of one to two pixels.
- **Strips of bars.** `BarStrips` finds patches of parallel bars from the structure tensor of 16-pixel blocks and samples each with its bars vertical; `LinearBand` searches whole bands over angles. Both crops are read by the linear readers only, so a crop never assembles a false PDF417 or matrix symbol.
- **Gray-profile fits.** Under about 1.3 pixels a module no threshold separates the bars, but the averaged gray profile still matches the blurred patterns. `Code128Fit` and `EanFit` fit every symbol or digit position by least squares, then search for the cheapest sequence with a right check, symbol by symbol shifted where it fits best. They run last, only when nothing was read, and take no attempt slot.
- **Error correction.** Every 2D decoder corrects errors and erasures with the port of librscpp, keeping 2t + e ≤ numEC and two codewords for detection in PDF417.

### False reads

A linear read must hold elsewhere before it counts: on a parallel strip a few pixels from the line that read it, or, for a full EAN-13 or UPC-A, on another row of the same bitmap (zxing-cpp's `minLineCount = 2`), but not on a downscaled image or a crop, where neighbouring rows are near copies. A read needs two points at least 30 px apart. Morphological variants decode only 2D symbologies. Band crops read by Otsu or local mean must repeat on several rows, and the gray-profile fits need a residual margin over the runner-up and agreement between two bands of rows. With `findAll`, `HitCollector` merges two scan lines of one symbol and drops a UPC-E cut from the left half of an EAN-13 read beside it.

### Text

Bytes under an ECI use its character set. Bytes without one go through `GuessTextEncoding` of zxing-cpp for QR and Micro QR (UTF-8, then Shift_JIS, then ISO-8859-1); Data Matrix, Aztec and PDF417 take UTF-8 when the bytes are valid UTF-8 and ISO-8859-1 otherwise, as their standards default to it. `BarcodeHit.bytes()` returns the data bytes without ECI markers, so binary content comes back without loss.

## Writing

`BarcodeWriter.encode(BarcodeType, content, WriteOptions)` returns a `Barcode`, a matrix of modules. The 2D and most linear encoders are ports of ZXing; Code 11, PZN, ISBN, ISMN, EAN-14, SSCC-18, the EAN add-ons, quiet zones and GS1 validation are ports of Barcode-Lib4J. For the same choices every module is the one the source library writes, which the tests check symbol by symbol. Two bugs of ZXing's minimal Data Matrix encoder are fixed (the 12×36 rectangle capacity, and EDIFACT at the end of the data without an unlatch).

A `Drawing` is bar rectangles and glyph outlines in modules, so the image, the SVG, the PDF and `draw(Graphics2D, …)` show the same shapes. The human-readable layout is a port of Barcode-Lib4J's. `toPdf` writes one PDF 1.4 page without a library and without an embedded font. The glyph outlines come from AWT, so a headless JDK without fonts needs `RenderOptions.humanReadable(false)`.

## Testing and measuring

`mvn test` runs the unit tests, round trips with ZXing's writers, the module-by-module writer comparisons and a deterministic synthetic set (clean, inverted, perspective, glare, noise, rotated, multiple and blank images) against a frozen baseline: `MultiFormatReader` with `HybridBinarizer`, `BufferedImageLuminanceSource` and `TRY_HARDER`. It needs no submodule.

The real photos are in the `datasets` submodule, [barleader-datasets](https://github.com/kamilkrzywanski/barleader-datasets) (`git submodule update --init datasets`): the Arte-Lab Medium Barcode 1D set and the zxing-cpp 3.1.1 samples.

```
mvn -Partelab test                                     # the Arte-Lab frames whose text is pinned
mvn -q -Partelab-eval test-compile exec:java -Dexec.args="--out target/artelab-report.tsv"
mvn -q -Pspeed test-compile exec:java -Dexec.args="datasets/artelab-medium-barcode-1d"
```

A change to the ladder or a decoder goes with a snapshot before and after:

```
mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot-after.tsv"
diff target/snapshot-before.tsv target/snapshot-after.tsv
```

`Snapshot` reads every image with plain ZXing and in fast, aggressive and fast-with-`findAll` mode, without a time budget and without timings, so two runs of the same code give the same bytes and `diff` shows every read whose text, symbology, attempt, orientation or corners moved. A change meant only for speed must give an empty `diff`. Beside the TSV comes `<file>.summary.md`, scored per set and folder against the labels.

Larger public sets are fetched outside the repository by `scripts/fetch-datasets.sh` (by default into `~/.cache/barleader/datasets`, checked by SHA-256): KAIST DEAL, the ZXing 3.5.4 test images and BoofCV's QR set. BarBeR needs an account and is only unpacked from a given archive (`BARBER_ZIP=<path>`). Add them to a snapshot with `--external <dir>`. The README has the resulting tables.

## Out of scope

- JNI, JavaCV, native OpenCV, libdmtx, zint, or binding zxing-cpp as a C++ library.
- The zint writer of zxing-cpp 3.x, EPS, and embedded fonts in PDF.
- Speed parity with the C++ binary, and SIMD through the incubating Vector API.
- QR Model 1, Han Xin and DotCode, for now.
