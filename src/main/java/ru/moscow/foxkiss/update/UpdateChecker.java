package ru.moscow.foxkiss.update;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moscow.foxkiss.utils.TextUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker implements Listener {

    private static final String VERSION_URL = "https://raw.githubusercontent.com/GriefSturd/FMAuctions/main/build.gradle";

    private static final String RELEASE_URL = "https://github.com/GriefSturd/FMAuctions/releases/latest";

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*version\\s*=\\s*['\"]([^'\"]+)['\"]");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Pattern PATTERN = Pattern.compile("^[vV]");
    private static final Pattern REGEX = Pattern.compile("[-+].*$");


    private final JavaPlugin plugin;
    private String latestVersion;

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkForUpdates() {
        if (!plugin.getConfig().getBoolean("update-checker", true)) {
            return;
        }

        String installed = plugin.getPluginMeta().getVersion();

        checkUpdates(version -> {
            if (!isNewer(version, installed)) {
                latestVersion = null;
                return;
            }

            latestVersion = version;

            plugin.getLogger().warning("Доступно обновление " + version + " (установлено: " + installed + "). Скачать: " + RELEASE_URL);
        });
    }

    public void checkUpdates(Consumer<String> consumer) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(VERSION_URL)).timeout(Duration.ofSeconds(10)).GET().build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Не удалось проверить обновления: HTTP " + response.statusCode());
                    return;
                }

                Matcher matcher = VERSION_PATTERN.matcher(response.body());

                if (!matcher.find()) {
                    plugin.getLogger().warning("Не удалось найти версию в build.gradle.");
                    return;
                }

                consumer.accept(matcher.group(1));

            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось проверить обновления: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String version = latestVersion;

        if (version == null || !player.hasPermission("fmauction.update")) {
            return;
        }

        player.sendMessage(TextUtils.colorize("&6[" + plugin.getName() + "] &eДоступно обновление &f " + version + " &e. Скачать: &b " + RELEASE_URL));
    }

    private static boolean isNewer(String candidate, String installed) {
        String[] latest = normalize(candidate).split("\\.");
        String[] current = normalize(installed).split("\\.");

        int length = Math.max(latest.length, current.length);

        for (int i = 0; i < length; i++) {
            int a = i < latest.length ? parsePart(latest[i]) : 0;
            int b = i < current.length ? parsePart(current[i]) : 0;

            if (a != b) {
                return a > b;
            }
        }

        return false;
    }

    private static String normalize(String version) {
        return REGEX.matcher(PATTERN.matcher(version).replaceFirst("")).replaceFirst("");
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}