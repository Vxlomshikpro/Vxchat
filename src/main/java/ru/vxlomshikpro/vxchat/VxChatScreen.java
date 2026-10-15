package ru.vxlomshikpro.vxchat;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран настроек VXChat (раздел 6 ТЗ).
 *
 * <p>Поля связаны с командами: правка здесь меняет тот же конфиг, который читают
 * {@code /vxon}, {@code /vxoff}, {@code /vxkey} и {@code /vxdm}, и наоборот.
 * Конфиг сохраняется при каждом изменении.
 */
public final class VxChatScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int ROW_HEIGHT = 22;

    private final Screen parent;

    private TextFieldWidget keyField;
    private TextFieldWidget dmField;
    private CyclingButtonWidget<Boolean> alwaysToggle;
    private CyclingButtonWidget<Boolean> truncateToggle;

    private final List<String> syncedPlayers = new ArrayList<>();
    private int listStartY;
    private boolean listEmpty;

    public VxChatScreen(Screen parent) {
        super(Text.literal("VXChat — настройки"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        VxChatConfig cfg = VxChatConfigHolder.config();
        int left = width / 2 - FIELD_WIDTH / 2;
        int y = 48;

        keyField = new TextFieldWidget(textRenderer, left, y, FIELD_WIDTH, 20, Text.literal("пароль"));
        keyField.setMaxLength(128);
        keyField.setText(cfg.key);
        keyField.setChangedListener(value -> {
            if (!value.isBlank()) {
                cfg.key = value;
                VxChatConfigHolder.save();
            }
        });
        addDrawableChild(keyField);
        y += ROW_HEIGHT + 16;

        dmField = new TextFieldWidget(textRenderer, left, y, FIELD_WIDTH, 20, Text.literal("msg"));
        dmField.setMaxLength(32);
        dmField.setText(cfg.dmCommand);
        dmField.setChangedListener(value -> {
            cfg.dmCommand = VxChatConfig.sanitizeDm(value);
            VxChatConfigHolder.save();
        });
        addDrawableChild(dmField);
        y += ROW_HEIGHT + 18;

        addDrawableChild(ButtonWidget
                .builder(Text.literal(encryptionLabel(cfg.encryptionEnabled)), button -> {
                    cfg.encryptionEnabled = !cfg.encryptionEnabled;
                    VxChatConfigHolder.save();
                    button.setMessage(Text.literal(encryptionLabel(cfg.encryptionEnabled)));
                })
                .dimensions(left, y, FIELD_WIDTH, 20)
                .build());
        y += ROW_HEIGHT + 6;

        alwaysToggle = addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.encryptAlways)
                .tooltip(value -> Tooltip.of(Text.literal(
                        "Шифровать даже когда нет синхронизированных игроков онлайн")))
                .build(left, y, FIELD_WIDTH, 20, Text.literal("Шифровать всегда"),
                        (widget, value) -> VxChatConfigHolder.save()));
        y += ROW_HEIGHT + 6;

        truncateToggle = addDrawableChild(CyclingButtonWidget.onOffBuilder(cfg.truncateOnLimit)
                .tooltip(value -> Tooltip.of(Text.literal(
                        "Сообщение длиннее 256 символов: включено — обрезать, выключено — не отправлять")))
                .build(left, y, FIELD_WIDTH, 20, Text.literal("Обрезать лишнее"),
                        (widget, value) -> VxChatConfigHolder.save()));
        y += ROW_HEIGHT + 20;

        listStartY = y;
        String serverKey = MinecraftServerAddress.currentKey();
        syncedPlayers.clear();
        syncedPlayers.addAll(cfg.syncedPlayers(serverKey));
        listEmpty = syncedPlayers.isEmpty();

        int rows = Math.min(syncedPlayers.size(), 5);
        int buttonWidth = 60;
        for (int i = 0; i < rows; i++) {
            final String player = syncedPlayers.get(i);
            int rowY = y + i * ROW_HEIGHT;

            addDrawableChild(ButtonWidget
                    .builder(Text.literal(player), button -> {})
                    .dimensions(left, rowY, FIELD_WIDTH - buttonWidth - 4, 20)
                    .build());

            addDrawableChild(ButtonWidget
                    .builder(Text.literal("убрать"), button -> {
                        cfg.removeSynced(serverKey, player);
                        VxChatConfigHolder.save();
                        syncedPlayers.remove(player);
                        clearAndInit();
                    })
                    .dimensions(left + FIELD_WIDTH - buttonWidth, rowY, buttonWidth, 20)
                    .build());
        }

        int listBottom = y + rows * ROW_HEIGHT + 6;

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Сбросить синхронизации"), button -> {
                    cfg.clearSynced(serverKey);
                    VxChatConfigHolder.save();
                    syncedPlayers.clear();
                    clearAndInit();
                })
                .dimensions(left, listBottom, FIELD_WIDTH / 2 - 2, 20)
                .build());

        addDrawableChild(ButtonWidget
                .builder(Text.literal("Готово"), button -> close())
                .dimensions(width / 2 + 2, listBottom, FIELD_WIDTH / 2 - 2, 20)
                .build());

        setInitialFocus(keyField);
    }

    private static String encryptionLabel(boolean enabled) {
        return "Шифрование: " + (enabled ? "включено" : "выключено");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int left = width / 2 - FIELD_WIDTH / 2;
        int label = 0xAAAAAA;

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "Пароль хендшейка", left, 36, label);
        context.drawTextWithShadow(textRenderer, "Команда лички", left, 70, label);
        context.drawTextWithShadow(textRenderer, "Синхронизированные игроки", left, 120, label);

        if (listEmpty) {
            context.drawTextWithShadow(textRenderer, "никого нет — /vxchat <игрок>",
                    left, listStartY + 4, 0x888888);
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }
}