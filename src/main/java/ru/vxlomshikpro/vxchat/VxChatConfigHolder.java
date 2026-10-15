package ru.vxlomshikpro.vxchat;

import ru.vxlomshikpro.vxchat.cipher.Cipher;
import ru.vxlomshikpro.vxchat.cipher.GlyphCipher;

/**
 * Держит единственный экземпляр конфига и активный шифр.
 *
 * <p>Шифр статический и не зависит от настроек, поэтому создаётся один раз.
 * Конфиг общий для команд и GUI, поэтому изменение из любого места сразу видно
 * в другом.
 */
public final class VxChatConfigHolder {

    private static VxChatConfig config = VxChatConfig.loadInitial();
    private static final Cipher CIPHER = GlyphCipher.instance();

    private VxChatConfigHolder() {
    }

    public static VxChatConfig config() {
        return config;
    }

    public static Cipher cipher() {
        return CIPHER;
    }

    public static void save() {
        config.save();
    }

    /**
     * Перечитать конфиг с диска.
     *
     * @param fallback настройки, остающиеся в памяти при ошибке чтения
     * @return результат чтения — команде /vxreload нужно знать, что показать
     */
    public static VxChatConfig.LoadResult reload() {
        VxChatConfig.LoadResult result = VxChatConfig.load(config);
        if (result.error == null) {
            config = result.config;
        }
        return result;
    }
}