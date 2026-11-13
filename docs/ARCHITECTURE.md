# Barleader — architecture

Barleader reads and writes barcodes in plain Java bytecode: no JNI, no native OpenCV, no zint and no other platform binaries. The read path is a port of [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) 3.1.1, the writers are ports of ZXing 3.5.4 and Barcode-Lib4J 3.2.0, all Apache 2.0 (see `NOTICE`). The comment above a ported class names its source file and tag. The behaviour and the structure of each algorithm are ported, not the C++ syntax; the run time of the C++ binary is not a goal.

On hard images the library must read clearly more than the standard Java ZXing call, and it must not trade that for false reads. Commercial SDKs on dirty production photos are not a promised target.

## Packages

The module `io.github.kamilkrzywanski.barleader` exports only its package of that name. Everything else is internal and may change in any release.

| Package | Role |
| --- | --- |
| `io.github.kamilkrzywanski.barleader` | API: `BarcodeReader`, `BarcodeWriter`, `QrCode`, `Barcode`, the options; the attempt ladder (`Scan`), strips of bars (`BarStrips`, `BarSegments`, `LinearBand`, `Deblur`), drawing and the PDF writer |
| `…barleader.image` | Luminance, rotations, scales, homographies, the binarizers ported from ZXing (`BinaryBitmap`, `BlackMatrix`) |
| `…barleader.preprocess` | Thresholds and morphology: Otsu, local mean, Gauss, Sauvola, erosion, dilation, denoising |
| `…barleader.locate` | QR, Micro QR, rMQR, Aztec and Data Matrix finders, `blob-quad` |
| `…barleader.decode` | Bit decoders of every symbology, the linear scanners, the lines across a box (`ProfileLines`), the gray-profile fits (`Code128Fit`, `EanFit`), deduplication |
| `…barleader.retail` | The rows of a box read for EAN-13 and UPC-A after tapirscan's Medium mode (`RetailEngine`), with its decoders of run widths and of module darkness |
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
      ↓ from 20 Mpx: only the box that holds anything (ContentBounds);
        in fast mode first the whole ladder on a copy of about 8 Mpx
1. original-hybrid, original-global (the global one for what reads the black matrix)
      ↓ without findAll
2. the candidates: scan-line across the boxes of `BarSegments`, where no line
   read scan-rows of those boxes for EAN-13 and UPC-A, then a second look at
   their lines and their sharpened crops, then bar-strip,
   the linear crops of `BarSegments` and their rescue; an EAN-13 or UPC-A of a
   crop that no line of the frame beside it repeats, and a UPC-E, wait for 3 and 4
   and count only when those read nothing
      ↓ QUICK ends here, with findAll after reading the candidates of 2 as well;
        a read that waits counts
3. the cheap ladder, computed together, accepted in order
   inverted-luma, pad-4, contrast-stretch, illumination,
   rotate-90/180/270 (linear and PDF417 only), adaptive-mean, otsu,
   scale-2, downscale-2, downscale-4,
   then contrast-stretch, illumination, adaptive-mean, otsu and
   downscale-2 turned by 90° (linear only), without an attempt slot
4. finders: qr-trace, mqr-trace, aztec-trace, dm-trace
      ↓ on an image nothing has read, or with findAll
5. with findAll the candidates of 2; the deblurred copies of the crops,
   code128-fit, ean-fit, ean-halves and on a colour image channel-max, channel-min
      ↓ AGGRESSIVE after a miss, with the attempts 2 and 5 took on top of its budget
6. rotate ±15° and ±30°, the full linear-band search, pyramid,
   tiles (2D only), Sauvola, scale-1.5, scale-0.5,
   sharpened and turned band crops, ean-correlate,
   morphology (2D only), blob-quad, finders on inverted grayscale
      ↓
List<BarcodeHit>
```

The order is deliberate. A clean symbol ends on the first attempt, at a cost close to plain ZXing; the expensive stages start only when the earlier ones found nothing, or when `findAll` asks to keep looking. Every attempt takes one slot of the attempt budget: fast mode has 40 attempts and 2.5 s, aggressive 100 attempts and 8 s. `timeBudgetMillis(Long.MAX_VALUE)` makes a read depend only on the attempt budget, so its result is the same on every machine. A single decoder call is never interrupted.

`new BarcodeReader()` computes the attempts one after another on the calling thread, `BarcodeReader.parallel()` independent attempts side by side on a shared pool of at most three daemon threads, and `new BarcodeReader(executor)` on the caller's pool. All three return the same hits when the time budget does not cut the read: attempts that run side by side are taken in the order of the ladder, on the calling thread, and an attempt counts only where it would one after another. Without findAll the candidates of 2 run side by side, and after a miss of 2 and 3 the turned variants of 3, the finders of 4 and the steps of 5 all run together; with findAll each step of 4 and 5 runs its items side by side. The first read of the original, which nothing runs beside, splits itself: the conversion to gray and the hybrid matrix in bands of rows, the matrix detectors of both polarities, and the row readers on a bitmap of their own beside the readers of the matrix. Within a step, pieces that waited on each other run side by side too: the directions of the Data Matrix trace, the signs the variants are gated on, the bands and first crops of the locators, the spans of the fits, the deblurred copies of a crop and the three looks of the QR trace. Aggressive mode's own steps, from the band crops to the localizer, run as jobs that log their attempts; the calling thread replays the logs in order, counting each attempt as one after another would. A reader is stateless and can be shared between threads.

### Stages

- **Binarization.** `HybridBinarizer` and `GlobalHistogramBinarizer` are ported from ZXing bit for bit, on ZXing's luminance `(306 R + 601 G + 117 B + 512) >> 10`. A row is kept with its runs for the whole attempt, so every linear reader shares one binarization, as `ODReader` does in zxing-cpp.
- **Matrix finders.** The QR, Micro QR, rMQR, Aztec and Data Matrix detectors of zxing-cpp. Data Matrix with several data regions samples each region with its own transform between the alignment crossings it finds. A QR that no regular grid decodes is sampled once more on a bent grid fitted to the module edges (a symbol on a can). The finders do not depend on the angle, so they do not run on rotated images. When `qr-trace` finds no finders, it looks again at the median of the frame, on a frame that shows a finder ring, and then `QrPure` reads an upright symbol that fills the image from the outline of its dark pixels, sampling a grid for each model-2 size and decoding the grids that show the timing patterns.
- **Linear scanners.** zxing-cpp's row readers. When no whole-pixel row reads, Code 128, Code 39 and EAN/UPC scan the rows again with edges in quarter pixels, for modules of one to two pixels. As in zxing-cpp, a row is tried for EAN/UPC from every start guard, not only its first, and a whole-pixel row whose read is refused does not end the scan. A read made either way counts only as an EAN-13 or UPC-A, and when no other text reads on more rows of the bitmap, and a thresholded image is still read from the first guard of each row, the first row that decodes deciding.
- **Lines across a box.** Before a crop of `BarSegments` is read as an image, `ProfileLines` reads thirteen single lines across each box, sampled from the frame at one and a half samples a pixel: the flow of tapirscan's Low mode. `ProfileSampler` cuts a line into bars where its profile crosses the level half way between the peak and the valley on either side, then where it crosses the middle of a window that follows the lighting, then at one level for the whole line, so shading and low contrast move no edge and no variant of the whole frame is needed for them. `RunEan` reads EAN-13 and UPC-A from the runs after it has measured on the start guard how much wider the bars came out than the spaces, and taken that off every digit; Code 128 goes to the row decoder. A text counts when two lines agree on it at the same place. A box that showed a text on one line gets lines close to it, then all of them at twice the density; when no box read, the two strongest are read again from a sharpened profile, one without its slow shading, one with a ghost of camera shake removed and one sharpened hard, where a text needs four lines. A read on three lines or more counts at once; one on two lines only when the lines of `EanCrossCheck` repeat it, else it waits for the crops, and with `findAll` it is dropped. The lines read neither the short retail codes, which have too few digits for two lines to confirm, nor a supplement.
- **Rows of the retail engine.** Where the lines read nothing, `RetailEngine` reads the same boxes for EAN-13 and UPC-A as the primary pass of tapirscan's Medium mode does. A row is a line of frame pixels at one sample a pixel, set between its 5th and 95th percentile, once for the whole row and once for its part inside the box, and cut into runs every way `SignalModels` knows: at the middle level, without clusters of specks beside an edge, with levels that follow the row, with edges between samples, between each peak and valley, and for a row with too few runs at a lower and a higher level. `RunWindows` reads every window of 61 runs between quiet zones with `EanRuns`, which scores all twelve digits by the squared distance of their runs and takes the first digit from the mix of odd and even digits that costs least, also on widths with the widening of the bars that the guards show taken out; the checksum only refuses. Where the runs read nothing, `EanModules` reads the darkness of the 95 modules against sharp digits and against digits blurred by 0.45, 0.65 and 0.85 of a module. Two readings of one stretch that differ refuse each other, a window that reads clearly and fails its checksum refuses a weaker one that passes, and what was refused stops a track from passing over it. Rows that agree are linked into a track where the frame shows the bars unbroken from one to the next, and a track counts with two rows, or four where the quiet zones are short or only the modules read, that lie a pixel apart when a reading is clear and a module of the symbol otherwise. The engine spends its rows by what the first ones show: three rows of 512 samples and five at a sample a pixel for every box; then none where four rows agree, 64 for a box whose rows showed no guards and 512 for one that showed them, as whole rows spread over the box and as windows of 512 pixels along it; a box with fewer than 48 runs on any of eight lines gets none. Last come rows one and two pixels beside each reading. A row that cuts a symbol off and shows half of it is read again made longer. When no box read, each box whose rows showed guards is read again a little longer and higher and turned by 1.5 and 3 degrees either way, and a symbol read there counts unless lines of the frame across it read another text (`SourceCheck`). Beyond tapirscan, a symbol counts only with a row that read it clearly from its runs, at a cost of 0.08 at most with every digit 0.08 ahead, or with pixels that follow the modules of its text (`SourceCheck.agrees`): bars doubled by camera shake are cut wrongly the same way on every row and read as another text on dozens of rows, each at a cost near the limit. A read on three rows or more counts at once, one on two rows as a read of two lines does. The engine reads the boxes along their bars only, and not the tiny symbols Medium fits from all the pixels of a box.
- **After the rows.** Where the rows read nothing either, three of Medium's later steps follow. `ProfileLines.recover` looks at the lines of the boxes again: the two boxes whose rows showed most guards from a profile sharpened a little at one sample a pixel; the first eight boxes without their slow shading, and when that reads nothing the boxes with any sign of a symbol with a ghost removed; then the strongest sampled twice as finely and sharpened with a blur of nine samples, and from bands, the mean of three lines a pixel and a half apart. A text needs three lines, four unless three read it before the profile was restored, and the frame's own pixels (`SourceCheck`): no lines across it that read another text, and pixels that follow its modules unless five lines agree; the wide and band profiles give one text only, on four lines, with pixels that follow it. Then `RetailEngine.restored` reads the two boxes with most guards, four at least, on a crop sharpened as `(5 * gray - 3 * blur + 1) / 2` with a blur of five pixels (`CropSharpen`), and the first of them with a blur of nine; a symbol needs five rows there and from the wider blur pixels that follow it. Late in the ladder, where the row decoder read nothing on the three copies of `Deblur` of the strongest box, the rows of `RetailEngine` read them: a text on four rows counts when the pixels of the frame follow its modules, no lines of the frame read another text, and no copy reads another text. The copies of the other boxes are left to the row decoder: the rows cost most on a frame without a symbol.
- **Strips of bars.** `BarStrips` finds patches of parallel bars from the structure tensor of 16-pixel blocks and samples each with its bars vertical; `BarSegments` votes parallel edge segments into one box a symbol, after the original attempts miss; `LinearBand` searches whole bands over angles, in aggressive mode and for postal codes. All these crops are read by the linear readers only, so a crop never assembles a false PDF417 or matrix symbol.
- **What the variants read.** The linear readers of the whole-frame variants read only the rows that cross a box of `BarSegments` or `BarStrips`, widened by a tenth, or run along an edge of the frame, where the locators miss a symbol the edge cuts; `BinaryBitmap.restrictRows` makes the other rows look flat to them, and the black matrix keeps every row. A variant decodes QR Code, Micro QR and rMQR only when the frame shows a finder ring, Aztec only when it shows a bullseye, and PDF417 only when a start or stop pattern of the frame lies in one of those boxes; the two locators and these signs are found side by side. The four finder traces of a frame share one matrix of `TraceMatrix`, the fits share the profiles of each patch (`StripBands`), and a turned variant fills only the rows its readers read. None of this changes a read.
- **EAN cross-check.** `HitCollector` checks every EAN-13 and UPC-A with a scan line against the unmodified image (`EanCrossCheck`): eleven lines beside the read are decoded again, and a read none of them repeats takes the one other text two or more of them agree on, or is dropped when they disagree. A threshold or a ghost can turn a left half into other digits; the lines beside it on the original pixels rarely all do.
- **EAN halves.** When fast mode read nothing at all, `EanHalves` looks on every row of the image, its halved frame and the image turned by 90° for a left half (guard, six digits, middle guard) and a right half (middle guard, six digits, guard) of an EAN-13, and joins halves of different rows whose middle guards and modules agree and whose 13 digits check. Glare or a fold that spoils every row in one place rarely spoils the same half. Each half has to read on four rows, and repeating bars, which read as halves of one digit, are refused.
- **Deblurred crops.** When the ladder read nothing, in either mode before the fits, `Deblur` resamples each blurred crop of `BarSegments` to about three pixels a module and sharpens it along the bars three ways: a motion ghost removed behind and ahead, and a Van Cittert deconvolution of the defocus. Only EAN-13 and UPC-A are read there, and a text counts only when no copy reads another one and the copies, the row count or `EanFit` confirm it.
- **Gray-profile fits.** Under about 1.3 pixels a module no threshold separates the bars, but the averaged gray profile still matches the blurred patterns. `Code128Fit` and `EanFit` fit every symbol or digit position by least squares, then search for the cheapest sequence with a right check, symbol by symbol shifted where it fits best. They run last, only when nothing was read, and take no attempt slot. `EanFit` reads the two strongest strips of bars, four with `findAll`, and neither the fits nor the EAN halves run on a frame where `BarSegments` found no crop.
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
