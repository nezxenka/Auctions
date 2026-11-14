package ru.moscow.foxkiss.economy;

import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultChatApi {

    private final Chat chat;

    public VaultChatApi() {
        RegisteredServiceProvider<Chat> provider = Bukkit.getServicesManager().getRegistration(Chat.class);

        if (provider == null) {
            throw new IllegalStateException("Провайдер чата Vault не найден");
        }

        this.chat = provider.getProvider();
    }

    public String getGroupPrefix(World world, String group) {
        return chat.getGroupPrefix(world, group);
    }
}