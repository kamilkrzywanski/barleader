package io.github.kamilkrzywanski.barcode.eval;

import io.github.kamilkrzywanski.barcode.eval.Labels.Codes;
import io.github.kamilkrzywanski.barcode.eval.Labels.Expected;
import io.github.kamilkrzywanski.barcode.eval.Labels.Negative;
import io.github.kamilkrzywanski.barcode.eval.Labels.Outcome;
import io.github.kamilkrzywanski.barcode.eval.Labels.Read;
import io.github.kamilkrzywanski.barcode.eval.Labels.RegionScore;
import io.github.kamilkrzywanski.barcode.eval.Labels.Regions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Markdown summary of a snapshot: for every set and every folder of it, how many labelled
 * images each configuration read correctly and how many it read wrong, how many images without a
 * barcode it read something on, and how many codes labelled only by their place it found.
 */
final class SnapshotReport {
    /**
     * Folders shown but kept out of the totals. KAIST's train and validation split {@code rec} holds
     * 482 of its 1200 photos at the resolutions of the Arte-Lab cameras, among them Arte-Lab GTINs,
     * and the aggressive ladder was tuned on Arte-Lab. Its test split {@code single_test} has 6.
     * BarBeR keeps the Arte-Lab photos as its {@code Artelab} sets, and all of KAIST as {@code Deal Kaist}.
     */
    static final Set<String> APART = Set.of("barcode_bb/rec", "Artelab", "Artelab Extended", "Deal Kaist");

    private SnapshotReport() {
    }

    /** Counters of one folder of one set. */
    private static final class Row {
        int images;
        int labelled;
        int negatives;
        int codes;
        final Map<String, int[]> counts = new LinkedHashMap<>();

        /** hit, partial, wrong, false read on a negative, matched code, unmatched read */
        int[] of(String config) {
            return counts.computeIfAbsent(config, key -> new int[6]);
        }
    }

    static String render(List<Snapshot.Outcome> outcomes) {
        Map<String, Map<String, Row>> sets = new LinkedHashMap<>();
        int unreadable = 0;
        List<String> errors = new ArrayList<>();
        for (Snapshot.Outcome outcome : outcomes) {
            if (outcome.unreadable()) {
                unreadable++;
                continue;
            }
            String name = outcome.source().name();
            String set = Labels.set(name);
            String family = Labels.family(outcome.source().file(), name);
            Row row = sets.computeIfAbsent(set, key -> new TreeMap<>()).computeIfAbsent(family, key -> new Row());
            row.images++;
            Expected expected = Labels.of(outcome.source().file(), name);
            if (expected instanceof Regions || expected instanceof Codes) {
                row.codes += Labels.codeCount(expected);
            } else if (expected instanceof Negative) {
                row.negatives++;
            } else if (!(expected instanceof Labels.Unlabelled)) {
                row.labelled++;
            }
            for (String config : Snapshot.CONFIGS) {
                List<Read> reads = outcome.reads().getOrDefault(config, List.of());
                for (Read read : reads) {
                    if (read.symbology().equals(Snapshot.ERROR)) {
                        errors.add(config + " on " + name + ": " + read.text());
                    }
                }
                int[] counts = row.of(config);
                if (expected instanceof Regions || expected instanceof Codes) {
                    RegionScore score = expected instanceof Regions regions
                            ? Labels.judgeRegions(regions, reads) : Labels.judgeCodes((Codes) expected, reads);
                    counts[4] += score.matched();
                    counts[5] += score.unmatched();
                    continue;
                }
                Outcome verdict = Labels.judge(expected, reads);
                switch (verdict) {
                    case HIT -> counts[0]++;
                    case PARTIAL -> counts[1]++;
                    case WRONG -> counts[2]++;
                    case FALSE_READ -> counts[3]++;
                    default -> {
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("# Snapshot summary\n\n");
        out.append("Cells are labelled images read right, then the number read wrong in brackets. ")
                .append("`zxing` is MultiFormatReader with HybridBinarizer and TRY_HARDER; the others are barcode-pro ")
                .append("without a time budget. Folders marked *apart* are shown but kept out of the totals: ")
                .append("KAIST's `rec` split holds photos like Arte-Lab's, which the aggressive ladder was tuned on.\n\n");
        out.append(header("set", "images", "labelled"));
        for (Map.Entry<String, Map<String, Row>> set : sets.entrySet()) {
            Row total = total(set.getValue());
            if (total.labelled > 0) {
                out.append(row(set.getKey(), total.images, total.labelled, total));
            }
        }
        if (unreadable > 0) {
            out.append("\n").append(unreadable).append(" images could not be opened.\n");
        }
        if (!errors.isEmpty()) {
            out.append("\nThe reader threw ").append(errors.size()).append(" times; each counts as a wrong read:\n\n");
            for (String error : errors) {
                out.append("- ").append(error).append('\n');
            }
        }
        for (Map.Entry<String, Map<String, Row>> set : sets.entrySet()) {
            out.append("\n## ").append(set.getKey()).append("\n\n");
            boolean any = false;
            StringBuilder text = new StringBuilder(header("folder", "images", "labelled"));
            StringBuilder negative = new StringBuilder(negativeHeader());
            StringBuilder regions = new StringBuilder(regionHeader());
            boolean anyNegative = false;
            boolean anyRegion = false;
            for (Map.Entry<String, Row> family : set.getValue().entrySet()) {
                Row row = family.getValue();
                if (row.labelled > 0) {
                    String label = APART.contains(family.getKey()) ? family.getKey() + " *apart*" : family.getKey();
                    text.append(row(label, row.images, row.labelled, row));
                    any = true;
                }
                if (row.negatives > 0) {
                    negative.append(negativeRow(family.getKey(), row));
                    anyNegative = true;
                }
                if (row.codes > 0) {
                    regions.append(regionRow(APART.contains(family.getKey()) ? family.getKey() + " *apart*" : family.getKey(), row));
                    anyRegion = true;
                }
            }
            if (any) {
                Row total = total(set.getValue());
                text.append(row("**total**", total.images, total.labelled, total));
                out.append(text);
            }
            if (anyNegative) {
                out.append("\nImages without a barcode, and how many of them each configuration read something on:\n\n")
                        .append(negative);
            }
            if (anyRegion) {
                regions.append(regionRow("**total**", total(set.getValue())));
                out.append("\nImages labelled code by code, BarBeR by text and the others only by place, QR codes by ")
                        .append("corners and EAN codes by boxes: codes found of all codes, then reads that matched no code:\n\n")
                        .append(regions);
            }
        }
        return out.toString();
    }

    private static Row total(Map<String, Row> families) {
        Row total = new Row();
        for (Map.Entry<String, Row> family : families.entrySet()) {
            if (APART.contains(family.getKey())) {
                continue;
            }
            Row row = family.getValue();
            total.images += row.images;
            total.labelled += row.labelled;
            total.codes += row.codes;
            for (String config : Snapshot.CONFIGS) {
                int[] sum = total.of(config);
                int[] counts = row.of(config);
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += counts[i];
                }
            }
        }
        return total;
    }

    private static String header(String first, String second, String third) {
        StringBuilder out = new StringBuilder("| ").append(first).append(" | ").append(second).append(" | ").append(third);
        StringBuilder rule = new StringBuilder("| --- | ---: | ---:");
        for (String config : Snapshot.CONFIGS) {
            out.append(" | ").append(config);
            rule.append(" | ---:");
        }
        return out.append(" |\n").append(rule).append(" |\n").toString();
    }

    private static String row(String name, int images, int labelled, Row row) {
        StringBuilder out = new StringBuilder("| ").append(name).append(" | ").append(images).append(" | ").append(labelled);
        for (String config : Snapshot.CONFIGS) {
            int[] counts = row.of(config);
            out.append(" | ").append(counts[0]);
            if (counts[1] > 0) {
                out.append(" +").append(counts[1]).append(" partial");
            }
            out.append(" (").append(counts[2]).append(')');
        }
        return out.append(" |\n").toString();
    }

    private static String negativeHeader() {
        StringBuilder out = new StringBuilder("| folder | images");
        StringBuilder rule = new StringBuilder("| --- | ---:");
        for (String config : Snapshot.CONFIGS) {
            out.append(" | ").append(config);
            rule.append(" | ---:");
        }
        return out.append(" |\n").append(rule).append(" |\n").toString();
    }

    private static String negativeRow(String name, Row row) {
        StringBuilder out = new StringBuilder("| ").append(name).append(" | ").append(row.negatives);
        for (String config : Snapshot.CONFIGS) {
            out.append(" | ").append(row.of(config)[3]);
        }
        return out.append(" |\n").toString();
    }

    private static String regionHeader() {
        StringBuilder out = new StringBuilder("| folder | codes");
        StringBuilder rule = new StringBuilder("| --- | ---:");
        for (String config : Snapshot.CONFIGS) {
            out.append(" | ").append(config);
            rule.append(" | ---:");
        }
        return out.append(" |\n").append(rule).append(" |\n").toString();
    }

    private static String regionRow(String name, Row row) {
        StringBuilder out = new StringBuilder("| ").append(name).append(" | ").append(row.codes);
        for (String config : Snapshot.CONFIGS) {
            int[] counts = row.of(config);
            out.append(" | ").append(counts[4]).append(" (").append(counts[5]).append(')');
        }
        return out.append(" |\n").toString();
    }
}
