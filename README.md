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

- **Symbologies.** `Symbology.defaults()` is QR Code, Micro QR, Data Matrix, Aztec, PDF417, Code 128, Code 39, EAN-13, EAN-8, UPC-A and UPC-E. Code 93, ITF, Codabar, DataBar, DataBar Limited and DataBar Expanded are read when you ask for them. The DataBar readers scan every row of every attempt: on 980 images without DataBar they added no false read and about a fifth more CPU in fast mode. Fewer symbologies make a read faster: without any 2D symbology the matrix detectors do not run at all.
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
| `corners` | The symbol outline in source-image pixels, also after rotation and scaling. Linear symbols give the two ends of the scan line. DataBar gives none. |
| `orientationDegrees` | Clockwise degrees with the Y axis down, or `-1` when unknown. |
| `confidence` | A heuristic in `[0, 1]` from the order of the attempt that read the symbol. It is not a calibrated probability. |
| `attempt` | The name of that attempt, such as `original-hybrid` or `rotate--15.0`. For diagnostics only; names may change between versions. |

## Accuracy

All numbers come from this repository and can be repeated with the commands in [docs/PLAN.md](docs/PLAN.md). "ZXing" is the call most Java code makes: `MultiFormatReader` with `HybridBinarizer`, `BufferedImageLuminanceSource` and `TRY_HARDER`.

**zxing-cpp 3.1.1 sample images.** The 464 labelled images of the zxing-cpp test suite that ImageIO can open and whose symbology both libraries read by default:

| Symbology | Images | ZXing | fast | aggressive |
| --- | ---: | ---: | ---: | ---: |
| QR Code | 118 | 87 | 116 | 116 |
| Micro QR | 15 | 0 | 15 | 15 |
| Data Matrix | 79 | 41 | 77 | 77 |
| Aztec | 47 | 20 | 47 | 47 |
| PDF417 | 56 | 52 | 56 | 56 |
| Code 128 | 22 | 21 | 21 | 22 |
| Code 39 | 14 | 10 | 13 | 13 |
| EAN-13 | 37 | 34 | 33 | 33 |
| EAN-8 | 9 | 8 | 8 | 8 |
| UPC-A | 47 | 44 | 36 | 47 |
| UPC-E | 20 | 20 | 20 | 20 |
| **Total** | **464** | **337** | **442** | **454** |

When they are asked for, the opt-in symbologies read all of their samples: 245 of 245 DataBar, DataBar Limited and DataBar Expanded images, 18 of 18 ITF, 15 of 15 Codabar (14 in fast) and 3 of 3 Code 93. Across all 795 images, including the three of zxing-cpp's false-positive set, one text is wrong: an Italian Code 32 pharmacode returned as its raw Code 39 text.

**Arte-Lab Medium Barcode 1D.** 430 phone photos of EAN-13 labels, half of them taken without autofocus ([Zamberletti et al., VISAPP 2010](https://doi.org/10.5220/0002811600050011), CC BY 3.0):

| | ZXing | fast | aggressive |
| --- | ---: | ---: | ---: |
| Labels read | 205 | 235 | 378 |
| Wrong texts | 9 | 5 | 5 |

The aggressive ladder was tuned on these photos, so treat 378 as what the ladder can reach on this kind of image, not as a measurement on unseen data.

**Speed.** A clean symbol is read on the first attempt in a few milliseconds. On 70 Arte-Lab photos (1152×864 to 2976×2232 pixels, most of them hard), fast mode took 0.21 s per photo and 0.69 s of CPU spread over the cores of a 12-thread laptop. On the same photos aggressive mode used 2.3 times the CPU of fast mode. Numbers depend on the machine; the relative cost of the modes does not.

**Robustness.** 1,500 random, degenerate (from 1×1 pixel to 3 megapixels) and damaged images in every mode raise no exception.

## What it does not read yet

- EAN/UPC add-ons (the 2- and 5-digit supplements): the main symbol is read, the add-on is not.
- MaxiCode, Telepen and DX film edge. Their decoders are ported and tested, but there is no photo detector for them in the ladder yet.
- rMQR, QR Model 1, Han Xin, DotCode and Code 32 as an Italian pharmacode (it reads as its Code 39 text).
- Raw bytes, ECI and structured-append metadata are not exposed in `BarcodeHit` yet; binary content comes back as ISO-8859-1 text.

## Command line

```bash
mvn -DskipTests package
java -jar target/barcode-pro.jar label.jpg
java -jar target/barcode-pro.jar --aggressive --all --formats=QR_CODE,EAN_13 --json scans/*.png
```

Plain output is one tab-separated line per symbol: file, symbology, text, attempt, orientation and corners. Tabs and line breaks in the text are escaped. `--json` prints one JSON object per symbol. The exit code is 0 when every image had a symbol, 1 when one had none, and 2 for wrong usage or an unreadable file.

## Building

```bash
mvn verify
```

This needs JDK 17 or later and Maven 3.9. CI builds on JDK 17, 21 and 25: [.github/workflows/verify.yml](.github/workflows/verify.yml). The default build skips the Arte-Lab photos; `mvn -Partelab test` reads the named frames whose text is pinned.

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
- A false read is worse than no read. Linear symbols are confirmed on a parallel strip, and variants that only make sense for matrix codes do not decode linear ones.

## License

Apache License 2.0, see [LICENSE](LICENSE). The ported zxing-cpp code keeps its authors' copyright, see [NOTICE](NOTICE). The Arte-Lab photos in `datasets/` are CC BY 3.0 and the zxing-cpp samples Apache 2.0.
