package ru.vxlomshikpro.vxchat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

/**
 * Доступ к тому, что нужно от клиента: адрес текущего сервера и ник игрока.
 *
 * <p>Вынесено отдельно, чтобы и конфиг, и хендшейк, и команды спрашивали
 * это одним и тем же способом и не зависели от {@link MinecraftClient} напрямую.
 */
public final class MinecraftServerAddress {

    private MinecraftServerAddress() {
    }

    /**
     * Адрес сервера, на котором мы сейчас играем.
     *
     * @return адрес или null, если не в игре
     */
    public static String current() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            return null;
        }
        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry == null) {
            return null;
        }
        String address = entry.address;
        return address == null || address.isBlank() ? null : address;
    }

    /** Ключ конфига для текущего сервера; «local» для одиночной игры. */
    public static String currentKey() {
        String address = current();
        return address == null ? "local" : address;
    }

    /** Ник игрока в этом клиенте. */
    public static String localPlayerName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getSession() == null) {
            return null;
        }
        return mc.getSession().getUsername();
    }

    /** Есть ли подключение к серверу. */
    public static boolean connected() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getNetworkHandler() != null;
    }

    /** Онлайн ли сейчас игрок с таким ником. */
    public static boolean isOnline(String playerName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null || playerName == null) {
            return false;
        }
        String wanted = VxChatConfig.normalize(playerName);
        for (var entry : mc.getNetworkHandler().getPlayerList()) {
            String name = entry.getProfile().name();
            if (name != null && VxChatConfig.normalize(name).equals(wanted)) {
                return true;
            }
        }
        return false;
    }
}