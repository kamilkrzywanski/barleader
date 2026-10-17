package io.github.kamilkrzywanski.barcode.eval;

import io.github.kamilkrzywanski.barcode.ImagePoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What an image of a benchmark set should read, taken from the label that set keeps next to it,
 * and how a list of reads is judged against it. The conventions of each set:
 *
 * <ul>
 *   <li>zxing-cpp and ZXing samples: {@code <name>.txt} holds the exact text, {@code <name>.bin}
 *       the exact bytes, or a {@code <name>.result.txt} has a {@code text=} line. A
 *       {@code falsepositives} folder has no barcode at all.</li>
 *   <li>Arte-Lab: {@code <image>.txt} holds the 13 digits, empty when the label is unknown.</li>
 *   <li>KAIST DEAL: the file name is the EAN-13 or EAN-8, before an optional {@code _N} or
 *       {@code -NN}. {@code multi_bb} has only boxes, in Pascal VOC files under {@code multi_gt}.
 *       {@code background} has no barcode.</li>
 *   <li>BoofCV QR: in {@code detection}, {@code <name>.txt} lists the four corners of every QR
 *       code, eight numbers a code, and there is no text. In {@code decoding} it holds the text.</li>
 *   <li>BarBeR: one VIA file a source set under {@code Annotations/VIA} gives every code of every
 *       image its type, polygon and text; the text is {@code -1} or {@code ^} where the authors
 *       could not decode it.</li>
 * </ul>
 */
final class Labels {
    private static final Pattern BOX = Pattern.compile(
            "<xmin>\\s*([\\d.]+)\\s*</xmin>\\s*<ymin>\\s*([\\d.]+)\\s*</ymin>\\s*<xmax>\\s*([\\d.]+)\\s*</xmax>\\s*<ymax>\\s*([\\d.]+)\\s*</ymax>");

    /** BarBeR types no reader here decodes: postal codes, IATA 2 of 5 and a lone EAN-2. */
    private static final Set<String> UNREAD = Set.of("ROYALMAILCODE", "KIX", "JAPANPOST", "POSTNET", "INTELLIGENTMAIL", "IATA25", "2-DIGIT");
    private static final Map<Path, Barber> BARBER = new ConcurrentHashMap<>();

    private Labels() {
    }

    sealed interface Expected permits Text, Gtin, Negative, Regions, Codes, Unlabelled {
    }

    /** An exact text, with the usual differences in how libraries print GS1, UPC and Codabar allowed. */
    record Text(String text) implements Expected {
    }

    /** EAN-13 or EAN-8 digits; a UPC-A read counts when the digits are 0 and its 12. */
    record Gtin(String digits) implements Expected {
    }

    /** No barcode: any read is false. */
    record Negative() implements Expected {
    }

    /** Codes known only by their place: QR codes by their corners, or EAN codes by their boxes. */
    record Regions(String kind, List<List<ImagePoint>> codes) implements Expected {
        static final String QR = "QR_CODE";
        static final String EAN = "EAN";
    }

    /** Every code of an image with its text, as BarBeR labels them. */
    record Codes(List<Code> codes) implements Expected {
    }

    /** One BarBeR code; the text is null where the authors could not decode it. */
    record Code(String type, String text, List<ImagePoint> polygon) {
    }

    record Unlabelled() implements Expected {
    }

    /** The BarBeR labels of one images folder: the codes of every image and the set it comes from. */
    private record Barber(Map<String, List<Code>> codes, Map<String, String> source) {
    }

    /** One read of one configuration, the way the snapshot prints it. */
    record Read(String symbology, String text, String attempt, int orientation, double confidence, List<ImagePoint> corners) {
    }

    enum Outcome { HIT, PARTIAL, WRONG, MISS, CLEAN, FALSE_READ, UNLABELLED }

    /** For corner-labelled images: codes found, codes in the image, and reads that matched none. */
    record RegionScore(int matched, int codes, int unmatched) {
    }

    static Expected of(Path file, String name) {
        try {
            String set = set(name);
            String folder = file.getParent().getFileName().toString();
            String base = stripExtension(file.getFileName().toString());
            switch (set) {
                case "arte-lab": {
                    Path label = file.resolveSibling(file.getFileName() + ".txt");
                    String digits = Files.exists(label) ? digits(Files.readString(label, StandardCharsets.ISO_8859_1)) : "";
                    return digits.isEmpty() ? new Unlabelled() : new Gtin(digits);
                }
                case "kaist-deal": {
                    if (name.contains("/background/")) {
                        return new Negative();
                    }
                    if (name.contains("/multi_bb/")) {
                        return boxes(file.getParent().resolveSibling("multi_gt").resolve(base + ".xml"));
                    }
                    String digits = kaistDigits(base);
                    return digits.isEmpty() ? new Unlabelled() : new Gtin(digits);
                }
                case "barber": {
                    List<Code> codes = barber(file.getParent()).codes().get(file.getFileName().toString());
                    return codes == null ? new Unlabelled() : new Codes(codes);
                }
                case "boofcv-qr": {
                    Path label = file.resolveSibling(base + ".txt");
                    if (folder.equals("decoding")) {
                        return Files.exists(label) ? new Text(Files.readString(label, StandardCharsets.UTF_8)) : new Unlabelled();
                    }
                    return corners(label);
                }
                default: {
                    if (folder.startsWith("falsepositives")) {
                        return new Negative();
                    }
                    Path text = file.resolveSibling(base + ".txt");
                    if (Files.exists(text)) {
                        return new Text(Files.readString(text, StandardCharsets.UTF_8));
                    }
                    Path bytes = file.resolveSibling(base + ".bin");
                    if (Files.exists(bytes)) {
                        return new Text(new String(Files.readAllBytes(bytes), StandardCharsets.ISO_8859_1));
                    }
                    Path result = file.resolveSibling(base + ".result.txt");
                    if (Files.exists(result)) {
                        for (String line : Files.readAllLines(result, StandardCharsets.UTF_8)) {
                            if (line.startsWith("text=")) {
                                return new Text(line.substring(5));
                            }
                        }
                    }
                    return new Unlabelled();
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return new Unlabelled();
        }
    }

    /** {@code arte-lab}, {@code zxing-cpp}, {@code kaist-deal}, {@code zxing-blackbox}, {@code boofcv-qr} or {@code other}. */
    static String set(String name) {
        if (name.startsWith("datasets/artelab")) {
            return "arte-lab";
        }
        if (name.startsWith("datasets/zxing-cpp") || name.startsWith("src/test/resources/official")) {
            return "zxing-cpp";
        }
        if (name.startsWith("external/")) {
            String rest = name.substring("external/".length());
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }
        return "other";
    }

    /** The group a table row counts: a folder without its {@code -N} suffix, a KAIST archive and split, an Arte-Lab half. */
    static String family(String name) {
        String set = set(name);
        String[] parts = name.split("/");
        if (set.equals("kaist-deal") && parts.length > 4) {
            return parts[2] + "/" + parts[parts.length - 2];
        }
        String folder = parts.length > 1 ? parts[parts.length - 2] : "";
        return set.equals("arte-lab") || set.equals("boofcv-qr") ? folder : folder.replaceAll("-\\d+$", "");
    }

    /** As {@link #family(String)}, but a BarBeR image counts under the source set its label comes from. */
    static String family(Path file, String name) {
        if (set(name).equals("barber")) {
            return barber(file.getParent()).source().getOrDefault(file.getFileName().toString(), "unlisted");
        }
        return family(name);
    }

    static Outcome judge(Expected expected, List<Read> reads) {
        if (expected instanceof Unlabelled || expected instanceof Regions || expected instanceof Codes) {
            return Outcome.UNLABELLED;
        }
        if (expected instanceof Negative) {
            return reads.isEmpty() ? Outcome.CLEAN : Outcome.FALSE_READ;
        }
        boolean partial = false;
        for (Read read : reads) {
            if (expected instanceof Gtin gtin ? sameGtin(read, gtin.digits()) : sameText(read, ((Text) expected).text())) {
                return Outcome.HIT;
            }
            if (expected instanceof Text text && addOnMissing(read, text.text())) {
                partial = true;
            }
        }
        if (partial) {
            return Outcome.PARTIAL;
        }
        return reads.isEmpty() ? Outcome.MISS : Outcome.WRONG;
    }

    /**
     * A read of the labelled kind matches a labelled code when the centre of one lies inside the
     * other. A linear read may have only the two ends of its scan line, so only its centre counts.
     */
    static RegionScore judgeRegions(Regions regions, List<Read> reads) {
        List<Read> kind = new ArrayList<>();
        for (Read read : reads) {
            if (ofKind(read.symbology(), regions.kind()) && !read.corners().isEmpty()) {
                kind.add(read);
            }
        }
        boolean[] used = new boolean[kind.size()];
        int matched = 0;
        for (List<ImagePoint> code : regions.codes()) {
            for (int i = 0; i < kind.size(); i++) {
                List<ImagePoint> found = kind.get(i).corners();
                if (!used[i] && (inside(center(found), code) || found.size() >= 3 && inside(center(code), found))) {
                    used[i] = true;
                    matched++;
                    break;
                }
            }
        }
        int unmatched = 0;
        for (boolean u : used) {
            unmatched += u ? 0 : 1;
        }
        return new RegionScore(matched, regions.codes().size(), unmatched);
    }

    /** The codes a place or text label counts: every code of a region label, the readable ones of BarBeR. */
    static int codeCount(Expected expected) {
        if (expected instanceof Regions regions) {
            return regions.codes().size();
        }
        if (expected instanceof Codes codes) {
            return (int) codes.codes().stream().filter(Labels::countable).count();
        }
        return 0;
    }

    /**
     * BarBeR codes are matched by text: a read finds the first code it spells that no other read
     * found yet. Codes without a text, or of a type no reader here decodes, are not counted, and a
     * read is unmatched when it spells no code of the image at all.
     */
    static RegionScore judgeCodes(Codes codes, List<Read> reads) {
        boolean[] used = new boolean[reads.size()];
        int matched = 0;
        for (Code code : codes.codes()) {
            if (!countable(code)) {
                continue;
            }
            for (int i = 0; i < reads.size(); i++) {
                if (!used[i] && sameCode(reads.get(i), code)) {
                    used[i] = true;
                    matched++;
                    break;
                }
            }
        }
        int unmatched = 0;
        for (int i = 0; i < reads.size(); i++) {
            if (used[i]) {
                continue;
            }
            Read read = reads.get(i);
            if (codes.codes().stream().noneMatch(code -> code.text() != null && sameCode(read, code))) {
                unmatched++;
            }
        }
        return new RegionScore(matched, codeCount(codes), unmatched);
    }

    private static boolean countable(Code code) {
        return code.text() != null && !UNREAD.contains(code.type());
    }

    private static boolean sameCode(Read read, Code code) {
        String text = code.text();
        if (code.type().equals("C39") && text.length() > 2 && text.startsWith("*") && text.endsWith("*")) {
            text = text.substring(1, text.length() - 1);
        }
        return sameText(read, text);
    }

    private static Barber barber(Path images) {
        return BARBER.computeIfAbsent(images, Labels::loadBarber);
    }

    @SuppressWarnings("unchecked")
    private static Barber loadBarber(Path images) {
        Path via = images.getParent().getParent().resolve("Annotations").resolve("VIA");
        Map<String, List<Code>> codes = new HashMap<>();
        Map<String, String> source = new HashMap<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(via)) {
            files = list.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException missing) {
            return new Barber(Map.of(), Map.of());
        }
        for (Path file : files) {
            String set = stripExtension(file.getFileName().toString());
            Map<String, Object> root;
            try {
                root = (Map<String, Object>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            Map<String, Object> metadata = (Map<String, Object>) root.get("_via_img_metadata");
            for (Object entry : metadata.values()) {
                Map<String, Object> image = (Map<String, Object>) entry;
                String name = (String) image.get("filename");
                source.putIfAbsent(name, set);
                List<Code> list = codes.computeIfAbsent(name, key -> new ArrayList<>());
                for (Object region : (List<Object>) image.get("regions")) {
                    Map<String, Object> shape = (Map<String, Object>) ((Map<String, Object>) region).get("shape_attributes");
                    Map<String, Object> attributes = (Map<String, Object>) ((Map<String, Object>) region).get("region_attributes");
                    List<ImagePoint> polygon = new ArrayList<>();
                    if ("polygon".equals(shape.get("name"))) {
                        List<Object> xs = (List<Object>) shape.get("all_points_x");
                        List<Object> ys = (List<Object>) shape.get("all_points_y");
                        for (int i = 0; i < xs.size() && i < ys.size(); i++) {
                            polygon.add(new ImagePoint(((Number) xs.get(i)).doubleValue(), ((Number) ys.get(i)).doubleValue()));
                        }
                    }
                    Object text = attributes.get("String");
                    String string = text == null ? null : text instanceof Double number && number == Math.rint(number)
                            ? Long.toString(number.longValue()) : text.toString();
                    if (string != null && (string.isBlank() || string.equals("-1") || string.equals("^"))) {
                        string = null;
                    }
                    if (string != null) {
                        string = unescapeVia(string);
                    }
                    list.add(new Code(String.valueOf(attributes.get("Type")), string, polygon));
                }
            }
        }
        return new Barber(codes, source);
    }

    /**
     * VIA saves an attribute HTML-escaped, so a BarBeR text has {@code &amp;} where the symbol has
     * {@code &}, and the same for {@code <} and {@code >}: 154, 413 and 418 times, in QR codes with
     * URLs and in PDF417 with XML. {@code &amp;} goes last, so an escaped entity stays one.
     */
    static String unescapeVia(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    private static boolean ofKind(String symbology, String kind) {
        if (kind.equals(Regions.EAN)) {
            return symbology.startsWith("EAN") || symbology.startsWith("UPC");
        }
        return symbology.equals(kind);
    }

    /** The 13 or 8 digits a KAIST file is named after, or empty. */
    static String kaistDigits(String base) {
        String digits = base.split("[_-]", 2)[0];
        return digits.matches("\\d{13}|\\d{8}") ? digits : "";
    }

    private static boolean sameGtin(Read read, String digits) {
        return gtin(read.symbology(), read.text()).equals(digits) || read.text().equals(digits);
    }

    private static boolean sameText(Read read, String expected) {
        String text = read.text();
        if (text.equals(expected) || text.strip().equals(expected.strip())
                || text.replace("\r\n", "\n").strip().equals(expected.replace("\r\n", "\n").strip())) {
            return true;
        }
        String gtin = gtin(read.symbology(), text);
        if (gtin.equals(expected.strip()) || ("0" + gtin).equals(expected.strip()) || gtin.equals("0" + expected.strip())) {
            return true;
        }
        if (read.symbology().startsWith("DATABAR") || read.symbology().startsWith("RSS")) {
            String plain = expected.strip().replace("(", "").replace(")", "");
            return text.replace("(", "").replace(")", "").equals(plain);
        }
        if (read.symbology().equals("CODABAR")) {
            String e = expected.strip();
            return (e.length() > 2 && text.equals(e.substring(1, e.length() - 1)))
                    || (text.length() > 2 && text.substring(1, text.length() - 1).equals(e));
        }
        return false;
    }

    /** The main EAN/UPC symbol was read, and the label also has its 2- or 5-digit supplement. */
    private static boolean addOnMissing(Read read, String expected) {
        String gtin = gtin(read.symbology(), read.text());
        String e = expected.strip();
        for (String main : new String[]{gtin, read.text()}) {
            if (!main.isEmpty() && e.startsWith(main) && (e.length() == main.length() + 2 || e.length() == main.length() + 5)) {
                return true;
            }
        }
        return false;
    }

    /** UPC-A and UPC-E as the 13-digit GTIN; any other read unchanged. */
    static String gtin(String symbology, String text) {
        if (symbology.equals("UPC_A") && text.length() == 12) {
            return "0" + text;
        }
        if (symbology.equals("UPC_E") && text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
            return "0" + expandUpce(text);
        }
        return text;
    }

    private static String expandUpce(String upce) {
        char system = upce.charAt(0);
        String d = upce.substring(1, 7);
        char check = upce.charAt(7);
        char last = d.charAt(5);
        String body;
        if (last == '0' || last == '1' || last == '2') {
            body = d.substring(0, 2) + last + "0000" + d.substring(2, 5);
        } else if (last == '3') {
            body = d.substring(0, 3) + "00000" + d.substring(3, 5);
        } else if (last == '4') {
            body = d.substring(0, 4) + "00000" + d.charAt(4);
        } else {
            body = d.substring(0, 5) + "0000" + last;
        }
        return system + body + check;
    }

    /**
     * BoofCV QR labels: the corners of every code in order, eight numbers a code. Newer files put a
     * code on a line after a {@code SETS} line, older ones a point on a line; comments start with #.
     */
    static Expected corners(Path label) throws IOException {
        if (!Files.exists(label)) {
            return new Unlabelled();
        }
        List<Double> numbers = new ArrayList<>();
        for (String line : Files.readAllLines(label, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("SETS")) {
                continue;
            }
            for (String number : trimmed.split("[\\s,]+")) {
                numbers.add(Double.parseDouble(number));
            }
        }
        List<List<ImagePoint>> codes = new ArrayList<>();
        for (int at = 0; at + 8 <= numbers.size(); at += 8) {
            List<ImagePoint> corners = new ArrayList<>(4);
            for (int i = at; i < at + 8; i += 2) {
                corners.add(new ImagePoint(numbers.get(i), numbers.get(i + 1)));
            }
            codes.add(corners);
        }
        return codes.isEmpty() ? new Unlabelled() : new Regions(Regions.QR, codes);
    }

    /** KAIST multi_bb labels: Pascal VOC, one {@code bndbox} an EAN code. */
    static Expected boxes(Path label) throws IOException {
        if (!Files.exists(label)) {
            return new Unlabelled();
        }
        String xml = Files.readString(label, StandardCharsets.UTF_8);
        List<List<ImagePoint>> codes = new ArrayList<>();
        Matcher box = BOX.matcher(xml);
        while (box.find()) {
            double x1 = Double.parseDouble(box.group(1));
            double y1 = Double.parseDouble(box.group(2));
            double x2 = Double.parseDouble(box.group(3));
            double y2 = Double.parseDouble(box.group(4));
            codes.add(List.of(new ImagePoint(x1, y1), new ImagePoint(x2, y1), new ImagePoint(x2, y2), new ImagePoint(x1, y2)));
        }
        return codes.isEmpty() ? new Unlabelled() : new Regions(Regions.EAN, codes);
    }

    private static ImagePoint center(List<ImagePoint> points) {
        double x = 0;
        double y = 0;
        for (ImagePoint point : points) {
            x += point.x();
            y += point.y();
        }
        return new ImagePoint(x / points.size(), y / points.size());
    }

    /** Point in polygon by ray crossing. */
    private static boolean inside(ImagePoint point, List<ImagePoint> polygon) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            ImagePoint a = polygon.get(i);
            ImagePoint b = polygon.get(j);
            if ((a.y() > point.y()) != (b.y() > point.y())
                    && point.x() < (b.x() - a.x()) * (point.y() - a.y()) / (b.y() - a.y()) + a.x()) {
                in = !in;
            }
        }
        return in;
    }

    private static String digits(String text) {
        return text.replaceAll("\\D", "");
    }

    private static String stripExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }
}
