# Changelog

The release workflow takes the section of the tagged version as the notes of its GitHub release.

## 0.1.0

The first release. Java 17 or later, no dependencies at run time; on the module path the module is `io.github.kamilkrzywanski.barcode`, and only its package of that name is exported.

### Reading

- QR Code, Micro QR, Data Matrix, Aztec, PDF417, Code 128, Code 39, Code 93, EAN-13, EAN-8, UPC-A and UPC-E with their 2- and 5-digit add-ons, ITF, Codabar, and GS1 DataBar, DataBar Limited and DataBar Expanded, from photos and scans. MaxiCode, Telepen, DX film edge, rMQR, MicroPDF417, Royal Mail (RM4SCC) and Japan Post are experimental and read only when asked for.
- The detectors and bit decoders are a Java port of the read path of zxing-cpp 3.1.1; the binarizers come from ZXing 3.5.4, and every decoder corrects errors and erasures with the Reed-Solomon decoder of zxing-cpp's librscpp.
- `BarcodeReader` tries a ladder of image variants until one decodes: `ReadOptions.fast()` by default, `ReadOptions.aggressive()` for hard images, with `findAll`, a choice of symbologies, attempt and time budgets, and a parallel, a custom-executor or a sequential reader. `BarcodeHit` gives the text, the bytes, the corners, the orientation and the attempt that read it.
- On the zxing-cpp sample images fast mode reads 460 of 464 symbols, where Java ZXing with `TRY_HARDER` reads 337; on the Arte-Lab EAN-13 photos fast reads 291 and aggressive 389 of 430, ZXing 205; on BarBeR, without its Arte-Lab and KAIST parts, fast reads 3,857 and aggressive 3,920 of 5,176 codes, ZXing 2,484. The README has the full tables and how to repeat them.

### Writing

- `BarcodeWriter` writes every symbology of the ZXing writers and of Barcode-Lib4J 3.2.0: QR Code and GS1 QR Code, Data Matrix and GS1 Data Matrix, PDF417, Aztec, Code 128 with its code sets, GS1-128, EAN-14, SSCC-18, Code 39 and its extended form, PZN and PZN8, Code 93 and its extended form, Codabar, Code 11, ITF, EAN-13, EAN-8, UPC-A, UPC-E with add-ons, ISBN-13 and ISMN. GS1 element strings are checked against their application identifiers.
- For the same choices every module is the one ZXing or Barcode-Lib4J writes. Two bugs of ZXing's minimal Data Matrix encoder are fixed: the 12×36 rectangle sized as 33 codewords, and data ending in EDIFACT without the unlatch.
- `QrCode` writes QR Code with every setting of its own: level, version, mask, character set, GS1 and compact segments.
- A `Barcode` draws itself as an image, an SVG, a one-page vector PDF without any library, or on any `Graphics2D`. Linear symbols get their human-readable line, EAN and UPC laid out as on a retail label; `RenderOptions` sets the bar height, the font, or no line.

### Command line

- `java -jar barleader.jar image...` prints one line or one JSON object per symbol; `write --type=...` and `qr` write a PNG, an SVG or a PDF.

### Known limitations

- MaxiCode is read only as a clean symbol that fills the image, without a detector for photos; MaxiCode, Telepen and DX film edge return the corners of the whole image. QR Model 1, Han Xin and DotCode are not read, and ECI, structured-append and symbology-identifier metadata are not in `BarcodeHit` yet.
- The human-readable line needs a font from AWT, which a headless JDK without fonts does not have; `RenderOptions.humanReadable(false)` draws without it.
- The API may still change before 1.0.
