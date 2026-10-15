package ru.vxlomshikpro.vxchat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Точка входа ModMenu: кнопка «Настроить» в списке модов. */
public class VxChatModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return VxChatScreen::new;
    }
}