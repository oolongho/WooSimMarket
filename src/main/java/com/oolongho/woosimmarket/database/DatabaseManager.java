package com.oolongho.woosimmarket.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * SQLite 数据库连接与表结构管理器。
 *
 * <p>负责加载驱动、建立单连接、设置 WAL 模式与外键、创建 shops/shelves 两表及索引。
 * 单连接 + {@link ReentrantLock} 保护并发：所有 DAO 操作必须在 {@link #getLock()} 内执行，
 * 避免 SQLite 单连接被多线程同时写入导致的锁竞争与 "database is locked" 异常。</p>
 *
 * <p>线程模型：DAO 方法同步执行（业务上层应通过 {@code TaskUtil} 将数据库 IO 投递到异步线程，
 * 本层不内置异步调度，保持单一职责）。</p>
 *
 * @author oolongho
 */
public class DatabaseManager {

    /**
     * Shop 表的不可变数据载体（DAO 入参/出参）。
     *
     * <p>location 拆分为 world + x + y + z 便于 SQL 查询与索引；facing 存字符串枚举名
     * （NORTH/SOUTH/EAST/WEST）；balance 为玩家待提现金额；createdAt 为毫秒时间戳；
     * notifyEnabled 表示 NPC 购买时是否向店主广播提示（默认 true）。</p>
     */
    public record ShopRecord(
            String id,
            UUID ownerUuid,
            String world,
            int x, int y, int z,
            String facing,
            double balance,
            long createdAt,
            String name,
            boolean notifyEnabled
    ) {
    }

    /**
     * Shelf 表的不可变数据载体（DAO 入参/出参）。
     *
     * <p>itemStackBase64 为 {@link com.oolongho.woosimmarket.util.SerializationUtils#serializeItemStack}
     * 的产物，空货架时为 null；enabled 用 0/1 存储于 SQLite。</p>
     */
    public record ShelfRecord(
            String id,
            String shopId,
            String world,
            int x, int y, int z,
            String facing,
            String itemStackBase64,
            double price,
            int stock,
            int maxStock,
            boolean enabled
    ) {
    }

    /**
     * purchase_log 表的不可变数据载体（DAO 入参/出参）。
     *
     * <p>id 为自增主键，插入时由数据库生成（record 的 id 字段被忽略）；bought 表示 NPC 是否
     * 成交；personality 存枚举名字符串；timestamp 为毫秒时间戳，用于按时间窗口清理与统计。</p>
     */
    public record PurchaseLogRecord(
            long id,
            String shopId,
            String itemId,
            double price,
            boolean bought,
            String personality,
            long timestamp
    ) {
    }

    private static final String DRIVER_CLASS = "org.sqlite.JDBC";
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private static final String SQL_PRAGMA_JOURNAL = "PRAGMA journal_mode=WAL";
    private static final String SQL_PRAGMA_SYNC = "PRAGMA synchronous=NORMAL";
    private static final String SQL_PRAGMA_FK = "PRAGMA foreign_keys=ON";

    private static final String SQL_CREATE_SHOPS = """
            CREATE TABLE IF NOT EXISTS shops (
                id TEXT PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                facing TEXT NOT NULL,
                balance REAL NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                notify_enabled INTEGER NOT NULL DEFAULT 1
            )""";

    /** 旧库迁移：若 shops 表缺少 notify_enabled 列则添加（SQLite 不支持 IF NOT EXISTS for ADD COLUMN）。 */
    private static final String SQL_MIGRATE_SHOPS_NOTIFY = "ALTER TABLE shops ADD COLUMN notify_enabled INTEGER NOT NULL DEFAULT 1";

    private static final String SQL_CREATE_SHOPS_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_shops_owner ON shops(owner_uuid)";

    private static final String SQL_CREATE_SHELVES = """
            CREATE TABLE IF NOT EXISTS shelves (
                id TEXT PRIMARY KEY,
                shop_id TEXT NOT NULL,
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                facing TEXT NOT NULL,
                item_stack TEXT,
                price REAL NOT NULL DEFAULT 0,
                stock INTEGER NOT NULL DEFAULT 0,
                max_stock INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
            )""";

    private static final String SQL_CREATE_SHELVES_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_shelves_shop_id ON shelves(shop_id)";

    private static final String SQL_CREATE_PURCHASE_LOG = """
            CREATE TABLE IF NOT EXISTS purchase_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shop_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                price REAL NOT NULL,
                bought INTEGER NOT NULL,
                personality TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
            )""";

    private static final String SQL_CREATE_PURCHASE_LOG_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_purchase_log_shop_id ON purchase_log(shop_id, id DESC)";

    private final JavaPlugin plugin;
    private final String fileName;
    private volatile Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 构造数据库管理器。
     *
     * @param plugin   插件实例，用于获取数据目录与 logger
     * @param fileName 数据库文件路径（如 {@code "data/woosimmarket.db"}），与 ConfigLoader 解耦
     */
    public DatabaseManager(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName;
    }

    /**
     * 初始化数据库：加载驱动、建立连接、设置 PRAGMA、建表建索引。
     *
     * @throws SQLException 驱动缺失、目录创建失败或连接建立失败时抛出
     */
    public void init() throws SQLException {
        lock.lock();
        try {
            try {
                Class.forName(DRIVER_CLASS);
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite JDBC driver not found on classpath", e);
            }

            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                throw new SQLException("Cannot create plugin data folder: " + dataFolder);
            }
            File dbFile = new File(dataFolder, fileName);
            File parent = dbFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Cannot create data directory: " + parent);
            }

            connection = DriverManager.getConnection(JDBC_PREFIX + dbFile.getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute(SQL_PRAGMA_JOURNAL);
                stmt.execute(SQL_PRAGMA_SYNC);
                stmt.execute(SQL_PRAGMA_FK);
                stmt.execute(SQL_CREATE_SHOPS);
                stmt.execute(SQL_CREATE_SHOPS_INDEX);
                stmt.execute(SQL_CREATE_SHELVES);
                stmt.execute(SQL_CREATE_SHELVES_INDEX);
                stmt.execute(SQL_CREATE_PURCHASE_LOG);
                stmt.execute(SQL_CREATE_PURCHASE_LOG_INDEX);
            }

            // 旧库迁移：若 shops 表缺少 notify_enabled 列则添加
            migrateShopsNotifyEnabled();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 旧库迁移：通过 PRAGMA table_info 检查 shops 表是否已有 notify_enabled 列，
     * 缺失则执行 ALTER TABLE ADD COLUMN（默认值 1，等价于已开启提示）。
     * 重复执行是安全的：列已存在时跳过。
     */
    private void migrateShopsNotifyEnabled() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("PRAGMA table_info(shops)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if ("notify_enabled".equalsIgnoreCase(rs.getString("name"))) {
                    return; // 列已存在，无需迁移
                }
            }
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(SQL_MIGRATE_SHOPS_NOTIFY);
            plugin.getLogger().info("已迁移 shops 表：新增 notify_enabled 列（默认 1=开启）");
        }
    }

    /**
     * 获取底层 Connection。调用方必须已持有 {@link #getLock()}。
     *
     * @return Connection 实例，未初始化时为 null
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * 获取并发锁。所有 DAO 操作必须在此锁保护下执行（SQLite 单连接）。
     *
     * @return ReentrantLock 实例
     */
    public ReentrantLock getLock() {
        return lock;
    }

    /**
     * 获取插件 logger，供 DAO 层记录错误日志。
     *
     * @return logger 实例
     */
    public Logger getLogger() {
        return plugin.getLogger();
    }

    /**
     * 关闭数据库连接。onDisable 时调用。
     */
    public void close() {
        lock.lock();
        try {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    plugin.getLogger().severe(() -> "Failed to close database connection: " + e.getMessage());
                }
                connection = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
