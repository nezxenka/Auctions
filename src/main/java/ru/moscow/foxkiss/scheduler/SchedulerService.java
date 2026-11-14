
package ru.moscow.foxkiss.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

public final class SchedulerService {

    private final JavaPlugin plugin;

    private final ExecutorService dbExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Executor mainExecutor;

    public SchedulerService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mainExecutor = task -> Bukkit.getScheduler().runTask(plugin, task);
    }

    public void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;

        try {
            dbExecutor.execute(() -> execute(task, "Ошибка асинхронной задачи"));
        } catch (RejectedExecutionException ignored) {
        }
    }

    public void runSync(Runnable task) {
        submitMain(() -> execute(task, "Ошибка синхронной задачи"));
    }

    public void runTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) return;

        try {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> execute(task, "Ошибка периодической задачи"), delayTicks, periodTicks);
        } catch (IllegalPluginAccessException ignored) {
        }
    }

    private void submitMain(Runnable task) {
        if (!plugin.isEnabled()) return;

        try {
            mainExecutor.execute(task);
        } catch (IllegalPluginAccessException ignored) {
        }
    }

    private void execute(Runnable task, String errorMessage) {
        try {
            task.run();
        } catch (Exception e) {
            logError(errorMessage, e);
        }
    }

    private void logError(String message, Exception e) {
        plugin.getLogger().log(Level.SEVERE, message, e);
    }

    public void shutdown() {
        dbExecutor.shutdown();
    }
}
