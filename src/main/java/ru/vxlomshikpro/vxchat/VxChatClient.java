package ru.vxlomshikpro.vxchat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import ru.vxlomshikpro.vxchat.cipher.Cipher;
import ru.vxlomshikpro.vxchat.cipher.GlyphCipher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Точка входа мода VXChat.
 *
 * <p>Исходящий чат шифруется в иероглифы через {@code MODIFY_CHAT}, входящий от
 * синхронизированных игроков расшифровывается через {@code ALLOW_CHAT}, а личные
 * сообщения с токеном хендшейка перехватываются через {@code ALLOW_GAME}.
 *
 * <p>Команды клиентские — на сервер не отправляются и в его логах не появляются.
 */
public final class VxChatClient implements ClientModInitializer {

    /** Подсказки команды лички для /vxdm. */
    private static final String[] DM_SUGGESTIONS = {"msg", "dm", "w", "tell", "whisper", "m", "pm"};

    @Override
    public void onInitializeClient() {
        HandshakeManager.selfCheck();
        registerOutgoing();
        registerIncoming();
        registerCommands();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> HandshakeManager.reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> HandshakeManager.reset());
    }

    // ================= исходящие сообщения =================

    private void registerOutgoing() {
        // Решение принимаем до шифрования: ALLOW_CHAT срабатывает раньше MODIFY_CHAT.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!shouldEncrypt(message)) {
                return true;
            }
            String encrypted = cipher().encrypt(message);
            if (encrypted.length() <= GlyphCipher.CHAT_LIMIT) {
                return true;
            }
            if (config().truncateOnLimit) {
                return true;
            }
            warn("Сообщение длиннее " + GlyphCipher.CHAT_LIMIT
                    + " символов после шифрования — не отправлено");
            return false;
        });

        ClientSendMessageEvents.MODIFY_CHAT.register(message -> {
            if (!shouldEncrypt(message)) {
                return message;
            }
            String encrypted = cipher().encrypt(message);
            if (config().truncateOnLimit && encrypted.length() > GlyphCipher.CHAT_LIMIT) {
                warn("Сообщение обрезано до " + GlyphCipher.CHAT_LIMIT + " символов");
                return encrypted.substring(0, GlyphCipher.CHAT_LIMIT);
            }
            return encrypted;
        });
    }

    /** Шифровать ли это сообщение: не команда, включено, есть кому или режим «всегда». */
    private static boolean shouldEncrypt(String message) {
        if (message == null || message.isEmpty() || message.charAt(0) == '/') {
            return false;
        }
        VxChatConfig cfg = config();
        if (!cfg.encryptionEnabled) {
            return false;
        }
        if (cfg.encryptAlways) {
            return true;
        }
        return !activeSyncedPlayers().isEmpty();
    }

    /** Синхронизированные игроки, которые сейчас онлайн (раздел 7 ТЗ). */
    private static List<String> activeSyncedPlayers() {
        List<String> result = new ArrayList<>();
        String serverKey = MinecraftServerAddress.currentKey();
        for (String player : config().syncedPlayers(serverKey)) {
            if (MinecraftServerAddress.isOnline(player)) {
                result.add(player);
            }
        }
        return result;
    }

    // ================= входящие сообщения =================

    private void registerIncoming() {
        // Токены хендшейка приходят системными сообщениями лички — прячем их всегда.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) {
                return true;
            }
            return !HandshakeManager.handleIncoming(message.getString());
        });

        // Чат игроков: расшифровываем то, что пришло от синхронизированного.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> {
            VxChatConfig cfg = config();
            String senderName = sender == null ? null : sender.name();

            String signedContent = signed != null ? signed.getSignedContent() : null;
            String raw = signedContent != null ? signedContent : message.getString();
            if (raw == null) {
                return true;
            }

            // Токен хендшейка проверяем ДА проверки синхронизации: отправитель токена
            // по определению ещё не синхронизирован, иначе хендшейк не начнётся никогда.
            // Ник здесь известен точно из подписи — надёжнее, чем парсить текст лички.
            if (HandshakeManager.handleIncoming(raw, senderName)
                    || HandshakeManager.handleIncoming(message.getString(), senderName)) {
                return false;
            }

            if (senderName == null) {
                return true;
            }

            String serverKey = MinecraftServerAddress.currentKey();
            if (!cfg.isSynced(serverKey, senderName) || !MinecraftServerAddress.isOnline(senderName)) {
                return true;
            }

            String content = stripSenderPrefix(raw, senderName);
            if (content == null) {
                return true;
            }

            // Мусор после расшифровки → показываем оригинал (раздел 3 ТЗ).
            if (!cipher().looksEncrypted(content)) {
                return true;
            }

            String plain = cipher().decrypt(content);
            if (plain.isBlank()) {
                return true;
            }

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.inGameHud == null || mc.inGameHud.getChatHud() == null) {
                return true;
            }
            mc.inGameHud.getChatHud().addMessage(buildDecoded(senderName, plain));
            return false;
        });
    }

    /** Убрать из текста префикс «<Ник> » или «Ник: », оставив только само сообщение. */
    private static String stripSenderPrefix(String decorated, String senderName) {
        String text = decorated;
        String bracketed = "<" + senderName + ">";
        if (text.startsWith(bracketed)) {
            return text.substring(bracketed.length()).trim();
        }
        int colon = text.indexOf(':');
        if (colon > 0 && text.substring(0, colon).trim().equalsIgnoreCase(senderName)) {
            return text.substring(colon + 1).trim();
        }
        return text;
    }

    /** Формат «<ник> текст» для расшифрованного сообщения. */
    private static MutableText buildDecoded(String senderName, String plain) {
        return Text.empty()
                .append(Text.literal("<").formatted(Formatting.GRAY))
                .append(Text.literal(senderName).formatted(Formatting.WHITE))
                .append(Text.literal("> ").formatted(Formatting.GRAY))
                .append(Text.literal(plain).formatted(Formatting.WHITE));
    }

    // ================= команды =================

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(ClientCommandManager.literal("vxchat")
                    .executes(ctx -> {
                        showHelp(ctx.getSource());
                        return 1;
                    })
                    .then(ClientCommandManager.literal("gui")
                            .executes(ctx -> {
                                MinecraftClient.getInstance().setScreen(new VxChatScreen(null));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("reset")
                            .executes(ctx -> {
                                String serverKey = MinecraftServerAddress.currentKey();
                                int removed = config().clearSynced(serverKey);
                                save();
                                info(ctx.getSource(), "Синхронизации сброшены"
                                        + (removed > 0 ? " (" + removed + ")" : ""));
                                return 1;
                            }))
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                            .suggests(VxChatClient::suggestPlayers)
                            .executes(ctx -> startHandshake(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "player")))));

            dispatcher.register(ClientCommandManager.literal("vxon").executes(ctx -> {
                config().encryptionEnabled = true;
                save();
                chat(Text.literal("Шифрование включено").formatted(Formatting.GREEN));
                explainWhyNotEncrypting();
                return 1;
            }));

            dispatcher.register(ClientCommandManager.literal("vxoff").executes(ctx -> {
                config().encryptionEnabled = false;
                save();
                chat(Text.literal("Шифрование выключено").formatted(Formatting.RED));
                return 1;
            }));

            dispatcher.register(ClientCommandManager.literal("vxkey")
                    .executes(ctx -> {
                        info(ctx.getSource(), "Использование: /vxkey <ключ>");
                        return 1;
                    })
                    .then(ClientCommandManager.argument("key", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String key = StringArgumentType.getString(ctx, "key");
                                if (key.isBlank()) {
                                    info(ctx.getSource(), "Использование: /vxkey <ключ>");
                                    return 0;
                                }
                                config().key = key;
                                save();
                                // Ключ в чат не выводим.
                                info(ctx.getSource(), "Ключ изменён");
                                return 1;
                            })));

            dispatcher.register(ClientCommandManager.literal("vxdm")
                    .executes(ctx -> {
                        info(ctx.getSource(), "Команда лички: /" + config().dmCommand);
                        return 1;
                    })
                    .then(ClientCommandManager.argument("command", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                String prefix = builder.getRemainingLowerCase();
                                for (String suggestion : DM_SUGGESTIONS) {
                                    if (suggestion.startsWith(prefix)) {
                                        builder.suggest(suggestion);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> {
                                String raw = StringArgumentType.getString(ctx, "command");
                                String normalized = VxChatConfig.sanitizeDm(raw);
                                config().dmCommand = normalized;
                                save();
                                info(ctx.getSource(), "Команда лички: /" + normalized);
                                return 1;
                            })));

            dispatcher.register(ClientCommandManager.literal("vxreload").executes(ctx -> {
                reload(ctx.getSource());
                return 1;
            }));
        });
    }

    private static int startHandshake(FabricClientCommandSource source, String playerName) {
        String target = VxChatConfig.normalize(playerName);
        String localName = VxChatConfig.normalize(MinecraftServerAddress.localPlayerName());

        if (target.isEmpty()) {
            error(source, "Игрок не найден");
            return 0;
        }
        if (!MinecraftServerAddress.isOnline(playerName)) {
            error(source, "Игрок не найден");
            return 0;
        }
        if (!localName.isEmpty() && localName.equals(target)) {
            error(source, "Нельзя начать синхронизацию с самим собой");
            return 0;
        }

        boolean sent = HandshakeManager.request(playerName);
        if (!sent) {
            error(source, "Не удалось отправить команду лички");
            return 0;
        }
        info(source, "Синхронизация с " + playerName + " запущена");
        return 1;
    }

    /** Tab-подсказки: онлайн-ники (раздел 5 ТЗ). */
    private static CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) {
            return builder.buildFuture();
        }
        String prefix = builder.getRemainingLowerCase();
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            String name = entry.getProfile().name();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    // ================= /vxreload =================

    private static void reload(FabricClientCommandSource source) {
        VxChatConfig.LoadResult result = VxChatConfigHolder.reload();

        if (result.error != null) {
            error(source, "Ошибка чтения конфига, настройки не изменены");
            return;
        }
        if (result.created) {
            info(source, "Конфиг не найден, создан с настройками по умолчанию");
        }

        VxChatConfig cfg = config();
        String serverKey = MinecraftServerAddress.currentKey();
        MutableText line = Text.literal("Конфиг перезагружен").formatted(Formatting.GREEN);
        line.append(Text.literal(" · шифрование ")
                .formatted(Formatting.GRAY))
                .append(Text.literal(cfg.encryptionEnabled ? "вкл" : "выкл")
                        .formatted(cfg.encryptionEnabled ? Formatting.GREEN : Formatting.RED));
        line.append(Text.literal(" · личка /" + cfg.dmCommand).formatted(Formatting.GRAY));
        line.append(Text.literal(" · синхронизировано: " + cfg.syncedPlayers(serverKey).size())
                .formatted(Formatting.GRAY));
        chat(line);
    }

    /**
     * Подсказать, почему шифрование включено, но сообщения всё равно идут открытым.
     *
     * <p>Пока нет синхронизированных игроков онлайн, мод по ТЗ не шифрует — иначе
     * остальные просто увидят иероглифы и не смогут прочесть. Об этом лучше сказать
     * явно, иначе выглядит как баг.
     */
    private static void explainWhyNotEncrypting() {
        VxChatConfig cfg = config();
        if (cfg.encryptAlways || !activeSyncedPlayers().isEmpty()) {
            return;
        }
        chat(Text.literal("[VXChat] Пока не шифрую: нет синхронизированных игроков онлайн. "
                + "Сделай ").formatted(Formatting.YELLOW)
                .append(Text.literal("/vxchat <игрок>").formatted(Formatting.YELLOW))
                .append(Text.literal(" или включи «шифровать всегда» в GUI")
                        .formatted(Formatting.YELLOW)));
    }

    // ================= вывод помощи =================

    private static void showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("VXChat — команды").formatted(Formatting.GRAY));

        command(source, "/vxchat <игрок>", "начать синхронизацию (хендшейк) с игроком");
        command(source, "/vxchat gui", "открыть окно настроек");
        command(source, "/vxchat reset", "сбросить синхронизации на этом сервере");
        command(source, "/vxon", "включить шифрование");
        command(source, "/vxoff", "выключить шифрование");
        command(source, "/vxkey <ключ>", "задать ключ (пароль) для хендшейка");
        command(source, "/vxdm <команда>", "задать команду для личных сообщений");
        command(source, "/vxreload", "перезагрузить конфиг");
    }

    private static void command(FabricClientCommandSource source, String command, String description) {
        source.sendFeedback(Text.literal("  " + command).formatted(Formatting.YELLOW)
                .append(Text.literal("  " + description).formatted(Formatting.WHITE)));
    }

    // ================= мелкие помощники =================

    private static VxChatConfig config() {
        return VxChatConfigHolder.config();
    }

    private static Cipher cipher() {
        return VxChatConfigHolder.cipher();
    }

    private static void save() {
        VxChatConfigHolder.save();
    }

    /** Сообщение в чат клиента (видно только игроку). */
    static void chat(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(text, false);
        }
    }

    private static void info(FabricClientCommandSource source, String message) {
        source.sendFeedback(Text.literal("[VXChat] " + message).formatted(Formatting.GRAY));
    }

    private static void error(FabricClientCommandSource source, String message) {
        source.sendFeedback(Text.literal("[VXChat] " + message).formatted(Formatting.RED));
    }

    private static void warn(String message) {
        chat(Text.literal("[VXChat] " + message).formatted(Formatting.YELLOW));
    }
}