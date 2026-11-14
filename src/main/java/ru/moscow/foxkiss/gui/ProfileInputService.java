package ru.moscow.foxkiss.gui;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.moscow.foxkiss.auction.AuctionRepository;
import ru.moscow.foxkiss.auction.PlayerProfile;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.PlaceholderUtils;
import ru.moscow.foxkiss.utils.TextUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ProfileInputService implements Listener {
    private final IConfigManager configManager;
    private final AuctionRepository repository;
    private final SchedulerService scheduler;
    private final JavaPlugin plugin;
    private final Map<UUID, Input> inputs = new HashMap<>();
    private final Set<UUID> saving = new HashSet<>();
    private boolean closed;

    public ProfileInputService(JavaPlugin plugin, IConfigManager configManager, AuctionRepository repository, SchedulerService scheduler) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.repository = repository;
        this.scheduler = scheduler;
    }

    public void open(Player player, boolean discord) {
        UUID uuid = player.getUniqueId();
        if (closed || !player.isOnline() || saving.contains(uuid)) return;
        cancelInput(uuid);
        player.closeInventory();
        Location location = player.getLocation().toBlockLocation();
        location.setY(Math.max(location.getWorld().getMinHeight(), Math.min(location.getWorld().getMaxHeight() - 1, location.getBlockY() + 2)));

        BlockData data = Bukkit.createBlockData(Material.OAK_SIGN);
        Sign sign = (Sign) data.createBlockState();
        sign.getSide(Side.FRONT).line(0, Component.empty());
        sign.getSide(Side.FRONT).line(1, Component.empty());
        sign.getSide(Side.FRONT).line(2, Component.text("^^^^^^^^"));
        sign.getSide(Side.FRONT).line(3, Component.text("Введите текст"));

        Input input = new Input(player, location, discord);
        inputs.put(uuid, input);
        try {
            player.sendBlockChange(location, data);
            player.sendBlockUpdate(location, sign);
            input.openTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (inputs.get(uuid) != input) return;
                if (!player.isOnline() || player.getWorld() != location.getWorld()) {
                    cancelInput(uuid);
                    return;
                }
                try {
                    player.openVirtualSign(Position.block(location.getBlockX(), location.getBlockY(), location.getBlockZ()), Side.FRONT);
                } catch (RuntimeException error) {
                    cancelInput(uuid);
                    plugin.getLogger().log(Level.WARNING, "Не удалось открыть ввод профиля", error);
                    sendMessage(player, configManager.getConfigValues().messages().databaseError());
                }
            }, 1L);
            input.timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (inputs.get(uuid) == input) cancelInput(uuid);
            }, 400L);
        } catch (RuntimeException error) {
            cancelInput(uuid);
            throw error;
        }
    }

    @EventHandler
    public void onSignChange(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Input input = inputs.get(uuid);
        if (input == null || input.player != player || event.getSide() != Side.FRONT
                || player.getWorld() != input.location.getWorld()) return;
        var position = event.getEditedBlockPosition();
        if (position.blockX() != input.location.getBlockX() || position.blockY() != input.location.getBlockY()
                || position.blockZ() != input.location.getBlockZ()) return;

        event.setCancelled(true);
        cancelInput(uuid);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < Math.min(2, event.lines().size()); i++) {
            value.append(PlainTextComponentSerializer.plainText().serialize(event.lines().get(i)));
        }
        String colored = TextUtils.colorize(value.toString().trim());
        int maxLength = configManager.getConfigValues().profileMenu().maxLength();
        if (colored.length() > maxLength) {
            sendMessage(player, configManager.getConfigValues().messages().tooLong()
                    .replace("{max_length}", String.valueOf(maxLength)));
            return;
        }

        String playerName = player.getName();
        saving.add(uuid);
        scheduler.runAsync(() -> {
            try {
                PlayerProfile profile = repository.getPlayerProfile(playerName);
                repository.savePlayerProfile(playerName,
                        input.discord ? colored : profile.discord(),
                        input.discord ? profile.storeName() : colored);
                scheduler.runSync(() -> {
                    saving.remove(uuid);
                    if (closed || !player.isOnline()) return;
                    var messages = configManager.getConfigValues().messages();
                    String message = colored.isEmpty()
                            ? (input.discord ? messages.discordCleared() : messages.storeCleared())
                            : (input.discord ? messages.discordSet() : messages.storeSet()).replace("{tag}", colored);
                    sendMessage(player, message);
                });
            } catch (RuntimeException error) {
                if (closed) return;
                plugin.getLogger().log(Level.WARNING, "Не удалось сохранить профиль игрока " + playerName, error);
                scheduler.runSync(() -> {
                    saving.remove(uuid);
                    if (player.isOnline()) sendMessage(player, configManager.getConfigValues().messages().databaseError());
                });
            }
        });
    }

    private void cancelInput(UUID uuid) {
        Input input = inputs.remove(uuid);
        if (input == null) return;
        if (input.openTask != null) input.openTask.cancel();
        if (input.timeout != null) input.timeout.cancel();
        Location location = input.location;
        if (input.player.isOnline() && input.player.getWorld() == location.getWorld()
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            input.player.sendBlockChange(location, location.getBlock().getBlockData());
            if (location.getBlock().getState() instanceof TileState tile) {
                input.player.sendBlockUpdate(location, tile);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) { cancelInput(event.getPlayer().getUniqueId()); }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { cancelInput(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) { cancelInput(event.getPlayer().getUniqueId()); }

    public void close() {
        closed = true;
        for (UUID uuid : new HashSet<>(inputs.keySet())) cancelInput(uuid);
        saving.clear();
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(TextUtils.component(PlaceholderUtils.apply(sender, message, configManager)));
    }

    private static final class Input {
        final Player player;
        final Location location;
        final boolean discord;
        BukkitTask openTask;
        BukkitTask timeout;

        Input(Player player, Location location, boolean discord) {
            this.player = player;
            this.location = location;
            this.discord = discord;
        }
    }
}
