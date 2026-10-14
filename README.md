# barcode-pro

A barcode reader in pure Java, with no JNI, no OpenCV and no native libraries. Apache License 2.0.

barcode-pro reads QR Code, Micro QR, Data Matrix, Aztec, PDF417, Code 128, Code 39, Code 93, EAN-13, EAN-8, UPC-A, UPC-E, ITF, Codabar and the GS1 DataBar family from photos and scans. The detectors and bit decoders are a Java port of the read path of [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) 3.1.1. Around them sits a ladder of image variants (inversion, contrast, illumination, thresholds, rotations, scales, a finder for tilted linear symbols) that is tried until one of them decodes. On hard images it reads far more than the usual Java ZXing call, and on clean images it stops after the first attempt.

**Status: young.** The API is small and may still change before 1.0. [docs/PLAN.md](docs/PLAN.md) (in Polish) is the design document with the full attempt ladder and the measurements.

## Getting started

```xml
<dependency>
    <groupId>io.github.kamilkrzywanski</groupId>
    <artifactId>barcode-pro</artifactId>
    <version>0.1.0</version>
</dependency>
```

The first release is not on Maven Central yet. Until then, `mvn install` and depend on `0.1.0-SNAPSHOT`.

```java
BarcodeReader reader = new BarcodeReader();

for (BarcodeHit hit : reader.read(ImageIO.read(new File("label.jpg")))) {
    System.out.println(hit.symbology() + " " + hit.text());
}
```

`read` never throws for an image without a barcode. It returns an empty list. One `BarcodeReader` can be shared by any number of threads.

Java 17 or later. The only runtime dependency is ZXing `core` 3.5.4, for its binarizers, bit matrices and Reed-Solomon decoders. On the module path the module is `io.github.kamilkrzywanski.barcode`. Only the package `io.github.kamilkrzywanski.barcode` is exported; everything else is internal.

## Options

```java
ReadOptions options = ReadOptions.aggressive()          // the full ladder, up to 8 s and 100 attempts
        .findAll(true)                                  // every symbol, not only the first
        .symbologies(Symbology.QR_CODE, Symbology.EAN_13);

List<BarcodeHit> hits = reader.read(image, options);
```

| | `ReadOptions.fast()` (default) | `ReadOptions.aggressive()` |
| --- | --- | --- |
| Ladder | original image, inversion, contrast stretch, illumination, 90/180/270°, mean and Otsu thresholds, 2× scale, QR/Micro QR/Aztec/Data Matrix finders | everything in fast, then ±15° and ±30°, a search for tilted linear symbols, a pyramid, tiles, Sauvola, Gaussian threshold, scales, morphology and quadrilateral candidates |
| Budget | 2.5 s, 40 attempts | 8 s, 100 attempts |

A clean symbol is read on the first attempt in either mode, so `aggressive()` only costs more on images that need it.

- **Symbologies.** `Symbology.defaults()` is QR Code, Micro QR, Data Matrix, Aztec, PDF417, Code 128, Code 39, EAN-13, EAN-8, UPC-A and UPC-E. Code 93, ITF, Codabar, DataBar, DataBar Limited and DataBar Expanded are read when you ask for them. The DataBar readers scan every row of every attempt: on 980 images without DataBar they added no false read and about a sixth more CPU in fast mode. Fewer symbologies make a read faster: without any 2D symbology the matrix detectors do not run at all.
- **EAN/UPC supplements.** `eanAddOn(EanAddOn.READ)` also reads the 2- or 5-digit add-on printed to the right of an EAN or UPC symbol, such as a book price; `EanAddOn.REQUIRE` returns such a symbol only together with its add-on. The default, as in zxing-cpp, is `IGNORE`.
- **Budgets.** `maxAttempts(int)` and `timeBudgetMillis(long)`. The time budget makes the result depend on how fast and how busy the machine is. `timeBudgetMillis(Long.MAX_VALUE)` leaves only the attempt budget, and the same image then gives the same hits on every machine.
- **Threads.** `new BarcodeReader()` runs the independent variants of one read in parallel on a shared pool of daemon threads. `new BarcodeReader(executor)` uses your executor instead; a read called from one of its threads cannot deadlock, because the calling thread also runs every variant the executor has not started. `BarcodeReader.sequential()` does everything on the calling thread and stops at the first hit, which uses the least CPU when you already read many images in parallel. All three return the same hits.

## Input

- `read(BufferedImage)`: any image ImageIO or Java 2D produces. Common rasters (`INT_RGB`, `INT_ARGB`, `3BYTE_BGR`, `4BYTE_ABGR`, `BYTE_GRAY`) are read row by row; anything else goes through `getRGB`.
- `read(byte[] luminance, int width, int height, ReadOptions)` and the variant with `offset` and `rowStride`: 8-bit luminance, 0 black to 255 white, such as the Y plane of a camera frame. The array is copied.

## What you get

`BarcodeHit` is a record:

| Field | Meaning |
| --- | --- |
| `text` | The decoded text. Byte content without an ECI is UTF-8 when it is valid UTF-8; QR Code and Micro QR can also be Shift_JIS, as in zxing-cpp; otherwise ISO-8859-1. GS1 symbols return the element string without parentheses. UPC-A has 12 digits and UPC-E 8. |
| `symbology` | One of `Symbology`. |
| `corners` | The symbol outline in source-image pixels, also after rotation and scaling. Linear symbols give the two ends of the scan line; stacked DataBar gives the four corners of its rows. |
| `orientationDegrees` | Clockwise degrees with the Y axis down, or `-1` when unknown. |
| `bytes` | The content as the symbol stores it, before any character set is applied: for QR Code, Micro QR, Data Matrix, Aztec and PDF417 the data bytes without ECI designators, so binary payloads come back intact; for the other symbologies the characters of the text. |
| `addOn` | The EAN/UPC supplement when `eanAddOn` asked for it and one was read, else empty. |
| `confidence` | A heuristic in `[0, 1]` from the order of the attempt that read the symbol. It is not a calibrated probability. |
| `attempt` | The name of that attempt, such as `original-hybrid` or `rotate--15.0`. For diagnostics only; names may change between versions. |

## Accuracy

All numbers come from this repository and its `datasets` submodule, [barcode-pro-datasets](https://github.com/kamilkrzywanski/barcode-pro-datasets) (`git submodule update --init datasets`), and can be repeated with the commands in [docs/PLAN.md](docs/PLAN.md). "ZXing" is the call most Java code makes: `MultiFormatReader` with `HybridBinarizer`, `BufferedImageLuminanceSource` and `TRY_HARDER`.

**zxing-cpp 3.1.1 sample images.** The 464 labelled images of the zxing-cpp test suite that ImageIO can open and whose symbology both libraries read by default:

| Symbology | Images | ZXing | fast | aggressive |
| --- | ---: | ---: | ---: | ---: |
| QR Code | 118 | 87 | 116 | 116 |
| Micro QR | 15 | 0 | 15 | 15 |
| Data Matrix | 79 | 41 | 77 | 77 |
| Aztec | 47 | 20 | 47 | 47 |
| PDF417 | 56 | 52 | 56 | 56 |
| Code 128 | 22 | 21 | 22 | 22 |
| Code 39 | 14 | 10 | 13 | 13 |
| EAN-13 | 37 | 34 | 37 | 37 |
| EAN-8 | 9 | 8 | 8 | 8 |
| UPC-A | 47 | 44 | 47 | 47 |
| UPC-E | 20 | 20 | 20 | 20 |
| **Total** | **464** | **337** | **458** | **458** |

When they are asked for, the opt-in symbologies read all of their samples: 245 of 245 DataBar, DataBar Limited and DataBar Expanded images, 18 of 18 ITF, 15 of 15 Codabar (14 in fast) and 3 of 3 Code 93. With `eanAddOn(EanAddOn.READ)` all 10 EAN-13 and UPC-A samples that carry a supplement come back with it. Across all 795 images, including the three of zxing-cpp's false-positive set, one text is wrong: an Italian Code 32 pharmacode returned as its raw Code 39 text.

**Arte-Lab Medium Barcode 1D.** 430 phone photos of EAN-13 labels, half of them taken without autofocus ([Zamberletti et al., VISAPP 2010](https://doi.org/10.5220/0002811600050011), CC BY 3.0):

| | ZXing | fast | aggressive |
| --- | ---: | ---: | ---: |
| Labels read | 205 | 243 | 378 |
| Wrong texts | 9 | 5 | 5 |

The aggressive ladder was tuned on these photos, so treat 378 as what the ladder can reach on this kind of image, not as a measurement on unseen data.

**Public sets outside the repository.** `scripts/fetch-datasets.sh` and the snapshot run in [Measuring](#measuring) give these numbers, all with the default symbologies and no time budget. ZXing, fast and aggressive return one symbol an image; only `findAll` looks for the rest, which is what counts on images with many codes.

| Set | Labelled | ZXing | fast | aggressive | fast, `findAll` |
| --- | ---: | ---: | ---: | ---: | ---: |
| KAIST DEAL test split, EAN-13 photos (wrong texts) | 2000 | 1224 (73) | 1437 (38) | 1576 (46) | 1459 (18) |
| KAIST DEAL EAN-8 photos | 90 | 70 | 80 | 88 | 81 |
| BoofCV QR v3, QR codes found by place | 1232 | 149 | 410 | 429 | 1005 |
| BarBeR without its Arte-Lab and KAIST parts, codes read | 5176 | 2450 | 3060 | 3348 | 3290 |

On KAIST's 788 photos without a barcode, ZXing reads something on 7, fast on none and aggressive on one. BarBeR counts codes ZXing knows but barcode-pro reads only when asked, such as the Interleaved 2 of 5 of its SKUU DSP Lab part.

**Speed.** A clean symbol is read on the first attempt in a few milliseconds. On 70 Arte-Lab photos (1152×864 to 2976×2232 pixels, most of them hard), fast mode took 0.12 s per photo and 0.32 s of CPU spread over the cores of a 12-thread laptop; aggressive mode took 0.41 s and 1.5 s of CPU. Numbers depend on the machine; the relative cost of the modes does not.

**Robustness.** 1,500 random, degenerate (from 1×1 pixel to 3 megapixels) and damaged images in every mode raise no exception.

## What it does not read yet

- MaxiCode, Telepen and DX film edge. Their decoders are ported and tested, but there is no photo detector for them in the ladder yet.
- rMQR, QR Model 1, Han Xin, DotCode and Code 32 as an Italian pharmacode (it reads as its Code 39 text).
- ECI, structured-append and symbology-identifier metadata are not exposed in `BarcodeHit` yet.

## Command line

```bash
mvn -DskipTests package
java -jar target/barcode-pro.jar label.jpg
java -jar target/barcode-pro.jar --aggressive --all --formats=QR_CODE,EAN_13 --addon=read --json scans/*.png
```

Plain output is one tab-separated line per symbol: file, symbology, text, attempt, orientation, corners and the EAN/UPC supplement (`-` when there is none). Tabs and line breaks in the text are escaped. `--json` prints one JSON object per symbol. The exit code is 0 when every image had a symbol, 1 when one had none, and 2 for wrong usage or an unreadable file.

## Building

```bash
mvn verify
```

This needs JDK 17 or later and Maven 3.9. CI builds on JDK 17, 21 and 25: [.github/workflows/verify.yml](.github/workflows/verify.yml). The default build skips the Arte-Lab photos; `mvn -Partelab test` reads the named frames whose text is pinned.

## Measuring

`mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot.tsv"` reads every image of the sets in the repository and its `datasets` submodule with plain ZXing and in each mode, without a time budget, and writes every read plus a scored summary (`target/snapshot.tsv.summary.md`). Two runs of the same code give the same bytes, so a `diff` between runs before and after a change lists exactly the reads it moved. `scripts/fetch-datasets.sh` downloads more public sets that need no account (KAIST DEAL, the ZXing test images, BoofCV QR v3) to `~/.cache/barcode-pro/datasets`, checking their SHA-256, and unpacks BarBeR from `BARBER_ZIP=<archive>` (it needs an account, so it is not downloaded); `--external <that dir>` adds them to the run.

## Packages

| Package | Role |
| --- | --- |
| `io.github.kamilkrzywanski.barcode` | The API: `BarcodeReader`, `ReadOptions`, `BarcodeHit`, `Symbology`. Also the attempt ladder (`Scan`) and the tilted-band finder. |
| `….image` | Luminance, rotations, scales, homographies. |
| `….preprocess` | Thresholds and morphology: Otsu, local mean, Gaussian, Sauvola, erosion, dilation. |
| `….decode` | Linear readers, PDF417, MaxiCode, DataBar, text decoding. |
| `….locate` | QR, Micro QR, Aztec and Data Matrix detectors and bit decoders. |
| `….cli` | The command line. |

## Principles

- Detection and decoding follow zxing-cpp 3.1.1. Every ported class names its source file, and [NOTICE](NOTICE) lists them.
- Writing barcodes is out of scope. Use ZXing or zxing-cpp for that.
- A false read is worse than no read. A linear symbol has to repeat on a parallel strip; a full EAN-13 or UPC-A may instead repeat on any other row, as zxing-cpp requires, except on shrunken images and band crops. Variants that only make sense for matrix codes do not decode linear ones.

## License

Apache License 2.0, see [LICENSE](LICENSE). The ported zxing-cpp code keeps its authors' copyright, see [NOTICE](NOTICE). The Arte-Lab photos in the `datasets` submodule and the eight of them in `src/test/resources/artelab` are CC BY 3.0, and the zxing-cpp samples Apache 2.0.
