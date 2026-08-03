package com.oolongho.woosimmarket.database;

import com.oolongho.woosimmarket.database.DatabaseManager.ShelfRecord;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shelf 表的数据访问对象。
 *
 * <p>所有方法均通过 {@link DatabaseManager#getLock()} 串行化，确保 SQLite 单连接线程安全。
 * 入参 null 安全：null 入参返回 false/空集合，并跳过 SQL 执行；SQL 异常与运行时异常（如
 * 连接未初始化）被捕获并记录日志后返回失败结果。</p>
 *
 * <p>shops 表设有 ON DELETE CASCADE 外键约束，删除 Shop 时关联 Shelves 会被数据库自动清除，
 * {@link #deleteByShopId(String)} 仅在需要显式批量清理（不删除 Shop）时使用。</p>
 *
 * @author oolongho
 */
public class ShelfDao {

    private static final String SQL_INSERT = """
            INSERT INTO shelves (id, shop_id, world, x, y, z, facing, item_stack, price, stock, max_stock, enabled, item_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SQL_UPDATE = """
            UPDATE shelves
            SET shop_id=?, world=?, x=?, y=?, z=?, facing=?, item_stack=?, price=?, stock=?, max_stock=?, enabled=?, item_id=?
            WHERE id=?""";

    private static final String SQL_DELETE = "DELETE FROM shelves WHERE id=?";
    private static final String SQL_DELETE_BY_SHOP = "DELETE FROM shelves WHERE shop_id=?";

    private static final String SQL_FIND_BY_SHOP = """
            SELECT id, shop_id, world, x, y, z, facing, item_stack, price, stock, max_stock, enabled, item_id
            FROM shelves WHERE shop_id=?""";

    private static final String SQL_LOAD_ALL = """
            SELECT id, shop_id, world, x, y, z, facing, item_stack, price, stock, max_stock, enabled, item_id
            FROM shelves""";

    private final DatabaseManager db;

    public ShelfDao(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 插入货架记录。
     *
     * @param record 货架数据，null 时返回 false
     * @return true 表示成功插入一行
     */
    public boolean insert(ShelfRecord record) {
        if (record == null) {
            return false;
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_INSERT)) {
            bindInsert(ps, record);
            return ps.executeUpdate() > 0;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to insert shelf " + record.id() + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 更新货架记录（按 id 匹配）。
     *
     * @param record 货架数据，null 时返回 false
     * @return true 表示成功更新一行
     */
    public boolean update(ShelfRecord record) {
        if (record == null) {
            return false;
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_UPDATE)) {
            bindUpdate(ps, record);
            return ps.executeUpdate() > 0;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to update shelf " + record.id() + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按 id 删除货架记录。
     *
     * @param id 货架 id，null 时返回 false
     * @return true 表示成功删除一行
     */
    public boolean delete(String id) {
        if (id == null) {
            return false;
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_DELETE)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to delete shelf " + id + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按 shopId 批量删除货架（不删除 Shop 本身）。
     *
     * @param shopId 商店 id，null 时返回 false
     * @return true 表示成功删除至少一行
     */
    public boolean deleteByShopId(String shopId) {
        if (shopId == null) {
            return false;
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_DELETE_BY_SHOP)) {
            ps.setString(1, shopId);
            return ps.executeUpdate() > 0;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to delete shelves by shop " + shopId + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按 shopId 查询其所有货架。
     *
     * @param shopId 商店 id，null 时返回空列表
     * @return 货架记录列表，失败时返回空列表
     */
    public List<ShelfRecord> findByShopId(String shopId) {
        if (shopId == null) {
            return List.of();
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_FIND_BY_SHOP)) {
            ps.setString(1, shopId);
            return mapList(ps);
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to find shelves by shop " + shopId + ": " + e.getMessage());
            return List.of();
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 加载全部货架记录（重启恢复用）。
     *
     * @return 货架记录列表，失败时返回空列表
     */
    public List<ShelfRecord> loadAll() {
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_LOAD_ALL)) {
            return mapList(ps);
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to load all shelves: " + e.getMessage());
            return List.of();
        } finally {
            db.getLock().unlock();
        }
    }

    private static void bindInsert(PreparedStatement ps, ShelfRecord r) throws SQLException {
        ps.setString(1, r.id());
        ps.setString(2, r.shopId());
        ps.setString(3, r.world());
        ps.setInt(4, r.x());
        ps.setInt(5, r.y());
        ps.setInt(6, r.z());
        ps.setString(7, r.facing());
        ps.setString(8, r.itemStackBase64());
        ps.setDouble(9, r.price());
        ps.setInt(10, r.stock());
        ps.setInt(11, r.maxStock());
        ps.setBoolean(12, r.enabled());
        ps.setString(13, r.itemId());
    }

    private static void bindUpdate(PreparedStatement ps, ShelfRecord r) throws SQLException {
        ps.setString(1, r.shopId());
        ps.setString(2, r.world());
        ps.setInt(3, r.x());
        ps.setInt(4, r.y());
        ps.setInt(5, r.z());
        ps.setString(6, r.facing());
        ps.setString(7, r.itemStackBase64());
        ps.setDouble(8, r.price());
        ps.setInt(9, r.stock());
        ps.setInt(10, r.maxStock());
        ps.setBoolean(11, r.enabled());
        ps.setString(12, r.itemId());
        ps.setString(13, r.id());
    }

    private static ShelfRecord map(ResultSet rs) throws SQLException {
        return new ShelfRecord(
                rs.getString("id"),
                rs.getString("shop_id"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("facing"),
                rs.getString("item_stack"),
                rs.getDouble("price"),
                rs.getInt("stock"),
                rs.getInt("max_stock"),
                rs.getBoolean("enabled"),
                rs.getString("item_id")
        );
    }

    private static List<ShelfRecord> mapList(PreparedStatement ps) throws SQLException {
        List<ShelfRecord> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }
}
