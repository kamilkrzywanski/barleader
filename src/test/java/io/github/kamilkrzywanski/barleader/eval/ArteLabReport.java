package io.github.kamilkrzywanski.barleader.eval;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.BarcodeReader;
import io.github.kamilkrzywanski.barleader.ReadOptions;
import io.github.kamilkrzywanski.barleader.eval.ArteLabScoring.Frame;
import io.github.kamilkrzywanski.barleader.eval.ArteLabScoring.Kind;
import io.github.kamilkrzywanski.barleader.eval.ArteLabScoring.Tally;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One pass over Arte-Lab Medium Barcode 1D. Not a unit test.
 *
 * <p>Aggressive is not run when FAST already returned a labeled hit. That ladder
 * is a prefix of aggressive and stops on the same hit. The TSV then marks the
 * aggressive column {@code skipped}.
 *
 * <p>Usage, from the repository root:
 * {@code mvn -q -Partelab-eval test-compile exec:java -Dexec.args="--out target/artelab-report.tsv"}
 */
public final class ArteLabReport {
    private static final String HEADER = "file\tsize\texpected\tbaseline\tfast\tfastMs\tfastClass"
            + "\taggressive\taggressiveMs\taggressiveClass";

    private final BarcodeReader reader = new BarcodeReader();
    private final Path root;
    private final boolean fastOnly;
    private final List<String> only;

    private ArteLabReport(Path root, boolean fastOnly, List<String> only) {
        this.root = root;
        this.fastOnly = fastOnly;
        this.only = only;
    }

    public static void main(String[] args) throws IOException {
        int status = execute(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int execute(String[] args) throws IOException {
        Path root = ArteLabScoring.ROOT;
        Path out = Path.of("target", "artelab-report.tsv");
        boolean fastOnly = false;
        List<String> only = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--root") && i + 1 < args.length) {
                root = Path.of(args[++i]);
            } else if (arg.equals("--out") && i + 1 < args.length) {
                out = Path.of(args[++i]);
            } else if (arg.equals("--only") && i + 1 < args.length) {
                only.add(args[++i]);
            } else if (arg.equals("--fast-only")) {
                fastOnly = true;
            } else if (arg.equals("-h") || arg.equals("--help")) {
                System.err.println("Usage: ArteLabReport [--root dir] [--out file] [--only substring]... [--fast-only]");
                return 2;
            } else {
                System.err.println("Unknown argument: " + arg);
                return 2;
            }
        }
        return new ArteLabReport(root, fastOnly, only).run(out);
    }

    private int run(Path out) throws IOException {
        if (!Files.isDirectory(root)) {
            System.err.println("Missing dataset: " + root.toAbsolutePath());
            return 1;
        }
        List<String> files = ArteLabScoring.relativeImages(root);
        if (!only.isEmpty()) {
            files = files.stream().filter(this::selected).toList();
        }
        if (files.isEmpty()) {
            System.err.println("No frames matched");
            return 2;
        }
        List<Frame> frames = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int errors = 0;
        for (String relative : files) {
            Row row = readFrame(relative);
            frames.add(row.frame());
            lines.add(row.tsv());
            if (row.frame().fast() == Kind.ERROR || row.frame().aggressive() == Kind.ERROR) {
                errors++;
            }
            System.err.println(relative
                    + "\t" + row.frame().fast().wire
                    + "\t" + row.frame().aggressive().wire
                    + "\t" + row.frame().fastMs()
                    + "\t" + row.frame().aggressiveMs());
        }
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, HEADER + "\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        printSummary(frames);
        int expected = only.isEmpty() ? 430 : files.size();
        if (frames.size() != expected || errors > 0) {
            System.out.println("DONE n=" + frames.size() + " errors=" + errors + " expected=" + expected);
            return 1;
        }
        System.out.println("DONE n=" + frames.size() + " errors=0" + (fastOnly ? " fast-only" : ""));
        return 0;
    }

    private boolean selected(String relative) {
        for (String needle : only) {
            if (relative.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Row readFrame(String relative) {
        String setName = relative.substring(0, relative.indexOf('/'));
        Path imagePath = root.resolve(relative);
        try {
            String label = ArteLabScoring.labelDigits(imagePath);
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                throw new IOException("ImageIO returned null");
            }
            String size = image.getWidth() + "x" + image.getHeight();
            Timed baseline = timeBaseline(image);
            Timed fast = timeRead(image, ReadOptions.fast());
            Kind fastKind = fast.kind(relative, label);
            boolean runAggressive = !fastOnly && !ArteLabScoring.labeledHit(fastKind);
            Timed aggressive = runAggressive ? timeRead(image, ReadOptions.aggressive()) : Timed.skipped();
            Kind aggressiveKind = runAggressive ? aggressive.kind(relative, label) : Kind.SKIPPED;
            Frame frame = new Frame(
                    relative,
                    setName,
                    baseline.kind(relative, label),
                    fastKind,
                    aggressiveKind,
                    runAggressive,
                    fast.millis(),
                    aggressive.millis()
            );
            String tsv = String.join("\t",
                    relative,
                    size,
                    label,
                    baseline.cell(),
                    fast.cell(),
                    Long.toString(fast.millis()),
                    fastKind.wire,
                    aggressive.cell(),
                    Long.toString(aggressive.millis()),
                    aggressiveKind.wire
            );
            return new Row(frame, tsv);
        } catch (RuntimeException | IOException exception) {
            Frame frame = new Frame(relative, setName, Kind.ERROR, Kind.ERROR, Kind.ERROR, false, 0, 0);
            String message = (exception.getClass().getSimpleName() + ": " + exception.getMessage())
                    .replace('\t', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ');
            String tsv = String.join("\t", relative, "", "", message, "", "0", "error", "", "0", "error");
            System.err.println(relative + " ERROR " + message);
            return new Row(frame, tsv);
        }
    }

    private Timed timeRead(BufferedImage image, ReadOptions options) {
        long start = System.nanoTime();
        List<BarcodeHit> hits = reader.read(image, options);
        long millis = (System.nanoTime() - start) / 1_000_000L;
        if (hits.isEmpty()) {
            return new Timed("", millis);
        }
        BarcodeHit hit = hits.get(0);
        return new Timed(
                ArteLabScoring.cell(hit.symbology().name(), hit.attempt(), hit.orientationDegrees(), hit.text()),
                hit.text(),
                millis
        );
    }

    /**
     * The phase-0 baseline: one {@code MultiFormatReader} decode, hybrid binarizer, {@code TRY_HARDER}.
     * Same call as {@code ZxingBaseline}, plus the format name for the report cell.
     */
    private static Timed timeBaseline(BufferedImage image) {
        long start = System.nanoTime();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            Result result = new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))),
                    hints
            );
            long millis = (System.nanoTime() - start) / 1_000_000L;
            if (result.getText() == null || result.getText().isBlank()) {
                return new Timed("", millis);
            }
            // ZXing format names are the Symbology names for every format both sides know.
            String name = result.getBarcodeFormat().name();
            return new Timed(
                    ArteLabScoring.cell(name, "zxing-baseline", -1, result.getText()),
                    result.getText(),
                    millis
            );
        } catch (NotFoundException exception) {
            return new Timed("", (System.nanoTime() - start) / 1_000_000L);
        }
    }

    private static void printSummary(List<Frame> frames) {
        System.out.println("set\tn\tbaselineHit\tfastHit\tunionHit\tempty\tallowed\tfp\terror\tmaxFastMs\tmaxAggressiveMs");
        for (Tally tally : ArteLabScoring.tally(frames)) {
            System.out.println(tally.setName()
                    + "\t" + tally.n()
                    + "\t" + tally.baselineHit()
                    + "\t" + tally.fastHit()
                    + "\t" + tally.unionHit()
                    + "\t" + tally.empty()
                    + "\t" + tally.allowed()
                    + "\t" + tally.fp()
                    + "\t" + tally.error()
                    + "\t" + tally.maxFastMs()
                    + "\t" + tally.maxAggressiveMs());
        }
    }

    private record Row(Frame frame, String tsv) {
    }

    private record Timed(String cell, String text, long millis) {
        Timed(String cell, long millis) {
            this(cell, "", millis);
        }

        static Timed skipped() {
            return new Timed("", 0);
        }

        Kind kind(String relative, String label) {
            return ArteLabScoring.classify(relative, label, text);
        }
    }
}
