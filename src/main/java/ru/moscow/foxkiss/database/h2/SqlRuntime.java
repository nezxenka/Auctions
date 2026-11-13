package ru.moscow.foxkiss.database.h2;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class SqlRuntime {
    private final Semaphore connections = new Semaphore(8, true);
    private final Semaphore requests = new Semaphore(72, true);
    private final Map<String, Samples> metrics = new HashMap<>();
    private long revision;
    private int writers;

    public synchronized long cacheRevision() {
        return writers == 0 ? revision : -1;
    }
    public synchronized void invalidateCache() {
        revision++;
    }
    private synchronized void beginWrite() {
        writers++; revision++;
    }

    private synchronized void endWrite() {
        writers--; revision++;
    }

    public Connection open(DataSource source) throws SQLException {
        if (!requests.tryAcquire()) {
            throw new SQLTransientConnectionException("Очередь запросов к базе данных заполнена");
        }
        long start = System.nanoTime();
        try {
            if (!connections.tryAcquire(2, TimeUnit.SECONDS))
                throw new SQLTransientConnectionException("Истекло время ожидания доступа к базе данных");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requests.release();
            throw new SQLTransientConnectionException("Ожидание соединения прервано", e);
        }
        try {
            Connection raw = source.getConnection();
            record("Ожидание соединения", System.nanoTime() - start, false);
            return wrap(raw);
        } catch (SQLException | RuntimeException e) {
            connections.release();
            requests.release();
            record("Ожидание соединения", System.nanoTime() - start, true);
            throw e;
        }
    }

    private Connection wrap(Connection raw) {
        return (Connection) Proxy.newProxyInstance(SqlRuntime.class.getClassLoader(), new Class<?>[]{Connection.class}, new ConnectionHandler(this, raw));
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final SqlRuntime outer;
        private final Connection raw;
        private boolean closed, dirty;

        ConnectionHandler(SqlRuntime outer, Connection raw) {
            this.outer = outer;
            this.raw = raw;
        }

        private void dirty() {
            if (!dirty) { dirty = true; outer.beginWrite(); }
        }

        private void clean() {
            if (dirty) { dirty = false; outer.endWrite(); }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                if (closed) return null;
                closed = true;
                try {
                    return call(raw, method, args);
                } finally {
                    clean();
                    outer.connections.release();
                    outer.requests.release();
                }
            }
            Object result = call(raw, method, args);
            if ("commit".equals(name) || "rollback".equals(name)
                    || ("setAutoCommit".equals(name) && Boolean.TRUE.equals(args[0]))) {
                clean();
            }
            if (!(result instanceof Statement statement)) return result;
            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "";
            Class<?> api = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(SqlRuntime.class.getClassLoader(), new Class<?>[]{api}, (p, m, values) -> {
                if ("getConnection".equals(m.getName())) return proxy;
                if (!m.getName().startsWith("execute")) return call(statement, m, values);
                String query = values != null && values.length > 0 && values[0] instanceof String text ? text : sql;
                boolean write = changesCatalog(query);
                if (write) dirty();
                long start = System.nanoTime();
                boolean error = false;
                try {
                    return call(statement, m, values);
                } catch (Throwable failure) {
                    error = true;
                    throw failure;
                } finally {
                    outer.record(query.isEmpty() ? m.getName() : query, System.nanoTime() - start, error);
                    if (write && raw.getAutoCommit()) clean();
                }
            });
        }
    }

    private static boolean changesCatalog(String sql) {
        String query = sql.stripLeading().toLowerCase(Locale.ENGLISH);
        if (query.startsWith("select")) return false;
        return query.contains("auction_items") || query.contains("auction_player_profiles") || query.contains("sales_history");
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private synchronized void record(String sql, long nanos, boolean error) {
        String key = (!metrics.containsKey(sql) && metrics.size() >= 64) ? "Прочие запросы" : sql;
        Samples sample = metrics.computeIfAbsent(key, ignored -> new Samples());
        sample.times[(int) (sample.count++ % sample.times.length)] = nanos;
        if (error) sample.errors++;
    }

    public synchronized void clearSamples() { metrics.clear(); }

    private static final class Samples {
        final long[] times = new long[1024];
        long count, errors;
    }
}