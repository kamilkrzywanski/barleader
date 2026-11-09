# Changelog

The release workflow takes the section of the tagged version as the notes of its GitHub release.

## Unreleased

### Reading

- Fast mode halves a frame from a shorter side of 800 pixels instead of 1000, so the 1152×864 Arte-Lab photos get the halved attempt too: 321 labels read where 297 were, with the same 5 wrong texts. Aggressive mode keeps 1000.
- After a miss, fast mode locates linear symbols with `BarSegments`, a port of the segment voting of tapirscan 1.5.0, instead of the coarse linear-band search. With `findAll` a band read that lies on the scan line of another code already read is dropped.
- On a halved or quartered frame an EAN/UPC text is dropped when another text reads on more rows of the same bitmap, at whole or at quarter pixels. With `findAll` this removes 4 wrong texts on BarBeR, 2 on Arte-Lab and 1 on KAIST's multi-code photos, and no labelled read; aggressive mode is unchanged on all 16,382 snapshot images.
- When nothing was read, both modes sharpen the blurred crops of `BarSegments` three ways, after the deblurring of tapirscan 1.5.0: a motion ghost removed behind or ahead, and the defocus deconvolved, then read EAN-13 and UPC-A there. A text counts only when no copy reads another and the copies, their rows or `EanFit` confirm it. Fast mode reads 300 more images of the snapshot, 38 of them Arte-Lab photos (321 to 359), aggressive mode 53 more; nothing read before is lost and the negative sets stay clean. One of the new Arte-Lab reads, `Foto(839)`, counts as wrong: the label says 5322825005431, the digits printed under the bars are 7321925005431, which is what is read.
- A `BarSegments` box longer than 2,400 or taller than 1,200 pixels, a symbol close to the lens, is sampled more coarsely instead of dropped: fast mode reads 32 more EAN-13 of the snapshot, among them three sharp Arte-Lab photos it missed.
- In fast mode the contrast stretch, the illumination, the mean and Otsu thresholds and the halved frame are also read turned by 90 degrees, for the linear formats, after all other variants and without an attempt slot. The variants scanned rows only, so a vertical symbol that needed one of them was lost: of 930 Arte-Lab and KAIST photos turned by 90 degrees fast mode read 805, now 827. On the snapshot fast mode reads 57 more images, among them three more KAIST EAN-8, and nothing it read before; on photos without a barcode it takes about 20 ms more.
- When fast mode read nothing, not even with the fits, it joins EAN-13 and UPC-A from halves read on different rows of the image, its halved frame and the image turned by 90 degrees, after tapirscan's partial halves: glare or a fold that spoils every row in one place rarely spoils the same half. A text needs each half on four rows, and halves of five equal digits, which the bars of Royal Mail and of QR codes give, are refused. Fast mode reads 63 more images of the snapshot, 17 of them Arte-Lab photos (361 to 378), and no wrong text; on photos without a barcode it takes about 7 ms more.
- Every EAN-13 and UPC-A read is checked against the unmodified image, after tapirscan's source contradiction: eleven lines parallel to its scan line, shifted across the bars, are read again. When none of them repeats the read and two or more agree on another EAN-13, that text is returned instead; when they read several others, the read is dropped. On the snapshot this turned 14 wrong texts into their labels in fast mode and 14 in aggressive mode, mostly a wrong left half beside the right half of the real code, and with findAll it merged 47 such wrong second reads into the code beside them.
- A frame of 20 megapixels or more is read where it holds anything: blocks of 32 pixels whose luminance hardly changes are blank, and the box around the rest is read when it leaves out a quarter of the frame. A photo pasted on a blank 100-megapixel canvas was read whole, every variant thresholded the blank too, and fast mode ran out of time before it got to the photo: on 20 such canvases, rebuilt from the public tapirscan benchmark's photos and parameters, fast mode now reads 20 instead of 6, in 0.34 s instead of 2.7 s, and aggressive mode 20 instead of 16, in 0.34 s instead of 4.9 s.
- In fast mode a frame still that large is first read on a box-averaged copy of about eight megapixels, and whole when that reads nothing; with findAll it is read whole. An EAN-8 upsampled to 48 megapixels read on every reduced copy and on none of the full frame, whose modules outgrew the threshold windows. On the nine upsampled photos of 12 to 48 megapixels of the same benchmark fast mode reads 9 instead of 8, in 0.31 s instead of 0.74 s.
- An upright QR Code that fills its image reads without its finder patterns when the finder search finds none: the box around its dark pixels, leaving out bands dark from edge to edge, is sampled as a grid of 21, 25, ... modules in each quarter turn until the decoder's format and error correction accept one. BoofCV's pathological samples cut the corners off the finders, put a dot in their light rings, pierce their centres or run a dark band through the quiet zone; fast mode reads all 23 of them instead of 11, aggressive mode 23 instead of 12, and nothing else of the snapshot changes. The speed does not measurably change.
- When fast mode read nothing at all on a colour image, it reads the brightest and the darkest channel of each pixel as its very last step. Luma turns a red background into a dark gray under black bars and blue bars into a middling gray on white; the brightest channel keeps the first contrast, the darkest the second. Fast mode reads 8 more images of the snapshot, an EAN-13 on red among them, and no wrong text; on colour photos without a barcode it takes about 11 ms more. Earlier in the ladder the channels read a short Code 128 on parcels before the band crops and fits reached the labelled one.

## 0.1.1

### Fixed

- With `findAll`, a linear symbol read on two rows far apart, or along a slant through the bars another attempt read straight, came back twice: a tall Code 93 read at its bottom row and at its top, 173 px apart on a 272 px symbol. Two scan lines of the same text are now one symbol when they cross or nearly touch, or when the bars run unbroken from one to the other. Two symbols of the same text side by side or stacked stay two. On the public sets the README measures, fast mode with `findAll` returns 348 duplicates fewer on 331 photos; every other mode and every score is unchanged.

### Build

- Releases are published with central-publishing 0.12.0. Under Maven 3.10.0 the 0.10.0 plugin put `maven-metadata-local.xml` into the bundle, which Central rejects; 0.1.0 was published by hand.

## 0.1.0

The first release. Java 17 or later, no dependencies at run time; on the module path the module is `io.github.kamilkrzywanski.barleader`, and only its package of that name is exported.

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
