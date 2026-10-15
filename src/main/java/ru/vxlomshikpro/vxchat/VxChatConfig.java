package ru.vxlomshikpro.vxchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Конфиг мода: {@code config/vxchat.json}.
 *
 * <p>Хранит пароль хендшейка, команду лички, состояние шифрования и список
 * синхронизированных игроков в разрезе серверов (ключ — адрес сервера).
 *
 * <p>Значения меняются из команд и из GUI и пишутся на диск при каждом
 * изменении, поэтому обе точки всегда видят одно и то же.
 */
public final class VxChatConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Пароль по умолчанию (ТЗ, раздел 4). */
    public static final String DEFAULT_KEY = "㹔㺜㹻㹛㺒㺮";

    /** Команда лички по умолчанию (ТЗ, раздел 4). */
    public static final String DEFAULT_DM = "msg";

    // --- Пароль и хендшейк ---

    /** Пароль хендшейка. Никогда не пишется в чат и в логи. */
    public String key = DEFAULT_KEY;

    /** Команда приватного сообщения для хендшейка, без ведущей косой черты. */
    public String dmCommand = DEFAULT_DM;

    // --- Шифрование ---

    /** Шифровать исходящие сообщения. Переключается /vxon и /vxoff. */
    public boolean encryptionEnabled = false;

    /** Шифровать даже когда нет ни одного синхронизированного онлайн-игрока. */
    public boolean encryptAlways = false;

    /** Превышен лимит: true — резать, false — не отправлять и предупредить. */
    public boolean truncateOnLimit = true;

    // --- Синхронизированные игроки ---

    /** Адрес сервера → список ников. */
    public Map<String, List<String>> servers = new LinkedHashMap<>();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("vxchat.json");
    }

    /** Результат чтения — чтобы команде /vxreload было что показать. */
    public static final class LoadResult {
        public final VxChatConfig config;
        /** true, если конфиг не был на диске и мы его создали. */
        public final boolean created;
        /** Ошибка чтения; при ней действует прежняя конфигурация из памяти. */
        public final String error;

        LoadResult(VxChatConfig config, boolean created, String error) {
            this.config = config;
            this.created = created;
            this.error = error;
        }
    }

    /**
     * Прочитать конфиг с диска.
     *
     * @param fallback настройки, которые оставить в памяти, если файл битый
     */
    public static LoadResult load(VxChatConfig fallback) {
        Path p = path();

        if (!Files.isRegularFile(p)) {
            VxChatConfig fresh = new VxChatConfig();
            fresh.save();
            return new LoadResult(fresh, true, null);
        }

        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            VxChatConfig cfg = GSON.fromJson(r, VxChatConfig.class);
            if (cfg == null) {
                throw new IllegalStateException("пустой файл");
            }
            cfg.sanitize();
            return new LoadResult(cfg, false, null);
        } catch (IOException | RuntimeException ex) {
            backupBroken(p);
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            // Ключ и содержимое синхронизаций в лог не пишем — только причина.
            System.err.println("[VXChat] Конфиг не прочитан (" + reason + "), настройки в памяти сохранены");
            return new LoadResult(fallback, false, reason);
        }
    }

    /** Отложить битый файл под .bak, чтобы пользователь мог его починить. */
    private static void backupBroken(Path p) {
        try {
            Path bak = p.resolveSibling(p.getFileName() + ".bak");
            Files.copy(p, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Не смогли сохранить копию — не критично, сообщение об ошибке уже будет.
        }
    }

    /** Загрузить при старте игры: битый файл не должен ронять мод. */
    public static VxChatConfig loadInitial() {
        VxChatConfig defaults = new VxChatConfig();
        return load(defaults).config;
    }

    public void save() {
        sanitize();
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (IOException ex) {
            System.err.println("[VXChat] Не удалось сохранить конфиг: " + ex.getMessage());
        }
    }

    /** Ключ сервера для текущего подключения. */
    public static String currentServerKey() {
        if (MinecraftServerAddress.current() == null) {
            return "local";
        }
        return MinecraftServerAddress.current();
    }

    /** Синхронизированные игроки на текущем сервере (создаёт запись при первом обращении). */
    public List<String> syncedPlayers() {
        String key = currentServerKey();
        return servers.computeIfAbsent(key, k -> new ArrayList<>());
    }

    /** Синхронизированные игроки на конкретном сервере. */
    public List<String> syncedPlayers(String serverKey) {
        List<String> list = servers.get(serverKey);
        return list == null ? new ArrayList<>() : list;
    }

    public boolean isSynced(String serverKey, String player) {
        String normalized = normalize(player);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String name : syncedPlayers(serverKey)) {
            if (normalize(name).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Добавить игрока в синхронизированные; true, если он добавился впервые. */
    public boolean addSynced(String serverKey, String player) {
        String normalized = normalize(player);
        if (normalized.isEmpty()) {
            return false;
        }
        List<String> list = servers.computeIfAbsent(serverKey, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (normalize(list.get(i)).equals(normalized)) {
                return false;
            }
        }
        list.add(player);
        return true;
    }

    /** Убрать игрока; true, если он был в списке. */
    public boolean removeSynced(String serverKey, String player) {
        String normalized = normalize(player);
        List<String> list = servers.get(serverKey);
        if (list == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (normalize(list.get(i)).equals(normalized)) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Сбросить синхронизации на сервере. */
    public int clearSynced(String serverKey) {
        List<String> list = servers.get(serverKey);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int size = list.size();
        list.clear();
        return size;
    }

    /** Привести конфиг к валидному состоянию после чтения из файла. */
    public void sanitize() {
        if (key == null || key.isBlank()) {
            key = DEFAULT_KEY;
        }
        dmCommand = sanitizeDm(dmCommand);

        if (servers == null) {
            servers = new LinkedHashMap<>();
        }

        Set<String> validKeys = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : servers.entrySet()) {
            String serverKey = entry.getKey();
            if (serverKey == null || serverKey.isBlank()) {
                continue;
            }
            List<String> players = entry.getValue();
            if (players == null) {
                continue;
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String player : players) {
                if (player != null && !player.isBlank()) {
                    unique.add(player);
                }
            }
            if (validKeys.add(serverKey)) {
                servers.put(serverKey, new ArrayList<>(unique));
            } else {
                entry.setValue(new ArrayList<>(unique));
            }
        }
    }

    /** Привести команду лички к виду «без слеша, только буквы». */
    public static String sanitizeDm(String raw) {
        if (raw == null) {
            return DEFAULT_DM;
        }
        String cmd = raw.trim().toLowerCase(Locale.ROOT);
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        if (cmd.isEmpty() || !cmd.matches("[a-z]+")) {
            return DEFAULT_DM;
        }
        return cmd;
    }

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Ник игрока в текущем клиенте. */
    public static String localPlayerName() {
        return MinecraftServerAddress.localPlayerName();
    }
}