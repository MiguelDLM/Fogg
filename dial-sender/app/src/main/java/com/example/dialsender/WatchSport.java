package com.example.dialsender;

import android.content.Context;
import android.util.SparseArray;

/**
 * The watch's own workout-type table, mode 7..162.
 *
 * This app used to decode the mode byte against its own 1..12 list, so a watch
 * record with mode 12 ("Walking") showed up as "Escalada" and anything outside
 * that range — a real session came back as mode 116 — rendered as "Sport 116".
 * The names here are lifted from the original CO-FIT/SMA app, whose resources
 * are keyed workout&lt;mode&gt;; see the decompiled app's res/values-* strings.
 *
 * Icons only exist for the activities this app draws; everything else falls
 * back to a neutral one rather than borrowing a misleading glyph.
 */
public final class WatchSport {

    private static final class Entry {
        final int nameRes;
        final int iconRes;

        Entry(int nameRes, int iconRes) {
            this.nameRes = nameRes;
            this.iconRes = iconRes;
        }
    }

    private static final SparseArray<Entry> MODES = new SparseArray<>();

    static {
        MODES.put(7, new Entry(R.string.workout_mode_7, R.drawable.ic_sport_run));
        MODES.put(8, new Entry(R.string.workout_mode_8, R.drawable.ic_sport_treadmill));
        MODES.put(9, new Entry(R.string.workout_mode_9, R.drawable.ic_sport_run));
        MODES.put(10, new Entry(R.string.workout_mode_10, R.drawable.ic_sport_cycling));
        MODES.put(11, new Entry(R.string.workout_mode_11, R.drawable.ic_sport_swim));
        MODES.put(12, new Entry(R.string.workout_mode_12, R.drawable.ic_sport_walk));
        MODES.put(13, new Entry(R.string.workout_mode_13, R.drawable.ic_sport_climb));
        MODES.put(14, new Entry(R.string.workout_mode_14, R.drawable.ic_sport_yoga));
        MODES.put(15, new Entry(R.string.workout_mode_15, R.drawable.ic_sport_cycling));
        MODES.put(16, new Entry(R.string.workout_mode_16, R.drawable.ic_sport_basketball));
        MODES.put(17, new Entry(R.string.workout_mode_17, R.drawable.ic_sport_football));
        MODES.put(18, new Entry(R.string.workout_mode_18, R.drawable.ic_sport_generic));
        MODES.put(19, new Entry(R.string.workout_mode_19, R.drawable.ic_sport_run));
        MODES.put(20, new Entry(R.string.workout_mode_20, R.drawable.ic_sport_walk));
        MODES.put(21, new Entry(R.string.workout_mode_21, R.drawable.ic_sport_generic));
        MODES.put(22, new Entry(R.string.workout_mode_22, R.drawable.ic_sport_generic));
        MODES.put(23, new Entry(R.string.workout_mode_23, R.drawable.ic_sport_generic));
        MODES.put(24, new Entry(R.string.workout_mode_24, R.drawable.ic_sport_generic));
        MODES.put(25, new Entry(R.string.workout_mode_25, R.drawable.ic_sport_generic));
        MODES.put(26, new Entry(R.string.workout_mode_26, R.drawable.ic_sport_jump_rope));
        MODES.put(27, new Entry(R.string.workout_mode_27, R.drawable.ic_sport_climb));
        MODES.put(28, new Entry(R.string.workout_mode_28, R.drawable.ic_sport_generic));
        MODES.put(29, new Entry(R.string.workout_mode_29, R.drawable.ic_sport_generic));
        MODES.put(30, new Entry(R.string.workout_mode_30, R.drawable.ic_sport_generic));
        MODES.put(32, new Entry(R.string.workout_mode_32, R.drawable.ic_sport_generic));
        MODES.put(33, new Entry(R.string.workout_mode_33, R.drawable.ic_sport_generic));
        MODES.put(34, new Entry(R.string.workout_mode_34, R.drawable.ic_sport_generic));
        MODES.put(35, new Entry(R.string.workout_mode_35, R.drawable.ic_sport_generic));
        MODES.put(36, new Entry(R.string.workout_mode_36, R.drawable.ic_sport_generic));
        MODES.put(37, new Entry(R.string.workout_mode_37, R.drawable.ic_sport_generic));
        MODES.put(38, new Entry(R.string.workout_mode_38, R.drawable.ic_sport_yoga));
        MODES.put(39, new Entry(R.string.workout_mode_39, R.drawable.ic_sport_generic));
        MODES.put(40, new Entry(R.string.workout_mode_40, R.drawable.ic_sport_generic));
        MODES.put(41, new Entry(R.string.workout_mode_41, R.drawable.ic_sport_generic));
        MODES.put(42, new Entry(R.string.workout_mode_42, R.drawable.ic_sport_generic));
        MODES.put(43, new Entry(R.string.workout_mode_43, R.drawable.ic_sport_generic));
        MODES.put(44, new Entry(R.string.workout_mode_44, R.drawable.ic_sport_generic));
        MODES.put(45, new Entry(R.string.workout_mode_45, R.drawable.ic_sport_generic));
        MODES.put(46, new Entry(R.string.workout_mode_46, R.drawable.ic_sport_generic));
        MODES.put(47, new Entry(R.string.workout_mode_47, R.drawable.ic_sport_generic));
        MODES.put(48, new Entry(R.string.workout_mode_48, R.drawable.ic_sport_generic));
        MODES.put(49, new Entry(R.string.workout_mode_49, R.drawable.ic_sport_generic));
        MODES.put(50, new Entry(R.string.workout_mode_50, R.drawable.ic_sport_hike));
        MODES.put(51, new Entry(R.string.workout_mode_51, R.drawable.ic_sport_generic));
        MODES.put(52, new Entry(R.string.workout_mode_52, R.drawable.ic_sport_row));
        MODES.put(53, new Entry(R.string.workout_mode_53, R.drawable.ic_sport_generic));
        MODES.put(54, new Entry(R.string.workout_mode_54, R.drawable.ic_sport_generic));
        MODES.put(55, new Entry(R.string.workout_mode_55, R.drawable.ic_sport_generic));
        MODES.put(56, new Entry(R.string.workout_mode_56, R.drawable.ic_sport_generic));
        MODES.put(57, new Entry(R.string.workout_mode_57, R.drawable.ic_sport_generic));
        MODES.put(58, new Entry(R.string.workout_mode_58, R.drawable.ic_sport_generic));
        MODES.put(59, new Entry(R.string.workout_mode_59, R.drawable.ic_sport_generic));
        MODES.put(60, new Entry(R.string.workout_mode_60, R.drawable.ic_sport_yoga));
        MODES.put(61, new Entry(R.string.workout_mode_61, R.drawable.ic_sport_generic));
        MODES.put(62, new Entry(R.string.workout_mode_62, R.drawable.ic_sport_generic));
        MODES.put(63, new Entry(R.string.workout_mode_63, R.drawable.ic_sport_generic));
        MODES.put(64, new Entry(R.string.workout_mode_64, R.drawable.ic_sport_generic));
        MODES.put(65, new Entry(R.string.workout_mode_65, R.drawable.ic_sport_generic));
        MODES.put(66, new Entry(R.string.workout_mode_66, R.drawable.ic_sport_generic));
        MODES.put(67, new Entry(R.string.workout_mode_67, R.drawable.ic_sport_generic));
        MODES.put(68, new Entry(R.string.workout_mode_68, R.drawable.ic_sport_generic));
        MODES.put(69, new Entry(R.string.workout_mode_69, R.drawable.ic_sport_generic));
        MODES.put(70, new Entry(R.string.workout_mode_70, R.drawable.ic_sport_generic));
        MODES.put(71, new Entry(R.string.workout_mode_71, R.drawable.ic_sport_generic));
        MODES.put(72, new Entry(R.string.workout_mode_72, R.drawable.ic_sport_generic));
        MODES.put(73, new Entry(R.string.workout_mode_73, R.drawable.ic_sport_generic));
        MODES.put(74, new Entry(R.string.workout_mode_74, R.drawable.ic_sport_generic));
        MODES.put(75, new Entry(R.string.workout_mode_75, R.drawable.ic_sport_generic));
        MODES.put(76, new Entry(R.string.workout_mode_76, R.drawable.ic_sport_generic));
        MODES.put(77, new Entry(R.string.workout_mode_77, R.drawable.ic_sport_generic));
        MODES.put(78, new Entry(R.string.workout_mode_78, R.drawable.ic_sport_generic));
        MODES.put(79, new Entry(R.string.workout_mode_79, R.drawable.ic_sport_generic));
        MODES.put(80, new Entry(R.string.workout_mode_80, R.drawable.ic_sport_generic));
        MODES.put(81, new Entry(R.string.workout_mode_81, R.drawable.ic_sport_generic));
        MODES.put(82, new Entry(R.string.workout_mode_82, R.drawable.ic_sport_generic));
        MODES.put(83, new Entry(R.string.workout_mode_83, R.drawable.ic_sport_generic));
        MODES.put(84, new Entry(R.string.workout_mode_84, R.drawable.ic_sport_climb));
        MODES.put(85, new Entry(R.string.workout_mode_85, R.drawable.ic_sport_generic));
        MODES.put(86, new Entry(R.string.workout_mode_86, R.drawable.ic_sport_run));
        MODES.put(87, new Entry(R.string.workout_mode_87, R.drawable.ic_sport_generic));
        MODES.put(88, new Entry(R.string.workout_mode_88, R.drawable.ic_sport_generic));
        MODES.put(89, new Entry(R.string.workout_mode_89, R.drawable.ic_sport_generic));
        MODES.put(90, new Entry(R.string.workout_mode_90, R.drawable.ic_sport_generic));
        MODES.put(91, new Entry(R.string.workout_mode_91, R.drawable.ic_sport_generic));
        MODES.put(92, new Entry(R.string.workout_mode_92, R.drawable.ic_sport_generic));
        MODES.put(93, new Entry(R.string.workout_mode_93, R.drawable.ic_sport_generic));
        MODES.put(94, new Entry(R.string.workout_mode_94, R.drawable.ic_sport_generic));
        MODES.put(95, new Entry(R.string.workout_mode_95, R.drawable.ic_sport_generic));
        MODES.put(96, new Entry(R.string.workout_mode_96, R.drawable.ic_sport_cycling));
        MODES.put(97, new Entry(R.string.workout_mode_97, R.drawable.ic_sport_cycling));
        MODES.put(98, new Entry(R.string.workout_mode_98, R.drawable.ic_sport_row));
        MODES.put(99, new Entry(R.string.workout_mode_99, R.drawable.ic_sport_generic));
        MODES.put(100, new Entry(R.string.workout_mode_100, R.drawable.ic_sport_swim));
        MODES.put(101, new Entry(R.string.workout_mode_101, R.drawable.ic_sport_row));
        MODES.put(102, new Entry(R.string.workout_mode_102, R.drawable.ic_sport_generic));
        MODES.put(103, new Entry(R.string.workout_mode_103, R.drawable.ic_sport_generic));
        MODES.put(104, new Entry(R.string.workout_mode_104, R.drawable.ic_sport_generic));
        MODES.put(105, new Entry(R.string.workout_mode_105, R.drawable.ic_sport_generic));
        MODES.put(106, new Entry(R.string.workout_mode_106, R.drawable.ic_sport_generic));
        MODES.put(107, new Entry(R.string.workout_mode_107, R.drawable.ic_sport_generic));
        MODES.put(108, new Entry(R.string.workout_mode_108, R.drawable.ic_sport_swim));
        MODES.put(109, new Entry(R.string.workout_mode_109, R.drawable.ic_sport_swim));
        MODES.put(110, new Entry(R.string.workout_mode_110, R.drawable.ic_sport_climb));
        MODES.put(111, new Entry(R.string.workout_mode_111, R.drawable.ic_sport_generic));
        MODES.put(112, new Entry(R.string.workout_mode_112, R.drawable.ic_sport_generic));
        MODES.put(113, new Entry(R.string.workout_mode_113, R.drawable.ic_sport_yoga));
        MODES.put(114, new Entry(R.string.workout_mode_114, R.drawable.ic_sport_generic));
        MODES.put(115, new Entry(R.string.workout_mode_115, R.drawable.ic_sport_generic));
        MODES.put(116, new Entry(R.string.workout_mode_116, R.drawable.ic_sport_generic));
        MODES.put(117, new Entry(R.string.workout_mode_117, R.drawable.ic_sport_generic));
        MODES.put(118, new Entry(R.string.workout_mode_118, R.drawable.ic_sport_generic));
        MODES.put(119, new Entry(R.string.workout_mode_119, R.drawable.ic_sport_generic));
        MODES.put(120, new Entry(R.string.workout_mode_120, R.drawable.ic_sport_generic));
        MODES.put(121, new Entry(R.string.workout_mode_121, R.drawable.ic_sport_generic));
        MODES.put(122, new Entry(R.string.workout_mode_122, R.drawable.ic_sport_generic));
        MODES.put(123, new Entry(R.string.workout_mode_123, R.drawable.ic_sport_generic));
        MODES.put(124, new Entry(R.string.workout_mode_124, R.drawable.ic_sport_generic));
        MODES.put(125, new Entry(R.string.workout_mode_125, R.drawable.ic_sport_generic));
        MODES.put(126, new Entry(R.string.workout_mode_126, R.drawable.ic_sport_generic));
        MODES.put(127, new Entry(R.string.workout_mode_127, R.drawable.ic_sport_generic));
        MODES.put(128, new Entry(R.string.workout_mode_128, R.drawable.ic_sport_generic));
        MODES.put(129, new Entry(R.string.workout_mode_129, R.drawable.ic_sport_generic));
        MODES.put(130, new Entry(R.string.workout_mode_130, R.drawable.ic_sport_generic));
        MODES.put(131, new Entry(R.string.workout_mode_131, R.drawable.ic_sport_generic));
        MODES.put(132, new Entry(R.string.workout_mode_132, R.drawable.ic_sport_generic));
        MODES.put(133, new Entry(R.string.workout_mode_133, R.drawable.ic_sport_generic));
        MODES.put(134, new Entry(R.string.workout_mode_134, R.drawable.ic_sport_generic));
        MODES.put(135, new Entry(R.string.workout_mode_135, R.drawable.ic_sport_generic));
        MODES.put(136, new Entry(R.string.workout_mode_136, R.drawable.ic_sport_generic));
        MODES.put(137, new Entry(R.string.workout_mode_137, R.drawable.ic_sport_generic));
        MODES.put(138, new Entry(R.string.workout_mode_138, R.drawable.ic_sport_generic));
        MODES.put(139, new Entry(R.string.workout_mode_139, R.drawable.ic_sport_generic));
        MODES.put(140, new Entry(R.string.workout_mode_140, R.drawable.ic_sport_generic));
        MODES.put(141, new Entry(R.string.workout_mode_141, R.drawable.ic_sport_generic));
        MODES.put(142, new Entry(R.string.workout_mode_142, R.drawable.ic_sport_generic));
        MODES.put(143, new Entry(R.string.workout_mode_143, R.drawable.ic_sport_generic));
        MODES.put(144, new Entry(R.string.workout_mode_144, R.drawable.ic_sport_generic));
        MODES.put(145, new Entry(R.string.workout_mode_145, R.drawable.ic_sport_generic));
        MODES.put(146, new Entry(R.string.workout_mode_146, R.drawable.ic_sport_generic));
        MODES.put(147, new Entry(R.string.workout_mode_147, R.drawable.ic_sport_generic));
        MODES.put(148, new Entry(R.string.workout_mode_148, R.drawable.ic_sport_generic));
        MODES.put(149, new Entry(R.string.workout_mode_149, R.drawable.ic_sport_generic));
        MODES.put(150, new Entry(R.string.workout_mode_150, R.drawable.ic_sport_generic));
        MODES.put(151, new Entry(R.string.workout_mode_151, R.drawable.ic_sport_generic));
        MODES.put(152, new Entry(R.string.workout_mode_152, R.drawable.ic_sport_generic));
        MODES.put(153, new Entry(R.string.workout_mode_153, R.drawable.ic_sport_generic));
        MODES.put(154, new Entry(R.string.workout_mode_154, R.drawable.ic_sport_generic));
        MODES.put(155, new Entry(R.string.workout_mode_155, R.drawable.ic_sport_generic));
        MODES.put(156, new Entry(R.string.workout_mode_156, R.drawable.ic_sport_generic));
        MODES.put(157, new Entry(R.string.workout_mode_157, R.drawable.ic_sport_generic));
        MODES.put(158, new Entry(R.string.workout_mode_158, R.drawable.ic_sport_generic));
        MODES.put(159, new Entry(R.string.workout_mode_159, R.drawable.ic_sport_generic));
        MODES.put(160, new Entry(R.string.workout_mode_160, R.drawable.ic_sport_generic));
        MODES.put(161, new Entry(R.string.workout_mode_161, R.drawable.ic_sport_generic));
        MODES.put(162, new Entry(R.string.workout_mode_162, R.drawable.ic_sport_generic));
    }

    private WatchSport() {
    }

    public static boolean isKnown(int mode) {
        return MODES.get(mode) != null;
    }

    public static String name(Context context, int mode) {
        Entry e = MODES.get(mode);
        if (e != null)
            return context.getString(e.nameRes);
        return context.getString(R.string.sport_generic, mode);
    }

    /** Reverse lookup for history rows, which only store the localised name. */
    public static int iconForName(Context context, String name) {
        if (name == null || name.trim().isEmpty())
            return R.drawable.ic_sport_generic;
        for (int i = 0; i < MODES.size(); i++) {
            Entry e = MODES.valueAt(i);
            if (name.equalsIgnoreCase(context.getString(e.nameRes)))
                return e.iconRes;
        }
        return R.drawable.ic_sport_generic;
    }

    public static int icon(int mode) {
        Entry e = MODES.get(mode);
        return e != null ? e.iconRes : R.drawable.ic_sport_generic;
    }
}
