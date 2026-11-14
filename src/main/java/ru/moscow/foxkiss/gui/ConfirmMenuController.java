package ru.moscow.foxkiss.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionItem;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.gui.builder.MenuBuilder;
import ru.moscow.foxkiss.gui.holders.ConfirmMenuHolder;
import ru.moscow.foxkiss.utils.TextUtils;

public record ConfirmMenuController(IConfigManager configManager, ItemDisplayFactory itemFactory, MenuBuilder menuBuilder) {

    public void openConfirm(Player player, AuctionCurrency currency, AuctionItem item, int amount, boolean isInventoryBuy) {
        ConfigValues.ConfirmMenuConfig config = configManager.getConfigValues().confirmMenu();

        int finalAmount = (amount == Integer.MAX_VALUE) ? item.amount() : amount;

        ConfirmMenuHolder holder = new ConfirmMenuHolder(currency, player.getUniqueId(), item.id(), finalAmount, isInventoryBuy);
        Inventory inv = Bukkit.createInventory(holder, config.size(), TextUtils.component(config.title()));

        holder.setInventory(inv);

        menuBuilder.fillGlass(inv, config.glassPanes());

        inv.setItem(config.itemSlot(), itemFactory.createBuyItem(item, finalAmount));

        setButton(inv, holder, config.confirm());
        setButton(inv, holder, config.cancel());

        player.openInventory(inv);
    }

    private void setButton(Inventory inv, ConfirmMenuHolder holder, ConfigValues.ConfirmButtonConfig config) {
        for (int slot : config.slots()) {
            if (slot >= 0 && slot < inv.getSize()) {
                inv.setItem(slot, itemFactory.createButton(config));
                holder.addActions(slot, config.actions());
            }
        }
    }
}
