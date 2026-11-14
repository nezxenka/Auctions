package ru.moscow.foxkiss.economy;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultPermissionApi {

    private final Permission permission;

    public VaultPermissionApi() {
        RegisteredServiceProvider<Permission> provider = Bukkit.getServicesManager().getRegistration(Permission.class);

        if (provider == null) {
            throw new IllegalStateException("Провайдер прав Vault не найден");
        }

        this.permission = provider.getProvider();
    }

    public String getPrimaryGroup(Player player) {
        return permission.getPrimaryGroup(player);
    }

    public String getPrimaryGroup(OfflinePlayer player) {
        String[] groups = permission.getPlayerGroups(null, player);

        if (groups.length == 0) {
            return "default";
        }

        return groups[0];
    }
}