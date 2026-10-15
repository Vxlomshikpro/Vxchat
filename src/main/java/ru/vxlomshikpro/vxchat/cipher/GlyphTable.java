package ru.vxlomshikpro.vxchat.cipher;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Таблица подстановки «один символ → один иероглиф» (раздел 1 ТЗ).
 *
 * <p>Шифр <b>статический</b>: одна и та же таблица у всех, пароль на неё не влияет.
 * Пароль используется только в хендшейке ({@link ru.vxlomshikpro.vxchat.HandshakeManager}),
 * чтобы подтвердить, что обе стороны — «свои».
 *
 * <p>Состав: 26 латинских букв, 33 кириллические (включая {@code ё}), 10 цифр
 * и пробел. Итого 70 символов и ровно 70 разных иероглифов.
 *
 * <p>Знаки препинания и любые символы вне таблицы при шифровании остаются как есть
 * (пункт 1 ТЗ), поэтому таблица ограничена буквами, цифрами и пробелом.
 */
public final class GlyphTable {

    /** 26 латинских + 33 кириллических + 10 цифр + пробел. */
    public static final int EXPECTED_SIZE = 70;

    /**
     * Границы CJK, которые точно рисует шрифт Minecraft (unifont).
     *
     * <p>Снизу U+3000, а не U+4E00: ноль записывается как 〇 (U+3007, CJK
     * Ideographic Number Zero) — он левее блока Unified и рисуется нормально.
     */
    private static final int CJK_FIRST = 0x3000;
    private static final int CJK_LAST = 0x9FFF;

    /** Прямая таблица: символ → иероглиф. */
    private static final Map<Character, Character> FORWARD = new LinkedHashMap<>();

    /** Обратная таблица: иероглиф → символ. Собирается из FORWARD. */
    private static final Map<Character, Character> BACKWARD = new HashMap<>();

    /** Все иероглифы таблицы — для генерации nonce в хендшейке. */
    private static final char[] GLYPHS;

    static {
        // --- Латиница (26) ---
        FORWARD.put('a', '秋');
        FORWARD.put('b', '鸟');
        FORWARD.put('c', '猫');
        FORWARD.put('d', '狗');
        FORWARD.put('e', '耳');
        FORWARD.put('f', '火');
        FORWARD.put('g', '金');
        FORWARD.put('h', '手');
        FORWARD.put('i', '冰');
        FORWARD.put('j', '玉');
        FORWARD.put('k', '王');
        FORWARD.put('l', '光');
        FORWARD.put('m', '月');
        FORWARD.put('n', '夜');
        FORWARD.put('o', '海');
        FORWARD.put('p', '人');
        FORWARD.put('q', '后');
        FORWARD.put('r', '雨');
        FORWARD.put('s', '星');
        FORWARD.put('t', '树');
        FORWARD.put('u', '伞');
        FORWARD.put('v', '谷');
        FORWARD.put('w', '水');
        FORWARD.put('x', '虾');
        FORWARD.put('y', '黄');
        FORWARD.put('z', '禅');

        // --- Кириллица (33) ---
        FORWARD.put('а', '瓜'); // а
        FORWARD.put('б', '桦'); // б
        FORWARD.put('в', '风'); // в
        FORWARD.put('г', '山'); // г
        FORWARD.put('д', '家'); // д
        FORWARD.put('е', '杉'); // е
        FORWARD.put('ё', '刺'); // ё
        FORWARD.put('ж', '虫'); // ж
        FORWARD.put('з', '土'); // з
        FORWARD.put('и', '针'); // и
        FORWARD.put('й', '碘'); // й
        FORWARD.put('к', '书'); // к
        FORWARD.put('л', '林'); // л
        FORWARD.put('м', '世'); // м
        FORWARD.put('н', '天'); // н
        FORWARD.put('о', '焰'); // о
        FORWARD.put('п', '果'); // п
        FORWARD.put('р', '河'); // р
        FORWARD.put('с', '日'); // с
        FORWARD.put('т', '影'); // т
        FORWARD.put('у', '晨'); // у
        FORWARD.put('ф', '旗'); // ф
        FORWARD.put('х', '饼'); // х
        FORWARD.put('ц', '花'); // ц
        FORWARD.put('ч', '茶'); // ч
        FORWARD.put('ш', '球'); // ш
        FORWARD.put('щ', '盾'); // щ
        FORWARD.put('ъ', '钥'); // ъ
        FORWARD.put('ы', '鱼'); // ы
        FORWARD.put('ь', '笔'); // ь
        FORWARD.put('э', '声'); // э
        FORWARD.put('ю', '南'); // ю
        FORWARD.put('я', '苹'); // я

        // --- Цифры (10) ---
        FORWARD.put('0', '〇'); // 〇
        FORWARD.put('1', '一'); // 一
        FORWARD.put('2', '二'); // 二
        FORWARD.put('3', '三'); // 三
        FORWARD.put('4', '四'); // 四
        FORWARD.put('5', '五'); // 五
        FORWARD.put('6', '六'); // 六
        FORWARD.put('7', '七'); // 七
        FORWARD.put('8', '八'); // 八
        FORWARD.put('9', '九'); // 九

        // --- Пробел (1) ---
        FORWARD.put(' ', '之'); // 之

        validate();
        for (Map.Entry<Character, Character> entry : FORWARD.entrySet()) {
            BACKWARD.put(entry.getValue(), entry.getKey());
        }
        GLYPHS = new char[FORWARD.size()];
        int i = 0;
        for (Character glyph : FORWARD.values()) {
            GLYPHS[i++] = glyph;
        }
    }

    /**
     * Проверки таблицы при загрузке класса (пункт 1 ТЗ: «проверить при сборке,
     * что в таблице нет дублей иероглифов»).
     *
     * <p>Дубль иероглифов — фатальная ошибка: два символа, кодируемые одним
     * иероглифом, ломают расшифровку необратимо. Лучше упасть на старте, чем
     * молча портить чат. Все проверки выполняются в байткоде при первом
     * обращении к шифру, то есть на каждом запуске игры.
     */
    private static void validate() {
        Map<Character, Character> seen = new HashMap<>();
        StringBuilder errors = new StringBuilder();

        for (Map.Entry<Character, Character> e : FORWARD.entrySet()) {
            Character symbol = e.getKey();
            Character glyph = e.getValue();

            Character owner = seen.put(glyph, symbol);
            if (owner != null) {
                errors.append("дубль иероглифа '").append(glyph)
                        .append("' у символов '").append(owner)
                        .append("' и '").append(symbol).append("'; ");
            }
            if (glyph < CJK_FIRST || glyph > CJK_LAST) {
                errors.append("иероглиф '").append(glyph).append("' (символ '")
                        .append(symbol).append("') вне диапазона CJK U+3000..U+9FFF; ");
            }
            if (Character.isSurrogate(glyph)) {
                errors.append("иероглиф '").append(glyph).append("' — суррогатный, "
                        + "шрифт Minecraft его не нарисует; ");
            }
        }

        if (FORWARD.size() != EXPECTED_SIZE) {
            errors.append("ожидалось ").append(EXPECTED_SIZE)
                    .append(" символов, в таблице ").append(FORWARD.size()).append("; ");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("[VXChat] Таблица шифра некорректна: " + errors);
        }
    }

    private GlyphTable() {
    }

    /** Прямая таблица (только для чтения). */
    public static Map<Character, Character> forward() {
        return Collections.unmodifiableMap(FORWARD);
    }

    /** Обратная таблица (только для чтения). */
    public static Map<Character, Character> backward() {
        return Collections.unmodifiableMap(BACKWARD);
    }

    /** Есть ли глиф для символа. */
    public static boolean has(char symbol) {
        return FORWARD.containsKey(symbol);
    }

    /** Глиф для символа либо {@code 0}, если символ вне таблицы. */
    public static char glyphOf(char symbol) {
        Character glyph = FORWARD.get(symbol);
        return glyph == null ? 0 : glyph;
    }

    /** Символ по глифу либо {@code null}. */
    public static Character symbolOf(char glyph) {
        return BACKWARD.get(glyph);
    }

    /** Является ли символ глифом этого шифра. */
    public static boolean isGlyph(char c) {
        return BACKWARD.containsKey(c);
    }

    /** Копия массива всех иероглифов — источник случайных глифов для nonce. */
    public static char[] allGlyphs() {
        return GLYPHS.clone();
    }

    /** Сколько символов в таблице. */
    public static int size() {
        return FORWARD.size();
    }
}