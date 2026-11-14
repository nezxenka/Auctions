package ru.moscow.foxkiss;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionRepository;
import ru.moscow.foxkiss.auction.AuctionService;
import ru.moscow.foxkiss.database.h2.H2AuctionRepository;
import ru.moscow.foxkiss.commands.AdminCommand;
import ru.moscow.foxkiss.commands.AuctionCommand;
import ru.moscow.foxkiss.config.impl.ConfigManager;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.economy.PluginEconomyProvider;
import ru.moscow.foxkiss.gui.AuctionMenu;
import ru.moscow.foxkiss.gui.AuctionMenuListener;
import ru.moscow.foxkiss.gui.ItemDisplayFactory;
import ru.moscow.foxkiss.gui.PlayerPreferences;
import ru.moscow.foxkiss.gui.ProfileInputService;
import ru.moscow.foxkiss.permissions.LimitService;
import ru.moscow.foxkiss.placeholders.FMAuctionExpansion;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.ItemUtils;
import ru.moscow.foxkiss.update.UpdateChecker;
import ru.moscow.foxkiss.utils.PlaceholderUtils;

public final class FMAuction extends JavaPlugin {

    private IConfigManager configManager;
    private AuctionRepository auctionRepository;
    private AuctionService auctionService;
    private AuctionMenu auctionMenu;
    private PluginEconomyProvider economyProvider;
    private LimitService limitService;
    private FMAuctionExpansion placeholderExpansion;
    private AuctionCommand auctionCommand;
    private AuctionCommand donateAuctionCommand;
    private AuctionMenuListener auctionMenuListener;
    private SchedulerService schedulerService;
    private ProfileInputService profileInputService;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();

        initializeManager();
        setupMetrics();
        PlaceholderUtils.init();

        registerCommands();
        registerListeners();
        registerPlaceholders();
        updateChecker.checkForUpdates();

        long endTime = System.currentTimeMillis();
        getLogger().info("Plugin enabled in " + (endTime - startTime) + " ms");
    }

    @Override
    public void onDisable() {
        if (profileInputService != null) {
            profileInputService.close();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.saveData();
        }

        if (schedulerService != null) {
            schedulerService.shutdown();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.disable();
            placeholderExpansion.unregister();
        }
        if (auctionRepository != null) {
            auctionRepository.close();
        }

    }

    private void setupMetrics() {
        if (!configManager.getConfigValues().bStatsEnabled()) {
            return;
        }

        int pluginId = 33068;
        new Metrics(this, pluginId);
    }

    public void initializeManager() {
        configManager = new ConfigManager(this);
        auctionRepository = new H2AuctionRepository(this);
        auctionRepository.init();

        economyProvider = new PluginEconomyProvider();
        economyProvider.init();

        limitService = new LimitService(configManager);
        limitService.init();

        schedulerService = new SchedulerService(this);
        ItemDisplayFactory itemDisplayFactory = new ItemDisplayFactory(configManager);

        auctionService = AuctionService.create(this, configManager, auctionRepository, economyProvider, limitService, itemDisplayFactory, schedulerService);

        PlayerPreferences playerPreferences = new PlayerPreferences();
        profileInputService = new ProfileInputService(this, configManager, auctionRepository, schedulerService);
        auctionMenu = new AuctionMenu(this, configManager, auctionRepository, playerPreferences, itemDisplayFactory, schedulerService, profileInputService);

        auctionRepository.recoverStuckLots(economyProvider, true);
        schedulerService.runTimerAsync(() -> auctionRepository.recoverStuckLots(economyProvider, false), 20L * 60, 20L * 60);
    }

    public void reloadAll() {
        configManager.reload();
        if (auctionRepository instanceof H2AuctionRepository repository) repository.clearReadCache();
        limitService.init();

        if (auctionMenu != null) {
            auctionMenu.reload();
        }
        if (auctionMenuListener != null) {
            auctionMenuListener.reloadCategories();
        }
        if (auctionCommand != null) {
            auctionCommand.clearCache();
        }
        if (donateAuctionCommand != null) {
            donateAuctionCommand.clearCache();
        }
        ItemUtils.clearCache();
    }

    private void registerCommands() {
        auctionCommand = new AuctionCommand(configManager, AuctionCurrency.VAULT, auctionMenu, auctionService);
        donateAuctionCommand = new AuctionCommand(configManager, AuctionCurrency.PLAYER_POINTS, auctionMenu, auctionService);
        AdminCommand adminCommand = new AdminCommand(this, configManager);

        getCommand("ah").setExecutor(auctionCommand);
        getCommand("dauc").setExecutor(donateAuctionCommand);
        getCommand("fmauction").setExecutor(adminCommand);
    }

    private void registerListeners() {
        auctionMenuListener = new AuctionMenuListener(configManager, auctionMenu, auctionService);
        getServer().getPluginManager().registerEvents(auctionMenuListener, this);
        getServer().getPluginManager().registerEvents(profileInputService, this);

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
    }

    private void registerPlaceholders() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new FMAuctionExpansion(this, auctionRepository, schedulerService);
            if (placeholderExpansion.register()) {
                getLogger().info("Расширение PlaceholderAPI успешно зарегистрировано.");
            } else {
                getLogger().warning("Не удалось зарегистрировать расширение PlaceholderAPI.");
            }
        }
    }
}
