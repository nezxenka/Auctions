package ru.moscow.foxkiss.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionItem;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.gui.builder.MenuBuilder;
import ru.moscow.foxkiss.gui.holders.QuantityMenuHolder;
import ru.moscow.foxkiss.utils.TextUtils;

public record QuantityMenuController(IConfigManager configManager, ItemDisplayFactory itemFactory, MenuBuilder menuBuilder) {

    public void openQuantity(Player player, AuctionCurrency currency, AuctionItem item, int selectedAmount) {
        ConfigValues values = configManager.getConfigValues();
        ConfigValues.QuantityMenuConfig qm = values.quantityMenu();

        QuantityMenuHolder holder = new QuantityMenuHolder(currency, player.getUniqueId(), item.id(), selectedAmount, item.amount(), item);
        Inventory inv = Bukkit.createInventory(holder, qm.sizeMenu(), TextUtils.component(values.quantityMenu().title()));

        holder.setInventory(inv);

        menuBuilder.fillGlass(inv, qm.glassPanes());

        setButton(inv, holder, qm.decrease10());
        setButton(inv, holder, qm.decrease1());
        setButton(inv, holder, qm.increase1());
        setButton(inv, holder, qm.increase10());

        updateQuantityDisplay(inv, holder, item);
        holder.addActions(qm.slotAmount(), qm.amount().actions());
        player.openInventory(inv);
    }

    public void updateQuantityDisplay(Inventory inv, QuantityMenuHolder holder, AuctionItem item) {
        int amount = holder.getSelectedAmount();
        ItemStack display = itemFactory.createBuyItem(item, amount);
        inv.setItem(configManager.getConfigValues().quantityMenu().slotAmount(), display);
    }

    private void setButton(Inventory inv, QuantityMenuHolder holder, ConfigValues.ButtonConfig config) {
        for (int slot : config.slots()) {
            if (slot >= 0 && slot < inv.getSize()) {
                inv.setItem(slot, itemFactory.createButton(config));
                holder.addActions(slot, config.actions());
            }
        }
    }
}
