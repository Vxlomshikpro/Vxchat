package ru.vxlomshikpro.vxchat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Диагностика хендшейка в лог игры.
 *
 * <p>Пишет только факты: кто прислал токен, совпал ли пароль, отправлен ли ответ.
 * Ни пароль, ни MAC, ни расшифрованный текст в лог не попадают — токен в логе
 * бесполезен (это HMAC, ключ из него не восстановить), а вот лишние строки в чате
 * заметны игрокам.
 */
public final class VxChatDebug {

    private static final Logger LOG = LoggerFactory.getLogger("VXChat");

    private VxChatDebug() {
    }

    public static void log(String message) {
        LOG.info(message);
    }
}