package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder;
import com.google.zxing.common.reedsolomon.ReedSolomonException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MaxiCode decoder ported from zxing-cpp 3.1.1 {@code MCReader.cpp} ({@code ExtractPureBits}),
 * {@code MCBitMatrixParser.cpp} and {@code MCDecoder.cpp}.
 *
 * <p>Only the pure, axis-aligned symbol is read. The C++ reader has no photo detector.
 * This decoder is not called from the scan ladder, and MaxiCode is not a {@code Symbology}.
 * Bytes with no ECI are ISO-8859-1, the {@code defaultCharset} zxing-cpp sets for MaxiCode.
 * Structured-append position is returned beside the text
 * and is not written into it.
 */
final class MaxiCodeDecoder {
    private static final int WIDTH = 30;
    private static final int HEIGHT = 33;
    private static final int ALL = 0;
    private static final int EVEN = 1;
    private static final int ODD = 2;
    private static final int LCHA = 0x107;
    private static final int LCHB = 0x108;
    private static final int LOCK = 0x109;
    private static final int ECI = 0x10A;
    private static final int NS = 0x10B;
    private static final int PAD = 0x10C;
    private static final int SHI0 = 0x100;
    private static final char GS = 0x1D;
    private static final char RS = 0x1E;
    private static final ReedSolomonDecoder REED_SOLOMON = new ReedSolomonDecoder(GenericGF.MAXICODE_FIELD_64);

    /** Set A through E. Control values sit at {@code 0x100} and above, matching {@code CHARSETS}. */
    private static final int[][] SETS = {
            {
                    '\r', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
                    'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', ECI, 0x1C, GS, RS, NS,
                    ' ', PAD, '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', 0x101, 0x102, 0x103, 0x104, LCHB,
            },
            {
                    '`', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
                    'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', ECI, 0x1C, GS, RS, NS,
                    '{', PAD, '}', '~', 0x7F, ';', '<', '=', '>', '?', '[', '\\', ']', '^', '_', ' ',
                    ',', '.', '/', ':', '@', '!', '|', PAD, 0x105, 0x106, PAD, SHI0, 0x102, 0x103, 0x104, LCHA,
            },
            {
                    0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF,
                    0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, ECI, 0x1C, GS, RS, NS,
                    0xDB, 0xDC, 0xDD, 0xDE, 0xDF, 0xAA, 0xAC, 0xB1, 0xB2, 0xB3, 0xB5, 0xB9, 0xBA, 0xBC, 0xBD, 0xBE,
                    0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, LCHA, 0x20, LOCK, 0x103, 0x104, LCHB,
            },
            {
                    0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF,
                    0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA, ECI, 0x1C, GS, RS, NS,
                    0xFB, 0xFC, 0xFD, 0xFE, 0xFF, 0xA1, 0xA8, 0xAB, 0xAF, 0xB0, 0xB4, 0xB7, 0xB8, 0xBB, 0xBF, 0x8A,
                    0x8B, 0x8C, 0x8D, 0x8E, 0x8F, 0x90, 0x91, 0x92, 0x93, 0x94, LCHA, 0x20, 0x102, LOCK, 0x104, LCHB,
            },
            {
                    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                    0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, ECI, PAD, PAD, 0x1B, NS,
                    0x1C, GS, RS, 0x1F, 0x9F, 0xA0, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA9, 0xAD, 0xAE, 0xB6,
                    0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, LCHA, 0x20, 0x102, 0x103, LOCK, LCHB,
            },
    };

    /** Bit number of each module, or negative when the module is not a data bit. */
    private static final int[][] BITNR = {
            {121, 120, 127, 126, 133, 132, 139, 138, 145, 144, 151, 150, 157, 156, 163, 162, 169, 168, 175, 174, 181, 180, 187, 186, 193, 192, 199, 198, -2, -2},
            {123, 122, 129, 128, 135, 134, 141, 140, 147, 146, 153, 152, 159, 158, 165, 164, 171, 170, 177, 176, 183, 182, 189, 188, 195, 194, 201, 200, 816, -3},
            {125, 124, 131, 130, 137, 136, 143, 142, 149, 148, 155, 154, 161, 160, 167, 166, 173, 172, 179, 178, 185, 184, 191, 190, 197, 196, 203, 202, 818, 817},
            {283, 282, 277, 276, 271, 270, 265, 264, 259, 258, 253, 252, 247, 246, 241, 240, 235, 234, 229, 228, 223, 222, 217, 216, 211, 210, 205, 204, 819, -3},
            {285, 284, 279, 278, 273, 272, 267, 266, 261, 260, 255, 254, 249, 248, 243, 242, 237, 236, 231, 230, 225, 224, 219, 218, 213, 212, 207, 206, 821, 820},
            {287, 286, 281, 280, 275, 274, 269, 268, 263, 262, 257, 256, 251, 250, 245, 244, 239, 238, 233, 232, 227, 226, 221, 220, 215, 214, 209, 208, 822, -3},
            {289, 288, 295, 294, 301, 300, 307, 306, 313, 312, 319, 318, 325, 324, 331, 330, 337, 336, 343, 342, 349, 348, 355, 354, 361, 360, 367, 366, 824, 823},
            {291, 290, 297, 296, 303, 302, 309, 308, 315, 314, 321, 320, 327, 326, 333, 332, 339, 338, 345, 344, 351, 350, 357, 356, 363, 362, 369, 368, 825, -3},
            {293, 292, 299, 298, 305, 304, 311, 310, 317, 316, 323, 322, 329, 328, 335, 334, 341, 340, 347, 346, 353, 352, 359, 358, 365, 364, 371, 370, 827, 826},
            {409, 408, 403, 402, 397, 396, 391, 390, 79, 78, -2, -2, 13, 12, 37, 36, 2, -1, 44, 43, 109, 108, 385, 384, 379, 378, 373, 372, 828, -3},
            {411, 410, 405, 404, 399, 398, 393, 392, 81, 80, 40, -2, 15, 14, 39, 38, 3, -1, -1, 45, 111, 110, 387, 386, 381, 380, 375, 374, 830, 829},
            {413, 412, 407, 406, 401, 400, 395, 394, 83, 82, 41, -3, -3, -3, -3, -3, 5, 4, 47, 46, 113, 112, 389, 388, 383, 382, 377, 376, 831, -3},
            {415, 414, 421, 420, 427, 426, 103, 102, 55, 54, 16, -3, -3, -3, -3, -3, -3, -3, 20, 19, 85, 84, 433, 432, 439, 438, 445, 444, 833, 832},
            {417, 416, 423, 422, 429, 428, 105, 104, 57, 56, -3, -3, -3, -3, -3, -3, -3, -3, 22, 21, 87, 86, 435, 434, 441, 440, 447, 446, 834, -3},
            {419, 418, 425, 424, 431, 430, 107, 106, 59, 58, -3, -3, -3, -3, -3, -3, -3, -3, -3, 23, 89, 88, 437, 436, 443, 442, 449, 448, 836, 835},
            {481, 480, 475, 474, 469, 468, 48, -2, 30, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, 0, 53, 52, 463, 462, 457, 456, 451, 450, 837, -3},
            {483, 482, 477, 476, 471, 470, 49, -1, -2, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -2, -1, 465, 464, 459, 458, 453, 452, 839, 838},
            {485, 484, 479, 478, 473, 472, 51, 50, 31, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, 1, -2, 42, 467, 466, 461, 460, 455, 454, 840, -3},
            {487, 486, 493, 492, 499, 498, 97, 96, 61, 60, -3, -3, -3, -3, -3, -3, -3, -3, -3, 26, 91, 90, 505, 504, 511, 510, 517, 516, 842, 841},
            {489, 488, 495, 494, 501, 500, 99, 98, 63, 62, -3, -3, -3, -3, -3, -3, -3, -3, 28, 27, 93, 92, 507, 506, 513, 512, 519, 518, 843, -3},
            {491, 490, 497, 496, 503, 502, 101, 100, 65, 64, 17, -3, -3, -3, -3, -3, -3, -3, 18, 29, 95, 94, 509, 508, 515, 514, 521, 520, 845, 844},
            {559, 558, 553, 552, 547, 546, 541, 540, 73, 72, 32, -3, -3, -3, -3, -3, -3, 10, 67, 66, 115, 114, 535, 534, 529, 528, 523, 522, 846, -3},
            {561, 560, 555, 554, 549, 548, 543, 542, 75, 74, -2, -1, 7, 6, 35, 34, 11, -2, 69, 68, 117, 116, 537, 536, 531, 530, 525, 524, 848, 847},
            {563, 562, 557, 556, 551, 550, 545, 544, 77, 76, -2, 33, 9, 8, 25, 24, -1, -2, 71, 70, 119, 118, 539, 538, 533, 532, 527, 526, 849, -3},
            {565, 564, 571, 570, 577, 576, 583, 582, 589, 588, 595, 594, 601, 600, 607, 606, 613, 612, 619, 618, 625, 624, 631, 630, 637, 636, 643, 642, 851, 850},
            {567, 566, 573, 572, 579, 578, 585, 584, 591, 590, 597, 596, 603, 602, 609, 608, 615, 614, 621, 620, 627, 626, 633, 632, 639, 638, 645, 644, 852, -3},
            {569, 568, 575, 574, 581, 580, 587, 586, 593, 592, 599, 598, 605, 604, 611, 610, 617, 616, 623, 622, 629, 628, 635, 634, 641, 640, 647, 646, 854, 853},
            {727, 726, 721, 720, 715, 714, 709, 708, 703, 702, 697, 696, 691, 690, 685, 684, 679, 678, 673, 672, 667, 666, 661, 660, 655, 654, 649, 648, 855, -3},
            {729, 728, 723, 722, 717, 716, 711, 710, 705, 704, 699, 698, 693, 692, 687, 686, 681, 680, 675, 674, 669, 668, 663, 662, 657, 656, 651, 650, 857, 856},
            {731, 730, 725, 724, 719, 718, 713, 712, 707, 706, 701, 700, 695, 694, 689, 688, 683, 682, 677, 676, 671, 670, 665, 664, 659, 658, 653, 652, 858, -3},
            {733, 732, 739, 738, 745, 744, 751, 750, 757, 756, 763, 762, 769, 768, 775, 774, 781, 780, 787, 786, 793, 792, 799, 798, 805, 804, 811, 810, 860, 859},
            {735, 734, 741, 740, 747, 746, 753, 752, 759, 758, 765, 764, 771, 770, 777, 776, 783, 782, 789, 788, 795, 794, 801, 800, 807, 806, 813, 812, 861, -3},
            {737, 736, 743, 742, 749, 748, 755, 754, 761, 760, 767, 766, 773, 772, 779, 778, 785, 784, 791, 790, 797, 796, 803, 802, 809, 808, 815, 814, 863, 862},
    };

    private MaxiCodeDecoder() {
    }

    static {
        if (SETS.length != 5) {
            throw new ExceptionInInitializerError("MaxiCode sets");
        }
        for (int[] set : SETS) {
            if (set.length != 64) {
                throw new ExceptionInInitializerError("MaxiCode set length");
            }
        }
        if (BITNR.length != HEIGHT) {
            throw new ExceptionInInitializerError("MaxiCode bit map");
        }
        for (int[] row : BITNR) {
            if (row.length != WIDTH) {
                throw new ExceptionInInitializerError("MaxiCode bit row");
            }
        }
    }

    /** Text plus the structured-append position. {@code index} and {@code count} are -1 when absent. */
    static final class Message {
        final String text;
        final int index;
        final int count;
        /** Raw message bytes after a mode 2/3 prefix insert, before charset decoding. */
        final byte[] bytes;

        Message(String text, int index, int count, byte[] bytes) {
            this.text = text;
            this.index = index;
            this.count = count;
            this.bytes = bytes;
        }
    }

    /** {@code true} is black. Returns null when the pure symbol cannot be read. */
    static String decode(boolean[][] image) {
        Message message = read(image);
        return message == null ? null : message.text;
    }

    /** Full read of a pure image, including structured append. Null when the symbol cannot be read. */
    static Message read(boolean[][] image) {
        boolean[][] bits = extract(image);
        return bits == null ? null : readModules(bits);
    }

    /** The 30 by 33 module grid sampled from a pure image, or null when the symbol is too small. */
    static boolean[][] sampled(boolean[][] image) {
        return extract(image);
    }

    /** {@code Decode(BitMatrix)} for an already sampled 30 by 33 module grid. */
    static String decodeModules(boolean[][] modules) {
        Message message = readModules(modules);
        return message == null ? null : message.text;
    }

    /** {@code Decode(BitMatrix)} including structured append. */
    static Message readModules(boolean[][] modules) {
        if (!rectangular(modules)) {
            return null;
        }
        return correct(readCodewords(modules));
    }

    /**
     * {@code DecodedBitStreamParser::Decode}. {@code data} is the corrected data codeword stream,
     * already padded. A format error returns null.
     */
    static Message message(int[] data, int mode) {
        if (data == null) {
            return null;
        }
        try {
            return parse(data, mode);
        } catch (Format | IndexOutOfBoundsException failed) {
            return null;
        }
    }

    private static Message parse(int[] data, int mode) {
        Out text = new Out();
        int[] append = {-1, -1};
        switch (mode) {
            case 2, 3 -> {
                String postcode = mode == 2 ? postcode2(data) : postcode3(data);
                String country = digits(Math.min(bits(data, 53, 54, 43, 44, 45, 46, 47, 48, 37, 38), 999), 3);
                String service = digits(Math.min(bits(data, 55, 56, 57, 58, 59, 60, 49, 50, 51, 52), 999), 3);
                readMessage(data, 10, 84, text, append);
                String prefix = postcode + GS + country + GS + service + GS;
                text.insert(text.startsWithCarrier() && text.size() >= 9 ? 9 : 0, prefix);
            }
            case 4, 6 -> readMessage(data, 1, 93, text, append);
            case 5 -> readMessage(data, 1, 77, text, append);
            default -> {
                return null;
            }
        }
        String value = text.finish();
        return new Message(value == null ? "" : value, append[0], append[1], text.raw());
    }

    /** {@code Decode(BitMatrix)}. Primary and secondary Reed-Solomon corrections both have to pass. */
    private static Message correct(int[] codewords) {
        if (!correct(codewords, 0, 10, 10, ALL)) {
            return null;
        }
        int mode = codewords[0] & 0x0F;
        int dataLength;
        switch (mode) {
            case 2, 3, 4, 6 -> {
                if (!correct(codewords, 20, 84, 40, EVEN) || !correct(codewords, 20, 84, 40, ODD)) {
                    return null;
                }
                dataLength = 94;
            }
            case 5 -> {
                if (!correct(codewords, 20, 68, 56, EVEN) || !correct(codewords, 20, 68, 56, ODD)) {
                    return null;
                }
                dataLength = 78;
            }
            default -> {
                return null;
            }
        }
        int[] data = new int[dataLength];
        System.arraycopy(codewords, 0, data, 0, 10);
        System.arraycopy(codewords, 20, data, 10, dataLength - 10);
        return message(data, mode);
    }

    /** {@code CorrectErrors}. Even and odd modes keep every other codeword. */
    private static boolean correct(int[] codewords, int start, int dataCodewords, int ecCodewords, int mode) {
        int total = dataCodewords + ecCodewords;
        int divisor = mode == ALL ? 1 : 2;
        int[] words = new int[total / divisor];
        for (int i = 0; i < total; i++) {
            if (mode == ALL || i % 2 == mode - 1) {
                words[i / divisor] = codewords[i + start];
            }
        }
        try {
            REED_SOLOMON.decode(words, ecCodewords / divisor);
        } catch (ReedSolomonException failed) {
            return false;
        }
        for (int i = 0; i < dataCodewords; i++) {
            if (mode == ALL || i % 2 == mode - 1) {
                codewords[i + start] = words[i / divisor];
            }
        }
        return true;
    }

    /** {@code ReadCodewords}. The matrix is the 33 by 30 module grid. */
    private static int[] readCodewords(boolean[][] bits) {
        int[] result = new int[144];
        for (int y = 0; y < bits.length && y < HEIGHT; y++) {
            for (int x = 0; x < bits[y].length && x < WIDTH; x++) {
                int bit = BITNR[y][x];
                if (bit >= 0 && bits[y][x]) {
                    result[bit / 6] |= 1 << (5 - (bit % 6));
                }
            }
        }
        return result;
    }

    /** {@code ExtractPureBits}. Odd rows are sampled half a module to the right. */
    private static boolean[][] extract(boolean[][] image) {
        if (!rectangular(image)) {
            return null;
        }
        int[] box = bounds(image);
        if (box == null) {
            return null;
        }
        int left = box[0];
        int top = box[1];
        int width = box[2];
        int height = box[3];
        boolean[][] bits = new boolean[HEIGHT][WIDTH];
        int imageWidth = image[0].length;
        for (int y = 0; y < HEIGHT; y++) {
            int iy = top + (y * height + height / 2) / HEIGHT;
            for (int x = 0; x < WIDTH; x++) {
                int ix = left + (x * width + width / 2 + (y & 1) * width / 2) / WIDTH;
                if (iy >= 0 && iy < image.length && ix >= 0 && ix < imageWidth && image[iy][ix]) {
                    bits[y][x] = true;
                }
            }
        }
        return bits;
    }

    /** {@code BitMatrix::findBoundingBox} with the module-grid width as the minimum span. */
    private static int[] bounds(boolean[][] image) {
        int width = image[0].length;
        int height = image.length;
        int first = -1;
        int last = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (image[y][x]) {
                    if (first < 0) {
                        first = y * width + x;
                    }
                    last = y * width + x;
                }
            }
        }
        if (first < 0) {
            return null;
        }
        int top = first / width;
        int left = first % width;
        int bottom = last / width;
        int right = last % width;
        if (bottom - top + 1 < WIDTH) {
            return null;
        }
        for (int y = top; y <= bottom; y++) {
            for (int x = 0; x < left; x++) {
                if (image[y][x]) {
                    left = x;
                    break;
                }
            }
            for (int x = width - 1; x > right; x--) {
                if (image[y][x]) {
                    right = x;
                    break;
                }
            }
        }
        int boxWidth = right - left + 1;
        int boxHeight = bottom - top + 1;
        if (boxWidth < WIDTH || boxHeight < WIDTH) {
            return null;
        }
        return new int[] {left, top, boxWidth, boxHeight};
    }

    private static boolean rectangular(boolean[][] image) {
        if (image == null || image.length == 0 || image[0] == null || image[0].length == 0) {
            return false;
        }
        int width = image[0].length;
        for (boolean[] row : image) {
            if (row == null || row.length != width) {
                return false;
            }
        }
        return true;
    }

    /** {@code GetMessage}. A numeric shift appends nine digits and consumes the next five codewords. */
    private static void readMessage(int[] data, int start, int length, Out text, int[] append) {
        int shift = -1;
        int set = 0;
        int lastSet = 0;
        for (int i = start; i < start + length; i++) {
            if (data[i] < 0 || data[i] >= 64) {
                throw new Format();
            }
            int code = SETS[set][data[i]];
            switch (code) {
                case LCHA -> {
                    set = 0;
                    shift = -1;
                }
                case LCHB -> {
                    set = 1;
                    shift = -1;
                }
                case SHI0, 0x101, 0x102, 0x103, 0x104 -> {
                    lastSet = set;
                    set = code - SHI0;
                    shift = 1;
                }
                case 0x105 -> {
                    lastSet = set;
                    set = 0;
                    shift = 2;
                }
                case 0x106 -> {
                    lastSet = set;
                    set = 0;
                    shift = 3;
                }
                case NS -> {
                    if (i + 5 >= data.length) {
                        throw new Format();
                    }
                    int value = (data[i + 1] << 24) + (data[i + 2] << 18) + (data[i + 3] << 12)
                            + (data[i + 4] << 6) + data[i + 5];
                    text.append(digits(value, 9));
                    i += 5;
                }
                case LOCK -> shift = -1;
                case ECI -> {
                    int[] cursor = {i};
                    text.eci(readEci(data, cursor));
                    i = cursor[0];
                }
                case PAD -> {
                    if (i == start) {
                        int symbol = data[++i];
                        append[0] = (symbol >> 3) & 0x07;
                        append[1] = (symbol & 0x07) + 1;
                        if (append[1] == 1 || append[1] <= append[0]) {
                            append[1] = 0;
                        }
                    }
                    shift = -1;
                }
                default -> text.push(code);
            }
            if (shift-- == 0) {
                set = lastSet;
            }
        }
    }

    /**
     * {@code ParseECIValue}. {@code index} is the codeword of the ECI latch and moves to the last
     * ECI byte. The for-loop then steps past that byte.
     */
    private static int readEci(int[] data, int[] index) {
        int first = at(data, ++index[0]);
        if ((first & 0x20) == 0) {
            return first;
        }
        int second = at(data, ++index[0]);
        if ((first & 0x10) == 0) {
            return ((first & 0x0F) << 6) | second;
        }
        int third = at(data, ++index[0]);
        if ((first & 0x08) == 0) {
            return ((first & 0x07) << 12) | (second << 6) | third;
        }
        int fourth = at(data, ++index[0]);
        return ((first & 0x03) << 18) | (second << 12) | (third << 6) | fourth;
    }

    private static int at(int[] data, int index) {
        if (index < 0 || index >= data.length) {
            throw new Format();
        }
        return data[index];
    }

    private static String postcode2(int[] data) {
        int value = bits(data, 33, 34, 35, 36, 25, 26, 27, 28, 29, 30, 19, 20, 21, 22, 23, 24, 13, 14, 15, 16, 17, 18,
                7, 8, 9, 10, 11, 12, 1, 2);
        int length = Math.min(bits(data, 39, 40, 41, 42, 31, 32), 9);
        String digits = Integer.toString(value);
        if (digits.length() >= length) {
            return digits.substring(0, length);
        }
        return "0".repeat(length - digits.length()) + digits;
    }

    private static String postcode3(int[] data) {
        return ""
                + (char) SETS[0][bits(data, 39, 40, 41, 42, 31, 32)]
                + (char) SETS[0][bits(data, 33, 34, 35, 36, 25, 26)]
                + (char) SETS[0][bits(data, 27, 28, 29, 30, 19, 20)]
                + (char) SETS[0][bits(data, 21, 22, 23, 24, 13, 14)]
                + (char) SETS[0][bits(data, 15, 16, 17, 18, 7, 8)]
                + (char) SETS[0][bits(data, 9, 10, 11, 12, 1, 2)];
    }

    private static int bits(int[] data, int... positions) {
        int value = 0;
        for (int position : positions) {
            int bit = position - 1;
            int on = (data[bit / 6] & (1 << (5 - (bit % 6)))) == 0 ? 0 : 1;
            value = (value << 1) | on;
        }
        return value;
    }

    /** {@code ToString}. A value that does not fit the width is a format error. */
    private static String digits(int value, int length) {
        if (value < 0) {
            throw new Format();
        }
        char[] out = new char[length];
        Arrays.fill(out, '0');
        for (int i = length - 1; i >= 0 && value != 0; i--, value /= 10) {
            out[i] = (char) ('0' + value % 10);
        }
        if (value != 0) {
            throw new Format();
        }
        return new String(out);
    }

    private static final class Format extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** ISO-8859-1 until an ECI. Marks are byte positions, so a prefix insert shifts them. */
    private static final class Out {
        private final List<Integer> bytes = new ArrayList<>();
        private final List<Integer> marks = new ArrayList<>();
        private final List<Integer> sets = new ArrayList<>();
        private boolean hasEci;

        void push(int value) {
            bytes.add(value & 0xFF);
        }

        void append(String value) {
            for (int i = 0; i < value.length(); i++) {
                push(value.charAt(i));
            }
        }

        void eci(int eci) {
            if (!hasEci) {
                marks.clear();
                sets.clear();
            }
            hasEci = true;
            marks.add(bytes.size());
            sets.add(eci);
        }

        int size() {
            return bytes.size();
        }

        byte[] raw() {
            byte[] copy = new byte[bytes.size()];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = bytes.get(i).byteValue();
            }
            return copy;
        }

        boolean startsWithCarrier() {
            int[] header = {'[', ')', '>', RS, '0', '1', GS};
            if (bytes.size() < header.length) {
                return false;
            }
            for (int i = 0; i < header.length; i++) {
                if (bytes.get(i) != header[i]) {
                    return false;
                }
            }
            return true;
        }

        /** {@code Content::insert}. Encodings strictly after the position move with the new bytes. */
        void insert(int pos, String value) {
            for (int i = 0; i < value.length(); i++) {
                bytes.add(pos + i, (int) value.charAt(i));
            }
            for (int i = 0; i < marks.size(); i++) {
                if (marks.get(i) > pos) {
                    marks.set(i, marks.get(i) + value.length());
                }
            }
        }

        String finish() {
            if (bytes.isEmpty()) {
                return null;
            }
            byte[] raw = new byte[bytes.size()];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = bytes.get(i).byteValue();
            }
            if (!hasEci) {
                return new String(raw, StandardCharsets.ISO_8859_1);
            }
            StringBuilder builder = new StringBuilder();
            if (marks.get(0) > 0) {
                builder.append(new String(raw, 0, marks.get(0), StandardCharsets.ISO_8859_1));
            }
            for (int i = 0; i < marks.size(); i++) {
                int from = marks.get(i);
                int to = i + 1 < marks.size() ? marks.get(i + 1) : raw.length;
                if (from != to) {
                    builder.append(TextDecoder.decodeOrLatin1(Arrays.copyOfRange(raw, from, to), sets.get(i)));
                }
            }
            return builder.isEmpty() ? null : builder.toString();
        }
    }
}
