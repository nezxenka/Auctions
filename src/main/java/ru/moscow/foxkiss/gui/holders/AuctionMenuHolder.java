package ru.moscow.foxkiss.gui.holders;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2BooleanMap;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionSort;
import ru.moscow.foxkiss.gui.AuctionViewType;
import ru.moscow.foxkiss.gui.actions.Action;
import ru.moscow.foxkiss.gui.holders.records.LotSnapshot;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public abstract class AuctionMenuHolder implements InventoryHolder {

    protected AuctionViewType viewType;
    protected AuctionCurrency currency;
    protected final UUID viewer;
    protected int page;
    protected AuctionSort sort;
    protected String sellerFilter;
    protected String searchFilter;
    protected String category;

    private AuctionSort renderedSort;
    private String renderedCategory;

    private int renderedPage = -1;
    private int renderedTotalPages = -1;
    private int renderedSellingCount = -1;
    private int renderedExpiredCount = -1;
    private String renderedCounterPage;

    protected final Int2LongMap lotsBySlot = new Int2LongOpenHashMap();
    protected final Int2IntMap lotsAmountBySlot = new Int2IntOpenHashMap();
    protected final Int2BooleanMap inventoryLotBySlot = new Int2BooleanOpenHashMap();
    protected final Int2ObjectMap<List<Action>> actionsBySlot = new Int2ObjectOpenHashMap<>();
    protected final Int2ObjectMap<LotSnapshot> lotSnapshots = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectMap<String> materialGroups = new Int2ObjectOpenHashMap<>();
    private final Int2IntMap materialGroupCounts = new Int2IntOpenHashMap();
    private String renderedMaterialCardName;
    private List<String> renderedMaterialCardLore;

    protected int totalPages = 1;
    private long requestVersion = 0;
    private Inventory inventory;

    public AuctionMenuHolder(AuctionViewType viewType, AuctionCurrency currency, UUID viewer, int page, AuctionSort sort, String sellerFilter, String searchFilter, String category) {
        this.viewType = viewType;
        this.currency = currency;
        this.viewer = viewer;
        this.page = page;
        this.sort = sort;
        this.sellerFilter = sellerFilter;
        this.searchFilter = searchFilter;
        this.category = category;
    }

    public long incrementAndGetRequestVersion() { return ++requestVersion; }

    public void addLot(int slot, long id, int amount, boolean inventory) {
        lotsBySlot.put(slot, id);
        lotsAmountBySlot.put(slot, amount);
        inventoryLotBySlot.put(slot, inventory);
    }

    public boolean isInventoryLot(int slot) {
        return inventoryLotBySlot.get(slot);
    }

    public void addActions(int slot, List<Action> actions) {
        actionsBySlot.put(slot, actions);
    }

    public List<Action> getActions(int slot) {
        return actionsBySlot.get(slot);
    }


    public Long getLot(int slot) {
        return lotsBySlot.isEmpty() || !lotsBySlot.containsKey(slot) ? null : lotsBySlot.get(slot);
    }

    public Integer getLotAmount(int slot) {
        return lotsAmountBySlot.isEmpty() || !lotsAmountBySlot.containsKey(slot) ? null : lotsAmountBySlot.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
