package io.github.kamilkrzywanski.barleader.eval;

import io.github.kamilkrzywanski.barleader.BarcodeReader;
import io.github.kamilkrzywanski.barleader.ReadOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * How long a read takes: every image of the given directories read one after another by the
 * default reader, which runs the variants of one read on its pool, in fast and then aggressive mode
 * without a time budget. Prints the wall time and the CPU time of the process per image, after a
 * warm-up pass over the first images.
 *
 * <pre>
 * mvn -q -Pspeed test-compile exec:java -Dexec.args="datasets/artelab-medium-barcode-1d"
 * </pre>
 */
public final class Speed {
    private static final int WARM_UP = 20;

    private Speed() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: Speed <image directory>...");
            System.exit(2);
        }
        List<BufferedImage> images = new ArrayList<>();
        for (String arg : args) {
            try (Stream<Path> walk = Files.walk(Path.of(arg))) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
                        BufferedImage image = ImageIO.read(file.toFile());
                        if (image != null) {
                            images.add(image);
                        }
                    }
                }
            }
        }
        BarcodeReader reader = new BarcodeReader();
        for (ReadOptions options : List.of(ReadOptions.fast(), ReadOptions.aggressive())) {
            options = options.timeBudgetMillis(Long.MAX_VALUE);
            for (int i = 0; i < Math.min(WARM_UP, images.size()); i++) {
                reader.read(images.get(i), options);
            }
            long cpu = cpu();
            long start = System.nanoTime();
            int read = 0;
            for (BufferedImage image : images) {
                read += reader.read(image, options).isEmpty() ? 0 : 1;
            }
            double wall = (System.nanoTime() - start) / 1e9 / images.size();
            double used = (cpu() - cpu) / 1e9 / images.size();
            System.out.printf(Locale.ROOT, "%-10s %d images, %d read: %.3f s a photo, %.3f s of CPU a photo, %d cores%n",
                    options.mode(), images.size(), read, wall, used, Runtime.getRuntime().availableProcessors());
        }
    }

    /** CPU time of the process in nanoseconds, from {@link ProcessHandle} so the module needs nothing more. */
    private static long cpu() {
        return ProcessHandle.current().info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
    }
}
