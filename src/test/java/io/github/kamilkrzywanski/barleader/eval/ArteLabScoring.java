package io.github.kamilkrzywanski.barleader.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Label match for Arte-Lab Medium Barcode 1D. A labeled hit is the 13-digit
 * file, or a 12-digit UPC-A equal to that file without one leading zero.
 * An 8-digit UPC-E is not a labeled hit. Three upstream label files are empty;
 * the printed code on those frames is {@link #ALLOWED_VISUAL}, not a labeled hit
 * and not an invented false read.
 */
public final class ArteLabScoring {
    public static final Path ROOT = Path.of("datasets", "artelab-medium-barcode-1d");

    /**
     * Printed payload on a frame whose upstream {@code .jpg.txt} is empty.
     * Any other text on those frames is a false read.
     */
    public static final Map<String, String> ALLOWED_VISUAL = Map.of(
            "Dataset1/05102009199.jpg", "8002330112752",
            "Dataset2/Foto(844).jpg", "7321925005431",
            "Dataset2/Foto(849).jpg", "7321925005431"
    );

    private ArteLabScoring() {
    }

    public enum Kind {
        EXACT("exact"),
        UPCA("upca"),
        EMPTY("empty"),
        ALLOWED("allowed"),
        FP("fp"),
        SKIPPED("skipped"),
        ERROR("error");

        public final String wire;

        Kind(String wire) {
            this.wire = wire;
        }
    }

    public record Frame(
            String file,
            String setName,
            Kind baseline,
            Kind fast,
            Kind aggressive,
            boolean aggressiveRan,
            long fastMs,
            long aggressiveMs
    ) {
    }

    public record Tally(
            String setName,
            int n,
            int baselineHit,
            int fastHit,
            int unionHit,
            int empty,
            int allowed,
            int fp,
            int error,
            long maxFastMs,
            long maxAggressiveMs
    ) {
    }

    public static boolean labeledHit(Kind kind) {
        return kind == Kind.EXACT || kind == Kind.UPCA;
    }

    /**
     * Outcome of the aggressive ladder. When aggressive did not run, the fast
     * ladder is its prefix and had already stopped on a labeled hit.
     */
    public static Kind decisive(Frame frame) {
        if (!frame.aggressiveRan()) {
            return frame.fast();
        }
        return frame.aggressive();
    }

    public static Kind classify(String relative, String label, String text) {
        if (text == null || text.isEmpty()) {
            return Kind.EMPTY;
        }
        if (!label.isEmpty() && label.equals(text)) {
            return Kind.EXACT;
        }
        if (label.length() == 13 && label.charAt(0) == '0' && label.substring(1).equals(text)) {
            return Kind.UPCA;
        }
        String allowed = ALLOWED_VISUAL.get(relative);
        if (label.isEmpty() && allowed != null && allowed.equals(text)) {
            return Kind.ALLOWED;
        }
        return Kind.FP;
    }

    public static String labelDigits(Path image) throws IOException {
        Path text = image.resolveSibling(image.getFileName() + ".txt");
        String raw = Files.readString(text, StandardCharsets.ISO_8859_1);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    public static List<String> relativeImages(Path root) throws IOException {
        List<String> keys = new ArrayList<>();
        for (String set : List.of("Dataset1", "Dataset2")) {
            Path directory = root.resolve(set);
            try (Stream<Path> walk = Files.list(directory)) {
                walk.filter(ArteLabScoring::isJpeg)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> keys.add(set + "/" + path.getFileName()));
            }
        }
        return keys;
    }

    public static String cell(String symbology, String attempt, int orientation, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String shown = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        if (shown.length() > 80) {
            shown = shown.substring(0, 80) + "...len=" + text.length();
        }
        return symbology + "@" + attempt + "/" + orientation + ":" + shown;
    }

    public static List<Tally> tally(List<Frame> frames) {
        List<Tally> rows = new ArrayList<>();
        rows.add(tally("Dataset1", frames.stream().filter(frame -> frame.setName().equals("Dataset1")).toList()));
        rows.add(tally("Dataset2", frames.stream().filter(frame -> frame.setName().equals("Dataset2")).toList()));
        rows.add(tally("ALL", frames));
        return rows;
    }

    private static Tally tally(String setName, List<Frame> frames) {
        int baselineHit = 0;
        int fastHit = 0;
        int unionHit = 0;
        int empty = 0;
        int allowed = 0;
        int fp = 0;
        int error = 0;
        long maxFast = 0;
        long maxAggressive = 0;
        for (Frame frame : frames) {
            if (labeledHit(frame.baseline())) {
                baselineHit++;
            }
            if (labeledHit(frame.fast())) {
                fastHit++;
            }
            Kind decisive = decisive(frame);
            if (labeledHit(decisive)) {
                unionHit++;
            } else if (decisive == Kind.EMPTY) {
                empty++;
            } else if (decisive == Kind.ALLOWED) {
                allowed++;
            } else if (decisive == Kind.FP) {
                fp++;
            } else {
                error++;
            }
            maxFast = Math.max(maxFast, frame.fastMs());
            maxAggressive = Math.max(maxAggressive, frame.aggressiveMs());
        }
        return new Tally(
                setName,
                frames.size(),
                baselineHit,
                fastHit,
                unionHit,
                empty,
                allowed,
                fp,
                error,
                maxFast,
                maxAggressive
        );
    }

    private static boolean isJpeg(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}
