package io.github.kamilkrzywanski.barcode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    /** zxing-cpp qrcode-1/17: a vCard-like text with CRLF line breaks. */
    private static String sample() throws URISyntaxException {
        return Path.of(MainTest.class.getResource("/official/qrcode-1/17.png").toURI()).toString();
    }

    @Test
    void escapesLineBreaksInTabSeparatedOutput() throws Exception {
        Run run = run(sample());
        assertEquals(0, run.status, run.err);
        String[] fields = run.out.strip().split("\t");
        assertEquals("QR_CODE", fields[1]);
        assertEquals("Sean Owen\\r\\nsrowen@google.com\\r\\n917-364-2918\\r\\nhttp://awesome-thoughts.com", fields[2]);
        assertEquals(1, run.out.strip().lines().count());
    }

    @Test
    void printsJsonAndHonoursFormats() throws Exception {
        Run json = run("--json", sample());
        assertEquals(0, json.status, json.err);
        assertTrue(json.out.startsWith("{\"file\":"), json.out);
        assertTrue(json.out.contains("\"text\":\"Sean Owen\\r\\nsrowen@google.com"), json.out);

        Run linearOnly = run("--formats=ean_13,code_128", sample());
        assertEquals(1, linearOnly.status);
        assertTrue(linearOnly.out.strip().endsWith("\tNO READ"), linearOnly.out);
    }

    @Test
    void printsTheSupplementWhenAskedFor() throws Exception {
        String image = Path.of(MainTest.class.getResource("/official-rows/ean13-extension-1/1.png").toURI()).toString();
        Run plain = run(image);
        assertTrue(plain.out.strip().endsWith("\t-"), plain.out);
        Run read = run("--addon=read", "--json", image);
        assertTrue(read.out.contains("\"addOn\":\""), read.out);
        assertEquals(2, run("--addon=maybe", image).status);
    }

    @Test
    void writesAQrCodeThatReadsBack() throws Exception {
        Path png = Files.createTempFile("barcode-pro-qr", ".png");
        try {
            Run write = run("qr", "--level=Q", "--module=4", "--out=" + png, "Zażółć gęślą jaźń");
            assertEquals(0, write.status, write.err);
            Run read = run(png.toString());
            assertEquals(0, read.status, read.err);
            assertEquals("Zażółć gęślą jaźń", read.out.strip().split("\t")[2]);
        } finally {
            Files.deleteIfExists(png);
        }
        Run svg = run("qr", "--", "-dash first");
        assertEquals(0, svg.status, svg.err);
        assertTrue(svg.out.startsWith("<svg "), svg.out);
        assertEquals(2, run("qr").status);
        assertEquals(2, run("qr", "--level=X", "text").status);
        assertEquals(2, run("qr", "--version=1", "x".repeat(200)).status);
    }

    @Test
    void writesEveryTypeThatReadsBack() throws Exception {
        record Case(String[] options, String content, String text) {
        }
        for (Case c : List.of(
                new Case(new String[]{"--type=CODE_128"}, "ABC-123", "ABC-123"),
                new Case(new String[]{"--type=ean_13", "--height=40"}, "590123412345", "5901234123457"),
                new Case(new String[]{"--type=DATA_MATRIX", "--shape=RECTANGLE"}, "Hello, World!", "Hello, World!"),
                new Case(new String[]{"--type=PDF_417", "--level=4", "--truncated"}, "Zażółć gęślą jaźń", "Zażółć gęślą jaźń"),
                new Case(new String[]{"--type=AZTEC", "--level=50", "--quiet=2"}, "Aztec", "Aztec"),
                new Case(new String[]{"--level=H"}, "QR", "QR"))) {
            Path png = Files.createTempFile("barcode-pro-write", ".png");
            try {
                List<String> args = new ArrayList<>(List.of("write", "--out=" + png));
                args.addAll(List.of(c.options()));
                args.add(c.content());
                Run write = run(args.toArray(String[]::new));
                assertEquals(0, write.status, write.err);
                Run read = run(png.toString());
                assertEquals(0, read.status, read.err);
                assertEquals(c.text(), read.out.strip().split("\t")[2], String.join(" ", c.options()));
            } finally {
                Files.deleteIfExists(png);
            }
        }
        assertTrue(run("write", "--type=CODE_39", "SVG").out.startsWith("<svg "));
        assertEquals(2, run("write", "--type=NOPE", "x").status);
        assertEquals(2, run("write", "--type=EAN_8", "--level=2", "1234567").status);
        assertEquals(2, run("write", "--type=EAN_8", "12345").status);
        assertEquals(2, run("write").status);
    }

    @Test
    void rejectsBadUsage() {
        assertEquals(2, run().status);
        assertEquals(2, run("--bogus", "x.png").status);
        assertEquals(2, run("--formats=NOPE", "x.png").status);
        assertEquals(2, run("does-not-exist.png").status);
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Run(int status, String out, String err) {
    }
}
