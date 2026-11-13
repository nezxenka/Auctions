    package ru.moscow.foxkiss.gui.builder;

    import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
    import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
    import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
    import it.unimi.dsi.fastutil.objects.ObjectArrayList;
    import it.unimi.dsi.fastutil.objects.ObjectList;
    import org.bukkit.Bukkit;
    import org.bukkit.Material;
    import org.bukkit.entity.Player;
    import org.bukkit.inventory.Inventory;
    import org.bukkit.inventory.ItemStack;
    import org.bukkit.inventory.meta.ItemMeta;
    import org.bukkit.inventory.meta.components.CustomModelDataComponent;
    import org.jetbrains.annotations.NotNull;
    import ru.moscow.foxkiss.auction.AuctionCurrency;
    import ru.moscow.foxkiss.auction.AuctionItem;
    import ru.moscow.foxkiss.auction.AuctionLotType;
    import ru.moscow.foxkiss.auction.AuctionSort;
    import ru.moscow.foxkiss.auction.PlayerProfile;
    import ru.moscow.foxkiss.config.ConfigValues;
    import ru.moscow.foxkiss.config.interfaces.IConfigManager;
    import ru.moscow.foxkiss.gui.AuctionViewType;
    import ru.moscow.foxkiss.gui.ItemDisplayFactory;
    import ru.moscow.foxkiss.gui.LotDisplayTime;
    import ru.moscow.foxkiss.gui.ProfileButtonFormatter;
    import ru.moscow.foxkiss.gui.holders.AuctionMenuHolder;
    import ru.moscow.foxkiss.gui.holders.InventoryMenuHolder;
    import ru.moscow.foxkiss.gui.holders.MainMenuHolder;
    import ru.moscow.foxkiss.gui.actions.Action;
    import ru.moscow.foxkiss.gui.enums.ActionType;
    import ru.moscow.foxkiss.gui.holders.records.LotSnapshot;
    import ru.moscow.foxkiss.utils.PriceFormatter;
    import ru.moscow.foxkiss.utils.TextUtils;

    import java.util.*;

    public final class MenuBuilder {
        private final IConfigManager configManager;
        private final ItemDisplayFactory itemFactory;
        private static final List<Action> PROFILE_ACTIONS = List.of(new Action(ActionType.PROFILE));
        private final Object2ObjectOpenHashMap<ConfigValues.GlassPane, ItemStack> glassPaneCache = new Object2ObjectOpenHashMap<>();
        private final EnumMap<AuctionViewType, EnumMap<AuctionCurrency, List<Integer>>> slotCache = new EnumMap<>(AuctionViewType.class);
        private static final List<Action> HISTORY_ACTIONS = List.of(new Action(ActionType.HISTORY));

        public MenuBuilder(IConfigManager configManager, ItemDisplayFactory itemFactory) {
            this.configManager = configManager;
            this.itemFactory = itemFactory;
            buildGlassCache();
        }

        public Inventory buildMainMenu(Player player, AuctionViewType viewType, AuctionCurrency currency, int page, AuctionSort sort, String sellerFilter, String searchFilter, String category, List<AuctionItem> filtered, int totalCount, int pageSize, int sellingCount, int expiredCount, Map<String, PlayerProfile> profiles, Map<String, Integer> materialLots, Long2ObjectMap<String> itemGroups) {
            ConfigValues values = configManager.getConfigValues();
            ConfigValues.AuctionGuiConfig gui = values.gui(currency);
            int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / pageSize));
            String pageDisplay = (page + 1) + "/" + totalPages;
            String title = buildTitle(viewType, pageDisplay, gui.titles());

            MainMenuHolder holder = new MainMenuHolder(viewType, currency, player.getUniqueId(), page, sort, sellerFilter, searchFilter, category);
            holder.setTotalPages(totalPages);

            Inventory inv = Bukkit.createInventory(holder, gui.menuSize(), TextUtils.component(title));
            holder.setInventory(inv);

            fillGlass(inv, getGlassPanes(viewType, values, currency));
            refreshLotDisplays(inv, holder, filtered, totalCount, pageSize, profiles, materialLots, itemGroups);
            addNavigationButtons(inv, viewType, sort, category, sellingCount, expiredCount, pageDisplay, holder, currency);
            addExitButton(inv, viewType, pageDisplay, gui, holder);
            addSalesHistoryButton(inv, holder);
            if (viewType == AuctionViewType.SELLING && values.profileMenu().enabled()) {
                String playerName = player.getName();
                PlayerProfile profile = profiles.get(playerName);

                if (profile == null) {
                    profile = PlayerProfile.empty(playerName);
                }

                addProfileButton(inv, holder, profile);
            }
            return inv;
        }

        public void refreshLotDisplays(Inventory inventory, AuctionMenuHolder holder, List<AuctionItem> filtered, int totalCount, int pageSize, Map<String, PlayerProfile> profiles, Map<String, Integer> materialLots, Long2ObjectMap<String> itemGroups) {
            AuctionViewType viewType = holder.getViewType();
            AuctionCurrency currency = holder.getCurrency();
            List<Integer> slots = getAuctionSlots(viewType, currency);

            int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / pageSize));
            holder.setTotalPages(totalPages);
            if (viewType == AuctionViewType.MAIN && currency == AuctionCurrency.VAULT && !TextUtils.isNotBlank(holder.getSearchFilter())) {
                refreshMaterialCards(inventory, holder, filtered, slots, materialLots, itemGroups);
                return;
            }
            holder.getMaterialGroups().clear();
            holder.getMaterialGroupCounts().clear();
            int count = Math.min(filtered.size(), slots.size());
            Map<Integer, LotSnapshot> snapshots = holder.getLotSnapshots();

            long now = System.currentTimeMillis();
            ConfigValues config = configManager.getConfigValues();
            int maxStorageDays = config.maxAuctionStorageDays();

            Map<String, PlayerProfile> emptyProfiles = new HashMap<>();

            for (int i = 0; i < slots.size(); i++) {
                int slot = slots.get(i);

                if (i >= count) {
                    if (snapshots.remove(slot) != null || holder.getLot(slot) != null) {
                        inventory.setItem(slot, null);
                        holder.getLotsBySlot().remove(slot);
                        holder.getLotsAmountBySlot().remove(slot);
                        holder.getInventoryLotBySlot().remove(slot);
                    }
                    continue;
                }

                AuctionItem item = filtered.get(i);
                boolean inventoryLot = item.type() == AuctionLotType.INVENTORY;

                PlayerProfile profile = profiles.get(item.sellerName());
                if (profile == null) {
                    profile = emptyProfiles.get(item.sellerName());
                    if (profile == null) {
                        profile = PlayerProfile.empty(item.sellerName());
                        emptyProfiles.put(item.sellerName(), profile);
                    }
                }

                long ageBucket = LotDisplayTime.ageBucket(item.createdAt(), now);
                long daysLeft = LotDisplayTime.daysLeft(item.createdAt(), now, maxStorageDays);

                LotSnapshot next = new LotSnapshot(
                        item.id(), item.amount(), item.price(), item.createdAt(),
                        item.type(), item.status(), item.sellerName(),
                        profile.discord(), profile.storeName(), profile.sales(),
                        ageBucket, daysLeft
                );

                if (next.equals(snapshots.get(slot))) {
                    continue;
                }

                ItemStack display = inventoryLot
                        ? itemFactory.createInventoryDisplay(item)
                        : itemFactory.createLotDisplay(item, profile, ageBucket, daysLeft);

                inventory.setItem(slot, display);
                holder.addLot(slot, item.id(), item.amount(), inventoryLot);
                snapshots.put(slot, next);
            }
        }
        private void refreshMaterialCards(Inventory inventory, AuctionMenuHolder holder, List<AuctionItem> items, List<Integer> slots, Map<String, Integer> materialLots, Long2ObjectMap<String> itemGroups) {
            ConfigValues.AuctionGuiConfig gui = configManager.getConfigValues().moneyGui();
            boolean changedTemplate = !gui.materialCardName().equals(holder.getRenderedMaterialCardName()) || !gui.materialCardLore().equals(holder.getRenderedMaterialCardLore());
            holder.setRenderedMaterialCardName(gui.materialCardName());
            holder.setRenderedMaterialCardLore(gui.materialCardLore());
            holder.getLotsBySlot().clear();
            holder.getLotsAmountBySlot().clear();
            holder.getInventoryLotBySlot().clear();
            holder.getLotSnapshots().clear();
            int itemCount = items.size();
            int slotCount = slots.size();
            for (int i = 0; i < slotCount; i++) {
                int slot = slots.get(i);
                if (i >= itemCount) {
                    holder.getMaterialGroups().remove(slot);
                    holder.getMaterialGroupCounts().remove(slot);
                    inventory.setItem(slot, null);
                    continue;
                }
                AuctionItem item = items.get(i);
                String group = itemGroups.get(item.id());
                String id = group == null ? item.itemStack().getType().name() : group;
                String selector = group == null ? id : "group:" + group;
                int lots = materialLots.getOrDefault(id, 1);
                if (!changedTemplate && selector.equals(holder.getMaterialGroups().get(slot))
                        && holder.getMaterialGroupCounts().get(slot) == lots) continue;
                inventory.setItem(slot, itemFactory.createMaterialCard(item.itemStack(), id, lots));
                holder.getMaterialGroups().put(slot, selector);
                holder.getMaterialGroupCounts().put(slot, lots);
            }
        }

        public void refreshNavigationButtons(Inventory inventory, AuctionMenuHolder holder) {
            ConfigValues.NavigationConfig nav = configManager.getConfigValues().gui(holder.getCurrency()).navigation();
            if (holder.getRenderedPage() != holder.getPage() || holder.getRenderedTotalPages() != holder.getTotalPages()) {
                String page = (holder.getPage() + 1) + "/" + holder.getTotalPages();
                setNavButton(inventory, nav.previous(), page, holder);
                setNavButton(inventory, nav.refresh(), page, holder);
                setNavButton(inventory, nav.next(), page, holder);
                holder.setRenderedPage(holder.getPage());
                holder.setRenderedTotalPages(holder.getTotalPages());
            }


            if (holder.getViewType() != AuctionViewType.MAIN && holder.getViewType() != AuctionViewType.OFFERS) return;
            if (holder.getRenderedSort() != holder.getSort()) {
                inventory.setItem(nav.sort().slot(), itemFactory.createSortButton(holder.getSort(), holder.getCurrency()));
                holder.addActions(nav.sort().slot(), nav.sort().actions());
                holder.setRenderedSort(holder.getSort());
            }

            if (holder.getViewType() == AuctionViewType.MAIN && !Objects.equals(holder.getRenderedCategory(), holder.getCategory())) {
                inventory.setItem(nav.categories().slot(), itemFactory.createCategoryButton(holder.getCategory(), holder.getCurrency()));
                holder.addActions(nav.categories().slot(), nav.categories().actions());
                holder.setRenderedCategory(holder.getCategory());
            }
        }

        public void refreshCounters(Inventory inventory, AuctionMenuHolder holder, int selling, int expired) {
            if (holder.getViewType() != AuctionViewType.MAIN) return;
            ConfigValues.NavigationConfig nav = configManager.getConfigValues().gui(holder.getCurrency()).navigation();
            String page = (holder.getPage() + 1) + "/" + holder.getTotalPages();
            boolean pageChanged = !page.equals(holder.getRenderedCounterPage());
            if (pageChanged || selling != holder.getRenderedSellingCount()) {
                setNavButtonWithCount(inventory, nav.selling(), page, selling, holder);
                holder.setRenderedSellingCount(selling);
            }
            if (pageChanged || expired != holder.getRenderedExpiredCount()) {
                setNavButtonWithCount(inventory, nav.expired(), page, expired, holder);
                holder.setRenderedExpiredCount(expired);
            }
            holder.setRenderedCounterPage(page);
        }

        public void fillGlass(Inventory inventory, Map<Integer, ConfigValues.GlassPane> panes) {
            for (Map.Entry<Integer, ConfigValues.GlassPane> entry : panes.entrySet()) {
                ItemStack cached = glassPaneCache.get(entry.getValue());
                if (cached != null) {
                    inventory.setItem(entry.getKey(), cached);
                }
            }
        }

        public List<Integer> getAuctionSlots(AuctionViewType viewType, AuctionCurrency currency) {
            EnumMap<AuctionCurrency, List<Integer>> byCurrency = slotCache.get(viewType);
            List<Integer> cached = byCurrency == null ? null : byCurrency.get(currency);
            if (cached != null) return cached;

            ConfigValues values = configManager.getConfigValues();
            List<Integer> result = getIntegers(viewType, currency, values);
            if (byCurrency == null) {
                byCurrency = new EnumMap<>(AuctionCurrency.class);
                slotCache.put(viewType, byCurrency);
            }
            byCurrency.put(currency, result);
            return result;
        }

        private static @NotNull List<Integer> getIntegers(AuctionViewType viewType, AuctionCurrency currency, ConfigValues values) {
            List<Integer> configured = values.gui(currency).auctionSlots();

            List<Integer> result;
            if (viewType != AuctionViewType.SELLING) {
                result = List.copyOf(configured);
            } else {
                List<Integer> slots = new ArrayList<>(configured.size());
                for (int slot : configured) {
                    if ((!values.salesHistory().enabled() || !values.salesHistory().button().slots().contains(slot))
                            && (!values.profileMenu().enabled() || !values.profileMenu().button().slots().contains(slot))) {
                        slots.add(slot);
                    }
                }
                result = List.copyOf(slots);
            }
            return result;
        }

        public void addSalesHistoryButton(Inventory inventory, AuctionMenuHolder holder) {
            ConfigValues values = configManager.getConfigValues();
            if (holder.getViewType() != AuctionViewType.SELLING || !values.salesHistory().enabled()) return;
            ItemStack button = itemFactory.createButton(values.salesHistory().button());
            for (int slot : values.salesHistory().button().slots()) {
                if (slot >= inventory.getSize()) continue;
                inventory.setItem(slot, button);
                holder.addActions(slot, HISTORY_ACTIONS);
            }
        }

        public void addProfileButton(Inventory inventory, AuctionMenuHolder holder, PlayerProfile profile) {
            ConfigValues values = configManager.getConfigValues();

            if (holder.getViewType() != AuctionViewType.SELLING || !values.profileMenu().enabled()) {
                return;
            }

            ConfigValues.ButtonConfig configured = values.profileMenu().button();

            ConfigValues.ButtonConfig formatted = ProfileButtonFormatter.format(configured, values.profileMenu().emptyValue(), profile);

            ItemStack button = itemFactory.createButton(formatted);

            for (int slot : configured.slots()) {
                if (slot < 0 || slot >= inventory.getSize()) {
                    continue;
                }

                inventory.setItem(slot, button.clone());
                holder.addActions(slot, PROFILE_ACTIONS);
            }
        }

        private String buildTitle(AuctionViewType viewType, String pageDisplay, ConfigValues.TitlesConfig titles) {
            String raw = switch (viewType) {
                case MAIN -> titles.main();
                case SELLING -> titles.selling();
                case EXPIRED -> titles.expired();
                default -> titles.main();
            };
            return raw.replace("{page}", pageDisplay);
        }

        private Map<Integer, ConfigValues.GlassPane> getGlassPanes(AuctionViewType viewType, ConfigValues values, AuctionCurrency currency) {
            return switch (viewType) {
                case MAIN, OFFERS, SELLING -> values.gui(currency).mainGlass();
                case EXPIRED -> values.gui(currency).expiredGlass();
                default -> Collections.emptyMap();
            };
        }

        private void addExitButton(Inventory inv, AuctionViewType viewType, String pageDisplay, ConfigValues.AuctionGuiConfig gui, AuctionMenuHolder holder) {
            ConfigValues.ButtonConfig exit = gui.exitButton();
            if (viewType != AuctionViewType.SELLING && viewType != AuctionViewType.EXPIRED && viewType != AuctionViewType.OFFERS) return;

            ConfigValues.ButtonConfig replaced = getButtonConfig(pageDisplay, exit);

            ItemStack button = itemFactory.createButton(replaced);
            for (int slot : replaced.slots()) {
                inv.setItem(slot, button.clone());
                holder.addActions(slot, replaced.actions());
            }
        }

        private static ConfigValues.@NotNull ButtonConfig getButtonConfig(String pageDisplay, ConfigValues.ButtonConfig exit) {
            ObjectList<String> replacedLore = new ObjectArrayList<>();
            for (String line : exit.lore()) {
                replacedLore.add(line.replace("{page}", pageDisplay));
            }

            return new ConfigValues.ButtonConfig(
                    exit.material(),
                    exit.name().replace("{page}", pageDisplay),
                    replacedLore,
                    exit.skullTexture(),
                    exit.actions(),
                    exit.slots(),
                    exit.customModelData()
            );
        }


        private void addNavigationButtons(Inventory inv, AuctionViewType viewType, AuctionSort sort, String category, int sellingCount, int expiredCount, String pageDisplay, AuctionMenuHolder holder, AuctionCurrency currency) {
            ConfigValues.AuctionGuiConfig gui = configManager.getConfigValues().gui(currency);

            ConfigValues.NavigationConfig nav = gui.navigation();

            setNavButton(inv, nav.previous(), pageDisplay, holder);
            setNavButton(inv, nav.refresh(), pageDisplay, holder);
            setNavButton(inv, nav.next(), pageDisplay, holder);

            if (viewType == AuctionViewType.OFFERS) {
                inv.setItem(nav.sort().slot(), itemFactory.createSortButton(sort, currency));
                holder.addActions(nav.sort().slot(), nav.sort().actions());
                holder.setRenderedSort(sort);
            }

            if (viewType == AuctionViewType.MAIN) {
                setNavButtonWithCount(inv, nav.selling(), pageDisplay, sellingCount, holder);
                setNavButtonWithCount(inv, nav.expired(), pageDisplay, expiredCount, holder);
                inv.setItem(nav.sort().slot(), itemFactory.createSortButton(sort, currency));
                holder.addActions(nav.sort().slot(), nav.sort().actions());
                inv.setItem(nav.categories().slot(), itemFactory.createCategoryButton(category, currency));
                holder.addActions(nav.categories().slot(), nav.categories().actions());
                holder.setRenderedSort(sort);
                holder.setRenderedCategory(category);
            }

            holder.setRenderedPage(holder.getPage());
            holder.setRenderedTotalPages(holder.getTotalPages());
            holder.setRenderedSellingCount(sellingCount);
            holder.setRenderedExpiredCount(expiredCount);
            holder.setRenderedCounterPage(pageDisplay);
        }


        private void setNavButton(Inventory inv, ConfigValues.NavigationButton button, String pageDisplay, AuctionMenuHolder holder) {
            setNavButtonInternal(inv, button, pageDisplay, null, holder);
        }

        private void setNavButtonWithCount(Inventory inv, ConfigValues.NavigationButton button, String pageDisplay, int count, AuctionMenuHolder holder) {
            setNavButtonInternal(inv, button, pageDisplay, Integer.toString(count), holder);
        }

        private void setNavButtonInternal(Inventory inv, ConfigValues.NavigationButton button, String pageDisplay, String count, AuctionMenuHolder holder) {
            List<String> template = button.lore();
            ObjectList<String> lore = new ObjectArrayList<>(template.size());

            for (String line : template) {
                String result = line.replace("{page}", pageDisplay);

                if (count != null) {
                    result = result.replace("{count}", count);
                }

                lore.add(result);
            }

            ItemStack item = itemFactory.createNavigationButton(button, lore);

            inv.setItem(button.slot(), item);
            holder.addActions(button.slot(), button.actions());
        }

        public void buildInventoryView(InventoryMenuHolder holder, AuctionItem lot, int current, int total) {
            ConfigValues values = configManager.getConfigValues();
            ConfigValues.InventorySellingConfig inv = values.inventorySelling();
            ConfigValues.InventoryViewConfig view = inv.view();

            Inventory invUi = holder.getInventory();


            String price = lot.currency().symbol(values) + PriceFormatter.format(lot.price());
            String seller = lot.sellerName();

            fillGlass(invUi, view.glassPanes());

            List<ItemStack> contents = lot.inventoryContents();
            int startSlot = view.startSlot();
            int endSlot = view.endSlot();
            if (contents != null) {
                int slot = startSlot;
                for (ItemStack item : contents) {
                    if (slot > endSlot) break;
                    if (item != null && item.getType() != Material.AIR) {
                        invUi.setItem(slot, itemFactory.createInventoryItemDisplay(item));
                        slot++;
                    }
                }
            }

            if (view.prev() != null && view.prev().slot() >= 0) {
                ConfigValues.InventoryNavButton prev = view.prev();
                List<String> lore = colorize(prev.lore(), current, total);
                invUi.setItem(prev.slot(), itemFactory.createConfigButton(prev.material(), colorize(prev.name()), lore));
                holder.addActions(prev.slot(), prev.actions());
            }

            if (view.next() != null && view.next().slot() >= 0) {
                ConfigValues.InventoryNavButton next = view.next();
                List<String> lore = colorize(next.lore(), current, total);
                invUi.setItem(next.slot(), itemFactory.createConfigButton(next.material(), colorize(next.name()), lore));
                holder.addActions(next.slot(), next.actions());
            }

            if (view.cancel() != null) {
                ConfigValues.InventoryActionButton cancel = view.cancel();

                ItemStack button = itemFactory.createConfigButton(cancel.material(), colorize(cancel.name()), colorize(cancel.lore(), price, seller));

                for (int slot : cancel.slots()) {
                    invUi.setItem(slot, button.clone());
                    holder.addActions(slot, cancel.actions());
                }
            }

            if (view.confirm() != null) {
                ConfigValues.InventoryActionButton confirm = view.confirm();

                ItemStack button = itemFactory.createConfigButton(confirm.material(), colorize(confirm.name()), colorize(confirm.lore(), price, seller));

                for (int slot : confirm.slots()) {
                    invUi.setItem(slot, button.clone());
                    holder.addActions(slot, confirm.actions());
                }
            }

            if (view.itemSlot() >= 0) {
                invUi.setItem(view.itemSlot(), itemFactory.createBuyItem(lot, lot.amount()));
            }
        }

        private List<String> colorize(List<String> lore, int current, int total) {
            String currentValue = Integer.toString(current + 1);
            String maxValue = Integer.toString(Math.max(1, total));

            ObjectList<String> result = new ObjectArrayList<>(lore.size());

            for (String line : lore) {
                result.add(TextUtils.colorize(line.replace("{current}", currentValue).replace("{max}", maxValue)));
            }

            return result;
        }

        private List<String> colorize(List<String> lore, String price, String seller) {
            ObjectList<String> result = new ObjectArrayList<>();
            for (String line : lore) {
                result.add(TextUtils.colorize(line
                        .replace("%price%", price)
                        .replace("%player%", seller)));
            }
            return result;
        }

        private String colorize(String text) {
            return TextUtils.colorize(text);
        }

        public void buildGlassCache() {
            slotCache.clear();
            glassPaneCache.clear();
            ConfigValues config = configManager.getConfigValues();
            cachePanes(config.moneyGui().mainGlass());
            cachePanes(config.moneyGui().expiredGlass());
            cachePanes(config.donateGui().mainGlass());
            cachePanes(config.donateGui().expiredGlass());
            cachePanes(config.confirmMenu().glassPanes());
            cachePanes(config.quantityMenu().glassPanes());
        }

        private void cachePanes(Map<Integer, ConfigValues.GlassPane> panes) {
            for (ConfigValues.GlassPane pane : panes.values()) {
                glassPaneCache.computeIfAbsent(pane, this::createGlassPane);
            }
        }

        private ItemStack createGlassPane(ConfigValues.GlassPane pane) {
            ItemStack item = new ItemStack(pane.material());
            ItemMeta meta = item.getItemMeta();

            if (meta != null) {
                meta.displayName(TextUtils.component(pane.displayName()));

                if (pane.customModelData() != null) {
                    CustomModelDataComponent component = meta.getCustomModelDataComponent();
                    component.setFloats(List.of(pane.customModelData().floatValue()));
                    meta.setCustomModelDataComponent(component);
                }

                item.setItemMeta(meta);
            }

            return item;
        }
    }
