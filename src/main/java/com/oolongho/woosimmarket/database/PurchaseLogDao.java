package com.oolongho.woosimmarket.database;

import com.oolongho.woosimmarket.database.DatabaseManager.PurchaseLogRecord;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * purchase_log 表的数据访问对象。
 *
 * <p>所有方法均通过 {@link DatabaseManager#getLock()} 串行化，确保 SQLite 单连接线程安全。
 * 入参 null 安全：null 入参返回 false/空集合，并跳过 SQL 执行；SQL 异常与运行时异常（如
 * 连接未初始化）被捕获并记录日志后返回失败结果，不让异常向上传播。</p>
 *
 * <p>线程模型：本层同步执行，业务上层应通过 {@code TaskUtil} 将数据库 IO 投递到异步线程。</p>
 *
 * @author oolongho
 */
public class PurchaseLogDao {

    private static final String SQL_INSERT = """
            INSERT INTO purchase_log (shop_id, item_id, price, bought, personality, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)""";

    private static final String SQL_FIND_RECENT_BY_SHOP_SINCE = """
            SELECT id, shop_id, item_id, price, bought, personality, timestamp
            FROM purchase_log WHERE shop_id=? AND timestamp>=? ORDER BY id DESC LIMIT ?""";

    private static final String SQL_DELETE_OLDER_THAN =
            "DELETE FROM purchase_log WHERE timestamp<?";

    private static final String SQL_FIND_RECENT_BY_ITEM = """
            SELECT id, shop_id, item_id, price, bought, personality, timestamp
            FROM purchase_log WHERE item_id=? AND timestamp>=? ORDER BY id""";

    private final DatabaseManager db;

    public PurchaseLogDao(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 插入一条购买日志。id 由数据库自增，忽略 record 中的 id 字段。
     *
     * @param record 日志数据，null 时返回 false
     * @return true 表示成功插入一行
     */
    public boolean insert(PurchaseLogRecord record) {
        if (record == null) {
            return false;
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_INSERT)) {
            ps.setString(1, record.shopId());
            ps.setString(2, record.itemId());
            ps.setDouble(3, record.price());
            ps.setInt(4, record.bought() ? 1 : 0);
            ps.setString(5, record.personality());
            ps.setLong(6, record.timestamp());
            return ps.executeUpdate() > 0;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to insert purchase log for shop " + record.shopId() + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按商店 id 查询自指定时间起的购买日志（timestamp>=since，id 倒序，LIMIT 保护）。
     *
     * <p>供统计面板查询真正"近 N 天"记录使用：调用方传入 {@code now - retentionDays×86400000}
     * 作为 since，{@code queryLimit} 作为 LIMIT 上限防止极端数据量。</p>
     *
     * @param shopId     商店 id，null 时返回空列表
     * @param sinceMillis 时间戳下限（毫秒），仅返回 {@code timestamp >= 此值} 的记录
     * @param limit      最大返回条数，{@code <=0} 时返回空列表
     * @return 日志记录列表，失败时返回空列表
     */
    public List<PurchaseLogRecord> findRecentByShopSince(String shopId, long sinceMillis, int limit) {
        if (shopId == null || limit <= 0) {
            return List.of();
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_FIND_RECENT_BY_SHOP_SINCE)) {
            ps.setString(1, shopId);
            ps.setLong(2, sinceMillis);
            ps.setInt(3, limit);
            List<PurchaseLogRecord> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
            return list;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to find recent purchase logs for shop " + shopId
                    + " since " + sinceMillis + ": " + e.getMessage());
            return List.of();
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按物品 ID 查询近 N 天的所有判定记录（含 bought=0/1），供漂移计算使用。
     *
     * <p>SQL 走 item_id 过滤 + timestamp 下限，由 {@code idx_purchase_log_item_id}
     * 复合索引优化（覆盖 WHERE item_id=? AND timestamp>=? 查询）。</p>
     *
     * @param itemId 物品 ID，null 时返回空列表
     * @param days   查询天数，{@code <=0} 时返回空列表
     * @return 日志记录列表，失败时返回空列表
     */
    public List<PurchaseLogRecord> findRecentByItem(String itemId, int days) {
        if (itemId == null || days <= 0) {
            return List.of();
        }
        long since = System.currentTimeMillis() - days * 86400000L;
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_FIND_RECENT_BY_ITEM)) {
            ps.setString(1, itemId);
            ps.setLong(2, since);
            List<PurchaseLogRecord> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
            return list;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to find recent purchase logs for item " + itemId + ": " + e.getMessage());
            return List.of();
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 删除时间戳早于指定时刻的所有日志记录（清理任务调用）。
     *
     * @param timestampMillis 时间戳下限（毫秒），删除 {@code timestamp < 此值} 的记录
     * @return 删除的行数，失败时返回 0
     */
    public int deleteOlderThan(long timestampMillis) {
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_DELETE_OLDER_THAN)) {
            ps.setLong(1, timestampMillis);
            return ps.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to delete purchase logs older than " + timestampMillis + ": " + e.getMessage());
            return 0;
        } finally {
            db.getLock().unlock();
        }
    }

    private static PurchaseLogRecord map(ResultSet rs) throws SQLException {
        return new PurchaseLogRecord(
                rs.getLong("id"),
                rs.getString("shop_id"),
                rs.getString("item_id"),
                rs.getDouble("price"),
                rs.getInt("bought") == 1,
                rs.getString("personality"),
                rs.getLong("timestamp")
        );
    }
}
