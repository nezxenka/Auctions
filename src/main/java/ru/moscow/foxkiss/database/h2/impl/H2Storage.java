package ru.moscow.foxkiss.database.h2.impl;

import org.bukkit.plugin.java.JavaPlugin;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.database.h2.SqlRuntime;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

public final class H2Storage {
    static final int active = 0;
    static final int processing = 1;
    final JavaPlugin plugin;
    final ExecutorService asyncExecutor;
    final Set<String> liveOperations = Collections.synchronizedSet(new HashSet<>());
    private final DataSource dataSource;
    private final SqlRuntime sqlRuntime;

    public H2Storage(JavaPlugin plugin, DataSource dataSource, SqlRuntime sqlRuntime, ExecutorService asyncExecutor) {
        this.plugin = plugin;
        this.dataSource = dataSource;
        this.sqlRuntime = sqlRuntime;
        this.asyncExecutor = asyncExecutor;
    }

    public Connection open() throws SQLException { return sqlRuntime.open(dataSource); }

    static void lockSeller(Connection conn, String sellerName, AuctionCurrency currency) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO auction_listing_locks(seller_name,currency) KEY(seller_name,currency) VALUES(?,?)")) {
            ps.setString(1, sellerName);
            ps.setString(2, currency.name());
            ps.executeUpdate();
        }
    }
}
