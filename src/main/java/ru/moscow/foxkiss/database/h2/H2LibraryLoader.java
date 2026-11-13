package ru.moscow.foxkiss.database.h2;

import java.net.URLConnection;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

public final class H2LibraryLoader {

    private static final String version = "2.2.224";
    private static final String library = "h2-" + version + ".jar";
    private static final String base = "https://repo1.maven.org/maven2/com/h2database/h2/" + version + "/";

    private static URLClassLoader classLoader;
    private static final Object lock = new Object();

    public static DataSource loadDataSource(JavaPlugin plugin, String jdbcUrl) {
        URLClassLoader loader = getClassLoader(plugin);
        try {
            Class<?> poolClass = Class.forName("org.h2.jdbcx.JdbcConnectionPool", true, loader);
            DataSource ds = createPool(poolClass, jdbcUrl, "SA", "");
            try (Connection ignored = ds.getConnection()) {
                plugin.getLogger().info("Подключение к H2 успешно установлено.");
                return ds;
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка с пользователем 'SA', пробуем пустой пользователь.");
                dispose(ds);
                DataSource fallback = createPool(poolClass, jdbcUrl, "", "");
                try (Connection ignored2 = fallback.getConnection()) {
                    plugin.getLogger().warning("Используется пустой пользователь.");
                    return fallback;
                } catch (SQLException e2) {
                    dispose(fallback);
                    e.addSuppressed(e2);
                    throw new IllegalStateException("Не удалось подключиться к H2", e);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Ошибка создания DataSource H2", e);
        }
    }

    private static DataSource createPool(Class<?> poolClass, String jdbcUrl, String user, String pass) throws ReflectiveOperationException {
        return (DataSource) poolClass.getMethod("create", String.class, String.class, String.class)
                .invoke(null, jdbcUrl, user, pass);
    }

    private static void dispose(Object pool) {
        try {
            pool.getClass().getMethod("dispose").invoke(pool);
        } catch (ReflectiveOperationException ignored) {}
    }

    private static URLClassLoader getClassLoader(JavaPlugin plugin) {
        if (classLoader != null) return classLoader;
        synchronized (lock) {
            if (classLoader != null) return classLoader;
            Path libFile = plugin.getDataFolder().toPath().resolve("libraries").resolve(library);
            try {
                Files.createDirectories(libFile.getParent());
                if (!Files.exists(libFile)) {
                    plugin.getLogger().info("Загрузка H2 " + version + "...");
                    downloadAndVerify(libFile);
                }
                classLoader = new URLClassLoader(new URL[]{libFile.toUri().toURL()}, H2LibraryLoader.class.getClassLoader());
                return classLoader;
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new IllegalStateException("Не удалось загрузить H2", e);
            }
        }
    }

    private static void downloadAndVerify(Path target) throws IOException, NoSuchAlgorithmException {
        Path temp = target.resolveSibling(library + ".tmp");
        try (InputStream in = openDownload(base + library)) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        String expected;
        try (InputStream in = openDownload(base + library + ".sha1")) {
            expected = new String(in.readAllBytes()).trim().split("\\s+")[0];
        }
        String actual = sha1(temp);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(temp);
            throw new IOException("Контрольная сумма H2 не совпадает");
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static InputStream openDownload(String address) throws IOException {
        URLConnection connection = URI.create(address).toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        return connection.getInputStream();
    }

    private static String sha1(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}