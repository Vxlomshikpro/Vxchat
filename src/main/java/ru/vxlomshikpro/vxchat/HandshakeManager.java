package ru.vxlomshikpro.vxchat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import ru.vxlomshikpro.vxchat.cipher.GlyphTable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Хендшейк: автоматическое подтверждение, что собеседник знает тот же пароль
 * (раздел 4 ТЗ).
 *
 * <p>В личку уходит токен {@code vx-<nonce><flag><mac>}: два случайных иероглифа,
 * флаг «запрос» или «ответ» и шесть иероглифов MAC. Сам пароль не передаётся
 * <b>никогда</b> — только доказательство, что отправитель его знает.
 *
 * <p>MAC = первые 36 бит {@code HMAC-SHA256(пароль, nonce + flag + минута + ник)},
 * закодированные шестью иероглифами в системе счисления по основанию 70
 * (размер таблицы). 36 бит — это максимум, который влезает в 70⁶ вариантов,
 * и его достаточно, чтобы отсечь случайного наблюдателя: перебрать 2³⁶
 * вариантов в чате невозможно.
 *
 * <p>Проверка принимает отклонение по времени ±1 минута, чтобы не ломаться
 * на границе минут.
 */
public final class HandshakeManager {

    /** Префикс токена. По нему личка распознаётся и прячется из чата. */
    public static final String TOKEN_PREFIX = "vx-";

    /** Флаг «запрос» — инициатор шлёт первым. На ответ не отвечаем. */
    private static final char FLAG_REQUEST = '请';

    /** Флаг «ответ» — автоматический ответ получателя. */
    private static final char FLAG_REPLY = '答';

    /** Длина MAC в иероглифах. */
    private static final int MAC_GLYPHS = 6;

    /** Сколько минут допускаем на расхождение часов. */
    private static final int MINUTE_TOLERANCE = 1;

    /** Не чаще одного автоответа одному игроку за N миллисекунд. */
    private static final long AUTO_REPLY_COOLDOWN_MS = 10_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Кому и когда уже отвечали — защита от зацикливания и флуда. */
    private static final Map<String, Long> LAST_AUTO_REPLY = new HashMap<>();

    /** Игроки, которым мы сами отправили запрос (ждём ответ). */
    private static final Map<String, Long> PENDING_OUT = new HashMap<>();

    private HandshakeManager() {
    }

    public static void reset() {
        LAST_AUTO_REPLY.clear();
        PENDING_OUT.clear();
    }

    // ---------------- построение токена ----------------

    /** Текущая минута — часть MAC, чтобы токен был одноразовым по времени. */
    private static long currentMinute() {
        return System.currentTimeMillis() / 60_000L;
    }

    /** Собрать MAC для конкретной минуты и ника отправителя. */
    private static String macFor(String password, String nonce, char flag,
                                 long minute, String senderName) {
        String material = nonce + flag + minute + VxChatConfig.normalize(senderName);
        byte[] hmac = hmacSha256(password, material);
        long bits = ((long) (hmac[0] & 0xFF) << 28)
                | ((long) (hmac[1] & 0xFF) << 20)
                | ((long) (hmac[2] & 0xFF) << 12)
                | ((long) (hmac[3] & 0xFF) << 4)
                | ((hmac[4] & 0xF0) >>> 4);
        return encodeBase70(bits, MAC_GLYPHS);
    }

    private static byte[] hmacSha256(String password, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            // HmacSHA256 есть в любой JRE; сюда попасть не может.
            throw new IllegalStateException("[VXChat] HMAC-SHA256 недоступен", ex);
        }
    }

    /** Закодировать число глифами таблицы, старшие разряды — первыми. */
    private static String encodeBase70(long value, int length) {
        char[] glyphs = GlyphTable.allGlyphs();
        int base = glyphs.length;
        char[] out = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            out[i] = glyphs[(int) (value % base)];
            value /= base;
        }
        return new String(out);
    }

    private static char randomGlyph() {
        char[] glyphs = GlyphTable.allGlyphs();
        return glyphs[RANDOM.nextInt(glyphs.length)];
    }

    /** Полный токен для отправки. Флаг определяет роль: запрос или ответ. */
    private static String buildToken(String password, String senderName, char flag) {
        String nonce = new String(new char[]{randomGlyph(), randomGlyph()});
        String mac = macFor(password, nonce, flag, currentMinute(), senderName);
        return TOKEN_PREFIX + nonce + flag + mac;
    }

    // ---------------- разбор токена ----------------

    /** Разобрать «vx-...» из текста лички. null, если это не токен. */
    public static ParsedToken parse(String text) {
        if (text == null) {
            return null;
        }
        String body = text.trim();
        int start = body.indexOf(TOKEN_PREFIX);
        if (start < 0) {
            return null;
        }
        body = body.substring(start + TOKEN_PREFIX.length());
        if (body.length() != 9) {
            return null;
        }
        char flag = body.charAt(2);
        if (flag != FLAG_REQUEST && flag != FLAG_REPLY) {
            return null;
        }
        for (int i = 0; i < body.length(); i++) {
            if (i == 2) {
                continue;
            }
            if (!GlyphTable.isGlyph(body.charAt(i))) {
                return null;
            }
        }
        return new ParsedToken(body.substring(0, 2), flag, body.substring(3));
    }

    /** Разобранный токен. */
    public static final class ParsedToken {
        public final String nonce;
        public final char flag;
        public final String mac;

        ParsedToken(String nonce, char flag, String mac) {
            this.nonce = nonce;
            this.flag = flag;
            this.mac = mac;
        }

        public boolean isRequest() {
            return flag == FLAG_REQUEST;
        }
    }

    /**
     * Проверить MAC с допуском ±1 минута.
     *
     * @return true, если пароль совпал
     */
    private static boolean verify(String password, ParsedToken token, String senderName) {
        long now = currentMinute();
        boolean match = false;
        for (long delta = -MINUTE_TOLERANCE; delta <= MINUTE_TOLERANCE; delta++) {
            String expected = macFor(password, token.nonce, token.flag, now + delta, senderName);
            match |= constantTimeEquals(expected, token.mac);
        }
        return match;
    }

    /** Сравнение строк за постоянное время — чтобы нельзя было подбирать MAC по таймингу. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ---------------- отправка ----------------

    /**
     * Отправить токен игроку через команду лички.
     *
     * @return true, если команда ушла на сервер
     */
    private static boolean sendToken(String targetPlayer, String token) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) {
            return false;
        }
        VxChatConfig cfg = VxChatConfigHolder.config();
        String command = cfg.dmCommand + " " + targetPlayer + " " + token;
        try {
            mc.getNetworkHandler().sendChatCommand(command);
            return true;
        } catch (RuntimeException ex) {
            // Сразу после входа диспетчер клиента может быть не готов — команда дропнется.
            return false;
        }
    }

    /** /vxchat <игрок>: отправить запрос на синхронизацию. */
    public static boolean request(String targetPlayer) {
        VxChatConfig cfg = VxChatConfigHolder.config();
        String localName = MinecraftServerAddress.localPlayerName();
        if (localName == null) {
            return false;
        }
        String token = buildToken(cfg.key, localName, FLAG_REQUEST);
        PENDING_OUT.put(VxChatConfig.normalize(targetPlayer), System.currentTimeMillis());
        boolean sent = sendToken(targetPlayer, token);
        VxChatDebug.log("токен запроса отправлен -> " + targetPlayer
                + " командой /" + cfg.dmCommand + ", отправлен=" + sent);
        // Токен в лог не пишем: в нём нет пароля, но и лишнего мусора не нужно.
        return sent;
    }

    // ---------------- приём ----------------

    /**
     * Обработать входящее сообщение лички.
     *
     * @param messageText текст системного сообщения целиком
     * @return true, если это был токен VXChat (его надо скрыть из чата в любом
     *         случае — и при совпадении пароля, и при несовпадении)
     */
    public static boolean handleIncoming(String messageText) {
        return handleIncoming(messageText, null);
    }

    /**
     * Обработать токен с известным отправителем.
     *
     * <p>Когда личка приходит подписанным чатом, ник известен точно из подписи,
     * а не вытаскивается из текста — так надёжнее.
     *
     * @param knownSender ник отправителя или null, если его неизвестно
     */
    public static boolean handleIncoming(String messageText, String knownSender) {
        ParsedToken token = parse(messageText);
        if (token == null) {
            return false;
        }

        String sender = knownSender != null ? knownSender : extractSender(messageText);
        if (sender == null) {
            // Похоже на токен, но отправителя не распознали — прячем и молчим.
            VxChatDebug.log("токен получен, но отправитель не распознан — скрыт");
            return true;
        }

        VxChatConfig cfg = VxChatConfigHolder.config();
        if (!verify(cfg.key, token, sender)) {
            // Пароль не совпал: ни сообщения, ни ответа. Наблюдатель ничего не узнает.
            VxChatDebug.log("токен от " + sender + ": пароль не совпал, молчу");
            return true;
        }
        VxChatDebug.log("токен от " + sender + ": пароль совпал, MAC верен");

        String serverKey = MinecraftServerAddress.currentKey();
        boolean alreadySynced = cfg.isSynced(serverKey, sender);
        boolean added = cfg.addSynced(serverKey, sender);

        if (added) {
            cfg.save();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.sendMessage(Text.literal("Чат с ")
                        .formatted(Formatting.GREEN)
                        .append(Text.literal(sender).formatted(Formatting.GREEN, Formatting.BOLD))
                        .append(Text.literal(" успешно синхронизирован").formatted(Formatting.GREEN)),
                        false);
            }
        }

        // На ответ не отвечаем, иначе два клиента зациклят друг друга.
        if (token.isRequest() && !alreadySynced) {
            maybeAutoReply(sender);
        }
        return true;
    }

    /** Ответить своим токеном, но не чаще одного раза в 10 секунд на игрока. */
    private static void maybeAutoReply(String targetPlayer) {
        String normalized = VxChatConfig.normalize(targetPlayer);
        long now = System.currentTimeMillis();
        Long last = LAST_AUTO_REPLY.get(normalized);
        if (last != null && now - last < AUTO_REPLY_COOLDOWN_MS) {
            return;
        }
        LAST_AUTO_REPLY.put(normalized, now);

        VxChatConfig cfg = VxChatConfigHolder.config();
        String localName = MinecraftServerAddress.localPlayerName();
        if (localName == null) {
            return;
        }
        boolean sent = sendToken(targetPlayer, buildToken(cfg.key, localName, FLAG_REPLY));
        VxChatDebug.log("токен ответа отправлен -> " + targetPlayer + ", отправлен=" + sent);
    }

    /**
     * Вытащить ник отправителя из системного сообщения лички.
     *
     * <p>Форматы на серверах разные: «&lt;Ник&gt; whispers to you: …», «Ник шепчет вам: …»,
     * «Ник -> You: …». Берём ник в угловых скобках, иначе — первый токен до двоеточия
     * минус служебные слова.
     */
    private static String extractSender(String messageText) {
        int prefixEnd = messageText.indexOf(TOKEN_PREFIX);
        String head = prefixEnd > 0 ? messageText.substring(0, prefixEnd) : messageText;

        int lt = head.lastIndexOf('<');
        int gt = head.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            String name = head.substring(lt + 1, gt).trim();
            if (isPlausibleName(name)) {
                return name;
            }
        }

        int colon = head.lastIndexOf(':');
        String body = colon >= 0 ? head.substring(0, colon) : head;
        for (String token : body.trim().split("\\s+")) {
            String clean = token.replaceAll("[<>()\\[\\]→]", "").trim();
            if (clean.isEmpty() || isServiceWord(clean)) {
                continue;
            }
            if (clean.endsWith("-")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            if (isPlausibleName(clean)) {
                return clean;
            }
        }
        return null;
    }

    /** Похоже ли на ник Minecraft (1–16 символов, буквы/цифры/подчёркивание). */
    private static boolean isPlausibleName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 16
                && name.matches("[A-Za-z0-9_]+");
    }

    /** Служебные слова переводов — они не ник. */
    private static boolean isServiceWord(String token) {
        String t = VxChatConfig.normalize(token);
        return t.equals("whispers") || t.equals("whisper") || t.equals("to")
                || t.equals("you") || t.equals("from") || t.equals("ru") || t.equals("en")
                || t.equals("шепчет") || t.equals("вы") || t.equals("кому")
                || t.equals("тебе") || t.equals("вам");
    }

    /** Публичный доступ к MAC для самопроверки в тестах и отладке. */
    public static String debugMac(String nonce, char flag, long minute, String senderName) {
        return macFor(VxChatConfigHolder.config().key, nonce, flag, minute, senderName);
    }

    /** Проверить, что MessageDigest доступен — ранняя диагностика окружения. */
    static void selfCheck() {
        try {
            MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            throw new IllegalStateException("[VXChat] SHA-256 недоступен", ex);
        }
    }
}