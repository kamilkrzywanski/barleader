package io.github.kamilkrzywanski.barcode.eval;

import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.BarcodeReader;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.ReadOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Every hit on every image of the Arte-Lab set and the zxing-cpp samples, in fast, aggressive and
 * fast with findAll, without a time budget. The output has no timings, so two runs of the same
 * code give the same bytes on any machine, and {@code diff} between a run before and after a
 * change lists exactly the reads that changed: text, symbology, attempt, orientation, confidence
 * and corners.
 *
 * <pre>
 * mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot.tsv"
 * </pre>
 *
 * One line per image and configuration: path, configuration, hit count, then one
 * {@code symbology|text|attempt|orientation|confidence|corners} field per hit. Control characters,
 * {@code |} and {@code \} in the text are escaped as {@code \xNN}.
 */
public final class Snapshot {
    private static final String[] ROOTS = {
            "datasets/artelab-medium-barcode-1d",
            "datasets/zxing-cpp-3.1.1",
            "src/test/resources"
    };

    private Snapshot() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Snapshot <out.tsv> [threads]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        List<Path> files = new ArrayList<>();
        for (String root : ROOTS) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).filter(Snapshot::isImage).sorted().forEach(files::add);
            }
        }
        long noBudget = Long.MAX_VALUE;
        ReadOptions[] configs = {
                ReadOptions.fast().timeBudgetMillis(noBudget),
                ReadOptions.aggressive().timeBudgetMillis(noBudget),
                ReadOptions.fast().findAll(true).timeBudgetMillis(noBudget)
        };
        String[] names = {"fast", "aggr", "fast-all"};
        BarcodeReader reader = new BarcodeReader();
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        try {
            List<Future<String>> lines = new ArrayList<>();
            for (Path file : files) {
                lines.add(callers.submit(() -> lines(reader, file, configs, names)));
            }
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                for (Future<String> line : lines) {
                    writer.print(line.get());
                }
            }
        } finally {
            callers.shutdownNow();
        }
        System.err.printf(Locale.ROOT, "%d images in %d s -> %s%n", files.size(), (System.nanoTime() - start) / 1_000_000_000L, out);
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    private static String lines(BarcodeReader reader, Path file, ReadOptions[] configs, String[] names) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (IOException exception) {
            image = null;
        }
        String path = file.toString().replace('\\', '/');
        if (image == null) {
            return path + "\t-\tunreadable\n";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < configs.length; i++) {
            List<BarcodeHit> hits = reader.read(image, configs[i]);
            out.append(path).append('\t').append(names[i]).append('\t').append(hits.size());
            for (BarcodeHit hit : hits) {
                out.append('\t').append(hit.symbology()).append('|').append(escape(hit.text()))
                        .append('|').append(hit.attempt())
                        .append('|').append(hit.orientationDegrees())
                        .append('|').append(hit.confidence()).append('|');
                for (ImagePoint corner : hit.corners()) {
                    out.append(Math.round(corner.x())).append(',').append(Math.round(corner.y())).append(' ');
                }
            }
            out.append('\n');
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
