package ru.moscow.foxkiss.auction.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.auction.services.sessions.DeliverySession;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.economy.EconomyProvider;
import ru.moscow.foxkiss.gui.ItemDisplayFactory;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.PlaceholderUtils;
import ru.moscow.foxkiss.utils.TextUtils;

import java.util.*;
import java.util.function.Consumer;

@Getter
@RequiredArgsConstructor
public final class AuctionTransactionService {
    private final SchedulerService scheduler;
    private final AuctionRepository repository;
    private final EconomyProvider economyProvider;
    private final ItemDisplayFactory itemFactory;
    private final Set<UUID> pendingListings = new HashSet<>();
    private final Set<UUID> delivering = new HashSet<>();
    @Getter
    private final Map<UUID, DeliverySession> sessions = new HashMap<>();
    private static final NamespacedKey receipte = new NamespacedKey("fmauction", "delivery_receipt");

    @Getter
    private final Map<UUID, Consumer<Player>> completionCallbacks = new HashMap<>();

    public void deliverPending(Player player, AuctionCurrency currency, IConfigManager config) {
        deliverPending(player, currency, config, null);
    }

    public void deliverPending(Player player, AuctionCurrency currency, IConfigManager config, Consumer<Player> onComplete) {
        if (!player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        synchronized (delivering) {
            DeliverySession running = sessions.get(uuid);
            if (running != null) {
                running.player = player;
                if (currency == null) { running.rescan = true; running.allRequested = true; }
                else running.claims.add(currency);
                if (onComplete != null) completionCallbacks.put(uuid, onComplete);
                return;
            }
        }
        Receipt receipt = readReceipt(player);
        DeliverySession session = new DeliverySession(uuid, player.getName(), player, config, receipt);
        if (currency != null) session.claims.add(currency);
        else session.allRequested = true;
        synchronized (delivering) {
            sessions.put(uuid, session);
            delivering.add(uuid);
            if (onComplete != null) completionCallbacks.put(uuid, onComplete);
        }
        loadDeliveries(session);
    }

    public void onPlayerQuit(Player player) {
        DeliverySession session;
        synchronized (delivering) {
            session = sessions.get(player.getUniqueId());
            if (session == null || session.player != player) return;
            session.player = null;
            if (session.inFlight) return;
        }
        finishDelivery(session);
    }

    private boolean isStaleSession(DeliverySession session) {
        synchronized (delivering) { return sessions.get(session.uuid) != session; }
    }

    private void loadDeliveries(DeliverySession session) {
        EnumSet<AuctionCurrency> claims;
        synchronized (delivering) {
            if (sessions.get(session.uuid) != session) return;
            claims = session.claims.clone();
            session.claims.clear();
            session.currencies = claims;
            session.all = session.allRequested;
            session.allRequested = false;
            session.commandBatch = !session.all && !claims.isEmpty();
            session.deliveredAny = false;
            session.rescan = false;
            session.inFlight = true;
        }
        scheduler.runAsync(() -> {
            List<PendingDelivery> pending;
            try {
                for (AuctionCurrency currency : claims) repository.claimPendingInventoryItems(session.name, currency);
                pending = repository.getPendingDeliveries(session.name);
            } catch (RuntimeException e) {
                scheduler.runSync(() -> finishDelivery(session));
                throw e;
            }
            scheduler.runSync(() -> {
                if (isStaleSession(session)) return;
                session.inFlight = false;
                session.pending = pending;
                session.index = 0;
                session.receiptPending = true;
                session.prioritizedIds.clear();
                if (session.receipt != null) {
                    List<Receipt> entries = session.receipt.entries();
                    if (entries.isEmpty()) session.prioritizedIds.add(session.receipt.deliveryId());
                    else for (Receipt entry : entries) session.prioritizedIds.add(entry.deliveryId());
                }
                continueDelivery(session);
            });
        });
    }

    private void continueDelivery(DeliverySession session) {
        try {
            deliverNext(session);
        } catch (RuntimeException e) {
            finishDelivery(session);
            throw e;
        }
    }

    private void deliverNext(DeliverySession session) {
        Player player = session.player;
        if (player == null || !player.isOnline()) { finishDelivery(session); return; }
        List<PendingDelivery> batch = new ArrayList<>(8);
        if (session.receiptPending && session.receipt != null) {
            for (PendingDelivery candidate : session.pending) {
                if (session.prioritizedIds.contains(candidate.id())) batch.add(candidate);
            }
        }
        session.receiptPending = false;
        boolean replay = !batch.isEmpty();
        while (!replay && batch.size() < 8 && session.index < session.pending.size()) {
            PendingDelivery candidate = session.pending.get(session.index++);
            if (session.prioritizedIds.contains(candidate.id())) continue;
            if (candidate.currency() != null && (session.all || session.currencies.contains(candidate.currency()))) batch.add(candidate);
        }
        if (!batch.isEmpty()) {
            session.deliveredAny = true;
            processDelivery(session, player, batch, replay);
            return;
        }
        boolean more;
        synchronized (delivering) { more = session.rescan || !session.claims.isEmpty(); }
        if (more) loadDeliveries(session);
        else {
            try {
                if (session.commandBatch && !session.deliveredAny) {
                    sendMessage(player, session.config, session.config.getConfigValues().messages().pendingItemsEmpty());
                }
            } finally { finishDelivery(session); }
        }
    }

    private void processDelivery(DeliverySession session, Player player, List<PendingDelivery> batch, boolean replay) {
        Receipt[] resolved = replay ? new Receipt[batch.size()] : null;
        for (int i = 0; i < batch.size(); i++) {
            PendingDelivery delivery = batch.get(i);
            if (delivery.currency() == null) {
                throw new IllegalStateException("Для старой доставки необходимо установить валюту: " + delivery.id());
            }
            if (replay) {
                Receipt entry = findReceipt(session.receipt, delivery.id());
                validateRemainder(deliveryContents(delivery), entry.leftovers());
                resolved[i] = entry;
            }
        }
        List<Receipt> entries = new ArrayList<>(batch.size());
        List<AuctionRepository.DeliveryConfirmation> confirmations = new ArrayList<>(batch.size());
        LongList received = new LongArrayList(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            PendingDelivery delivery = batch.get(i);
            List<ItemStack> leftovers;
            long receivedCount;
            if (replay) {
                leftovers = resolved[i].leftovers();
                receivedCount = 0;
            } else {
                List<ItemStack> contents = deliveryContents(delivery);
                leftovers = giveItems(player, contents);
                receivedCount = countItems(contents) - countItems(leftovers);
            }
            entries.add(new Receipt(delivery.id(), leftovers));
            confirmations.add(new AuctionRepository.DeliveryConfirmation(delivery.id(), delivery.currency(), leftovers));
            received.add(receivedCount);
        }

        if (!replay) {
            player.getPersistentDataContainer().set(receipte, PersistentDataType.STRING, encodeBatchReceipt(entries));
            Receipt first = entries.getFirst();
            session.receipt = new Receipt(first.deliveryId(), first.leftovers(), List.copyOf(entries));
        }
        player.saveData();
        confirmDelivery(session, List.copyOf(confirmations), received);
    }

    private static List<ItemStack> deliveryContents(PendingDelivery delivery) {
        return delivery.lotType() == AuctionLotType.INVENTORY ? delivery.inventoryContents() : List.of(delivery.item());
    }

    private void confirmDelivery(DeliverySession session, List<AuctionRepository.DeliveryConfirmation> confirmations, LongList received) {
        session.inFlight = true;
        scheduler.runAsync(() -> {
            boolean confirmed;
            try {
                confirmed = repository.completeInventoryDeliveries(session.name, confirmations);
            } catch (RuntimeException e) {
                scheduler.runSync(() -> finishDelivery(session));
                throw e;
            }
            scheduler.runSync(() -> {
                if (isStaleSession(session)) return;
                session.inFlight = false;
                Player player = session.player;
                if (!confirmed || player == null || !player.isOnline()) {
                    finishDelivery(session);
                    return;
                }
                try {
                    player.getPersistentDataContainer().remove(receipte);
                    session.receipt = null;
                    for (int i = 0; i < confirmations.size(); i++) {
                        AuctionRepository.DeliveryConfirmation delivery = confirmations.get(i);
                        notifyDelivery(player, session, delivery.currency(), delivery.items(), received.getLong(i));
                    }
                    deliverNext(session);
                } catch (RuntimeException e) {
                    finishDelivery(session);
                    throw e;
                }
            });
        });
    }

    private void finishDelivery(DeliverySession session) {
        Consumer<Player> callback;
        Player player;
        synchronized (delivering) {
            if (sessions.get(session.uuid) != session) return;
            sessions.remove(session.uuid);
            delivering.remove(session.uuid);
            callback = completionCallbacks.remove(session.uuid);
            player = session.player;
        }
        if (callback != null && player != null) {
            scheduler.runSync(() -> { if (player.isOnline()) callback.accept(player); });
        }
    }

    private void sendMessage(CommandSender sender, IConfigManager config, String message) {
        sender.sendMessage(TextUtils.component(PlaceholderUtils.apply(sender, message, config)));
    }

    private Receipt readReceipt(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String raw = pdc.get(receipte, PersistentDataType.STRING);
        if (raw != null) {
            return parseReceipt(raw);
        }
        if (pdc.has(receipte)) {
            throw new IllegalStateException("Неверный тип данных по ключу " + receipte.getKey() + "; необходима сверка");
        }
        return null;
    }


    private Receipt parseReceipt(String raw) {
        if (raw == null) return null;
        try {
            boolean batch = raw.startsWith("v3:");
            if (batch || raw.startsWith("v2:")) {
                int checksumAt = raw.lastIndexOf(':');
                if (checksumAt <= 3) throw new IllegalArgumentException("Отсутствует контрольная сумма");
                String body = raw.substring(3, checksumAt);
                if (!receiptChecksum(body).equals(raw.substring(checksumAt + 1))) {
                    throw new IllegalArgumentException("Контрольная сумма не совпадает");
                }
                raw = body;
            }
            if (batch) {
                String[] parts = raw.split("\\|", -1);
                if (parts.length == 0 || parts.length > 8) throw new IllegalArgumentException("Неверный размер пакета доставок");
                List<Receipt> entries = new ArrayList<>(parts.length);
                LongSet ids = new LongOpenHashSet();
                for (String part : parts) {
                    Receipt entry = parseReceiptEntry(part);
                    if (!ids.add(entry.deliveryId())) throw new IllegalArgumentException("Повторный идентификатор доставки");
                    entries.add(entry);
                }
                Receipt first = entries.getFirst();
                return new Receipt(first.deliveryId(), first.leftovers(), List.copyOf(entries));
            }
            return parseReceiptEntry(raw);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Квитанция повреждена; выдача остановлена до сверки", e);
        }
    }

    private static Receipt parseReceiptEntry(String raw) {
        int separator = raw.indexOf(':');
        if (separator <= 0) throw new IllegalArgumentException("Отсутствует идентификатор");
        for (int i = 0; i < separator; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("Некорректный идентификатор");
        }
        long id = Long.parseLong(raw.substring(0, separator));
        if (id <= 0) throw new IllegalArgumentException("Идентификатор должен быть положительным");
        return new Receipt(id, decodeItems(raw.substring(separator + 1)));
    }

    private static String encodeBatchReceipt(List<Receipt> entries) {
        StringBuilder body = new StringBuilder();
        for (Receipt entry : entries) {
            if (!body.isEmpty()) body.append('|');
            body.append(entry.deliveryId()).append(':').append(encodeItems(entry.leftovers()));
        }
        return "v3:" + body + ":" + receiptChecksum(body.toString());
    }

    private static Receipt findReceipt(Receipt receipt, long id) {
        if (receipt != null) {
            List<Receipt> entries = receipt.entries();
            if (entries.isEmpty()) {
                if (receipt.deliveryId() == id) return receipt;
            } else {
                for (Receipt entry : entries) if (entry.deliveryId() == id) return entry;
            }
        }
        throw new IllegalStateException("В квитанции отсутствует доставка " + id);
    }

    private static String receiptChecksum(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Алгоритм SHA-256 недоступен", e);
        }
    }

    private static void validateRemainder(List<ItemStack> contents, List<ItemStack> leftovers) {
        int[] available = new int[contents.size()];
        for (int i = 0; i < available.length; i++) {
            ItemStack item = contents.get(i);
            if (item != null) available[i] = item.getAmount();
        }
        for (ItemStack left : leftovers) {
            int needed = left.getAmount();
            for (int i = 0; i < available.length && needed > 0; i++) {
                if (available[i] > 0 && left.isSimilar(contents.get(i))) {
                    int take = Math.min(needed, available[i]);
                    needed -= take;
                    available[i] -= take;
                }
            }
            if (needed != 0) throw new IllegalStateException("Остаток квитанции превышает содержимое доставки");
        }
    }

    private static long countItems(List<ItemStack> items) {
        long count = 0;
        for (ItemStack item : items) if (item != null) count += item.getAmount();
        return count;
    }

    private void notifyDelivery(Player player, DeliverySession session, AuctionCurrency currency, List<ItemStack> items, long received) {
        IConfigManager config = session.config;
        if (session.commandBatch && received > 0) {
            sendMessage(player, config, config.getConfigValues().messages().pendingItemsReceived().replace("{amount}", String.valueOf(received)));
        }
        if (items.isEmpty()) return;
        String raw = session.commandBatch ? config.getConfigValues().messages().pendingItemsRemaining() : config.getConfigValues().messages().pendingItemsSaved();
        sendMessage(player, config, raw.replace("{amount}", String.valueOf(countItems(items))).replace("{command}", currency == AuctionCurrency.VAULT ? "/ah items" : "/dah items"));
    }

    private static String encodeItems(List<ItemStack> items) {
        StringBuilder encoded = new StringBuilder();
        Base64.Encoder encoder = Base64.getEncoder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) encoded.append(';');
            encoded.append(encoder.encodeToString(items.get(i).serializeAsBytes()));
        }
        return encoded.toString();
    }

    private static List<ItemStack> decodeItems(String data) {
        if (data.isEmpty()) return List.of();
        String[] parts = data.split(";", -1);
        List<ItemStack> items = new ArrayList<>(parts.length);
        Base64.Decoder decoder = Base64.getDecoder();
        for (String part : parts) {
            if (part.isEmpty()) throw new IllegalArgumentException("Пустая запись предмета");
            ItemStack item = ItemStack.deserializeBytes(decoder.decode(part));
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) throw new IllegalArgumentException("Некорректный предмет");
            items.add(item);
        }
        return List.copyOf(items);
    }

    public record Receipt(long deliveryId, List<ItemStack> leftovers, List<Receipt> entries) {
        public Receipt(long deliveryId, List<ItemStack> leftovers) { this(deliveryId, leftovers, List.of()); }
    }

    public boolean beginListing(Player player) {
        return pendingListings.add(player.getUniqueId());
    }

    public void endListing(Player player) {
        pendingListings.remove(player.getUniqueId());
    }

    public boolean hasInsufficientFunds(Player player, AuctionCurrency currency, double amount) {
        return !economyProvider.has(player, currency, amount);
    }

    public boolean withdrawMoney(Player player, AuctionCurrency currency, double amount) {
        return economyProvider.withdraw(player, currency, amount);
    }

    public boolean depositMoney(OfflinePlayer player, AuctionCurrency currency, double amount) {
        return economyProvider.deposit(player, currency, amount);
    }

    public void returnItems(Player player, AuctionCurrency currency, List<ItemStack> items, IConfigManager config) {
        String name = player.getName();
        List<ItemStack> returned = List.copyOf(items);
        scheduler.runAsync(() -> {
            repository.savePendingDelivery(name, currency, returned);
            scheduler.runSync(() -> deliverPending(player, null, config));
        });
    }

    public List<ItemStack> giveItems(Player player, List<ItemStack> items) {
        List<ItemStack> leftovers = null;
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) continue;
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(item.clone());
            if (remaining.isEmpty()) continue;
            if (leftovers == null) leftovers = new ArrayList<>(remaining.size());
            for (ItemStack left : remaining.values()) leftovers.add(left);
        }
        return leftovers == null ? List.of() : leftovers;
    }
}
