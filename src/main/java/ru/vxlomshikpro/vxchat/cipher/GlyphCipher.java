package ru.vxlomshikpro.vxchat.cipher;

import java.util.Locale;

/**
 * Статическая подстановка: один символ → один иероглиф (раздел 1 ТЗ).
 *
 * <p>Правила ровно как в ТЗ:
 * <ul>
 *   <li>перед шифрованием текст приводится к нижнему регистру;</li>
 *   <li>символы вне таблицы (знаки препинания, эмодзи, служебные символы)
 *       остаются как есть;</li>
 *   <li>пробел кодируется иероглифом 之, при расшифровке 之 даёт пробел;</li>
 *   <li>шифр статический — пароль на него не влияет, пароль нужен только
 *       для хендшейка.</li>
 * </ul>
 *
 * <p>Так как регистр сбрасывается, обратное отображение однозначно: расшифровка
 * всегда даёт строчные буквы. Это осознанная потеря регистра — иначе таблица
 * разрослась бы вдвое.
 */
public final class GlyphCipher implements Cipher {

    /** Ванильный лимит длины чат-сообщения. */
    public static final int CHAT_LIMIT = 256;

    private static final GlyphCipher INSTANCE = new GlyphCipher();

    public static GlyphCipher instance() {
        return INSTANCE;
    }

    private GlyphCipher() {
    }

    @Override
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        String lower = plain.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            char glyph = GlyphTable.glyphOf(c);
            if (glyph != 0) {
                out.append(glyph);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(cipherText.length());
        for (int i = 0; i < cipherText.length(); i++) {
            char c = cipherText.charAt(i);
            Character symbol = GlyphTable.symbolOf(c);
            if (symbol != null) {
                out.append(symbol.charValue());
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public boolean looksEncrypted(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (GlyphTable.isGlyph(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean encrypts(char c) {
        return GlyphTable.has(c);
    }

    @Override
    public int maxPlainLength() {
        return CHAT_LIMIT;
    }
}