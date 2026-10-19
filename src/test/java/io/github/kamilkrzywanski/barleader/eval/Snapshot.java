package io.github.kamilkrzywanski.barleader.eval;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.BarcodeReader;
import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.ReadOptions;
import io.github.kamilkrzywanski.barleader.eval.Labels.Read;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * Every read on every image of the benchmark sets, in fast, aggressive and fast with findAll, without
 * a time budget, and plain ZXing beside them. The output has no timings, so two runs of the same code
 * give the same bytes on any machine, and {@code diff} between a run before and after a change lists
 * exactly the reads that changed: text, symbology, attempt, orientation, confidence and corners.
 * A summary next to it scores every set against its labels.
 *
 * <pre>
 * mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot.tsv"
 * scripts/fetch-datasets.sh
 * mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot.tsv --external ~/.cache/barleader/datasets"
 * mvn -q -Psnapshot test-compile exec:java -Dexec.args="--rescore target/snapshot.tsv --external ~/.cache/barleader/datasets"
 * </pre>
 *
 * <p>{@code --rescore} reads nothing: it takes the reads of an earlier run from its TSV and writes
 * the summary again against the labels as they are now, for a change to the scoring alone.
 *
 * <p>The sets in the repository are the Arte-Lab photos and the zxing-cpp samples. With
 * {@code --external} the sets {@code scripts/fetch-datasets.sh} unpacks there join them under
 * {@code external/}, and BarBeR too when its archive, which needs an account, was given to the script.
 *
 * <p>One TSV line per image and configuration: path, configuration, read count, then one
 * {@code symbology|text|attempt|orientation|confidence|corners} field per read. Control characters,
 * {@code |} and {@code \} in the text are escaped as {@code \xNN}. The summary is
 * {@code <out>.summary.md}.
 */
public final class Snapshot {
    private static final String[] ROOTS = {
            "datasets/artelab-medium-barcode-1d",
            "datasets/zxing-cpp-3.1.1",
            "src/test/resources"
    };
    private static final String[] EXTERNAL = {"kaist-deal", "zxing-blackbox", "boofcv-qr", "barber"};
    static final String[] CONFIGS = {"zxing", "fast", "aggr", "fast-all"};
    /** The symbology of the read that stands for an exception thrown by the reader. */
    static final String ERROR = "ERROR";

    private Snapshot() {
    }

    /** One image: where it is, and the path the TSV prints for it. */
    record Source(Path file, String name) {
    }

    /** The reads of one image in every configuration, in {@link #CONFIGS} order. */
    record Outcome(Source source, Map<String, List<Read>> reads, boolean unreadable) {
    }

    public static void main(String[] args) throws Exception {
        Path out = null;
        Path external = null;
        boolean rescore = false;
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--rescore")) {
                rescore = true;
            } else if (args[i].equals("--external") && i + 1 < args.length) {
                // quoted, or the backslashes of a Windows home are read as escapes and dropped
                external = Path.of(args[++i].replaceFirst("^~", Matcher.quoteReplacement(System.getProperty("user.home"))));
            } else if (args[i].equals("--threads") && i + 1 < args.length) {
                threads = Integer.parseInt(args[++i]);
            } else if (out == null) {
                out = Path.of(args[i]);
            } else {
                threads = Integer.parseInt(args[i]);
            }
        }
        if (out == null) {
            System.err.println("Usage: Snapshot [--rescore] <out.tsv> [--external <datasets dir>] [--threads n]");
            System.exit(2);
        }
        if (external != null && !Files.isDirectory(external)) {
            // a missing directory used to drop every external set without a word
            System.err.println("No datasets directory at " + external.toAbsolutePath());
            System.exit(2);
        }
        if (rescore) {
            List<Outcome> outcomes = parse(out, external);
            String report = SnapshotReport.render(outcomes);
            Files.writeString(out.resolveSibling(out.getFileName() + ".summary.md"), report, StandardCharsets.UTF_8);
            System.out.print(report);
            return;
        }
        List<Source> sources = sources(external);
        long start = System.nanoTime();
        List<Outcome> outcomes = new ArrayList<>();
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Source source : sources) {
                futures.add(callers.submit(() -> read(source)));
            }
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                for (Future<Outcome> future : futures) {
                    Outcome outcome = future.get();
                    outcomes.add(outcome);
                    writer.print(lines(outcome));
                }
            }
        } finally {
            callers.shutdownNow();
        }
        Path summary = out.resolveSibling(out.getFileName() + ".summary.md");
        String report = SnapshotReport.render(outcomes);
        Files.writeString(summary, report, StandardCharsets.UTF_8);
        System.out.print(report);
        System.err.printf(Locale.ROOT, "%d images in %d s -> %s, %s%n",
                sources.size(), (System.nanoTime() - start) / 1_000_000_000L, out, summary);
    }

    private static List<Source> sources(Path external) throws IOException {
        List<Source> sources = new ArrayList<>();
        for (String root : ROOTS) {
            Path dir = Path.of(root);
            for (Path file : images(dir)) {
                // Copies of datasets/ photos kept for the unit tests.
                if (!file.startsWith(Path.of("src", "test", "resources", "artelab"))) {
                    sources.add(new Source(file, file.toString().replace('\\', '/')));
                }
            }
        }
        if (external != null) {
            for (String set : EXTERNAL) {
                Path dir = external.resolve(set);
                for (Path file : images(dir)) {
                    String relative = external.relativize(file).toString().replace('\\', '/');
                    sources.add(new Source(file, "external/" + relative));
                }
            }
        }
        return sources;
    }

    private static List<Path> images(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).filter(Snapshot::isImage).sorted().toList();
        }
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp");
    }

    private static Outcome read(Source source) {
        BufferedImage image;
        try {
            image = ImageIO.read(source.file().toFile());
        } catch (IOException | RuntimeException exception) {
            image = null;
        }
        if (image == null) {
            return new Outcome(source, Map.of(), true);
        }
        // The sequential reader gives the same hits as the parallel one; the callers are the parallelism here.
        BarcodeReader reader = BarcodeReader.sequential();
        long noBudget = Long.MAX_VALUE;
        Map<String, List<Read>> reads = new LinkedHashMap<>();
        reads.put("zxing", zxing(image));
        reads.put("fast", reads(source, reader, image, ReadOptions.fast().timeBudgetMillis(noBudget)));
        reads.put("aggr", reads(source, reader, image, ReadOptions.aggressive().timeBudgetMillis(noBudget)));
        reads.put("fast-all", reads(source, reader, image, ReadOptions.fast().findAll(true).timeBudgetMillis(noBudget)));
        return new Outcome(source, reads, false);
    }

    /** The reads of one configuration. An exception is a bug to fix, not a reason to lose the run: it becomes an ERROR read. */
    private static List<Read> reads(Source source, BarcodeReader reader, BufferedImage image, ReadOptions options) {
        List<BarcodeHit> hits;
        try {
            hits = reader.read(image, options);
        } catch (RuntimeException | Error failure) {
            System.err.println(source.name() + ": " + failure);
            return List.of(new Read(ERROR, failure.toString(), "error", -1, 0, List.of()));
        }
        List<Read> reads = new ArrayList<>(hits.size());
        for (BarcodeHit hit : hits) {
            reads.add(new Read(hit.symbology().name(), hit.text(), hit.attempt(), hit.orientationDegrees(), hit.confidence(), hit.corners()));
        }
        return reads;
    }

    /** The baseline: MultiFormatReader, HybridBinarizer and TRY_HARDER, as most Java code calls ZXing. */
    private static List<Read> zxing(BufferedImage image) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            Result result = new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))), hints);
            List<ImagePoint> corners = new ArrayList<>();
            if (result.getResultPoints() != null) {
                for (ResultPoint point : result.getResultPoints()) {
                    if (point != null) {
                        corners.add(new ImagePoint(point.getX(), point.getY()));
                    }
                }
            }
            return List.of(new Read(result.getBarcodeFormat().name(), result.getText(), "zxing", -1, 1.0, corners));
        } catch (NotFoundException | RuntimeException none) {
            return List.of();
        }
    }

    private static String lines(Outcome outcome) {
        String path = outcome.source().name();
        if (outcome.unreadable()) {
            return path + "\t-\tunreadable\n";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<Read>> config : outcome.reads().entrySet()) {
            out.append(path).append('\t').append(config.getKey()).append('\t').append(config.getValue().size());
            for (Read read : config.getValue()) {
                out.append('\t').append(read.symbology()).append('|').append(escape(read.text()))
                        .append('|').append(read.attempt())
                        .append('|').append(read.orientation())
                        .append('|').append(read.confidence()).append('|');
                for (ImagePoint corner : read.corners()) {
                    out.append(Math.round(corner.x())).append(',').append(Math.round(corner.y())).append(' ');
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** The outcomes an earlier run wrote, the inverse of {@link #lines(Outcome)}. */
    private static List<Outcome> parse(Path tsv, Path external) throws IOException {
        Map<String, Map<String, List<Read>>> reads = new LinkedHashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields.length < 3) {
                continue;
            }
            String name = fields[0];
            if (fields[1].equals("-")) {
                outcomes.add(new Outcome(new Source(file(name, external), name), Map.of(), true));
                continue;
            }
            List<Read> list = new ArrayList<>();
            for (int i = 3; i < fields.length; i++) {
                list.add(read(fields[i]));
            }
            reads.computeIfAbsent(name, key -> new LinkedHashMap<>()).put(fields[1], list);
        }
        for (Map.Entry<String, Map<String, List<Read>>> entry : reads.entrySet()) {
            outcomes.add(new Outcome(new Source(file(entry.getKey(), external), entry.getKey()), entry.getValue(), false));
        }
        return outcomes;
    }

    private static Path file(String name, Path external) {
        return name.startsWith("external/") && external != null ? external.resolve(name.substring("external/".length())) : Path.of(name);
    }

    private static Read read(String field) {
        String[] parts = field.split("\\|", -1);
        List<ImagePoint> corners = new ArrayList<>();
        for (String point : parts[5].trim().split(" ")) {
            if (!point.isEmpty()) {
                String[] xy = point.split(",");
                corners.add(new ImagePoint(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
            }
        }
        return new Read(parts[0], unescape(parts[1]), parts[2], Integer.parseInt(parts[3]), Double.parseDouble(parts[4]), corners);
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 3 < text.length() && text.charAt(i + 1) == 'x') {
                out.append((char) Integer.parseInt(text.substring(i + 2, i + 4), 16));
                i += 3;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '|' || c == '\\') {
                out.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
