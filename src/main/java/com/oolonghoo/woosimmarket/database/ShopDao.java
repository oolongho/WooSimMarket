package com.oolonghoo.woosimmarket.database;

import com.oolonghoo.woosimmarket.database.DatabaseManager.ShopRecord;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shop 表的数据访问对象。
 *
 * <p>所有方法均通过 {@link DatabaseManager#getLock()} 串行化，确保 SQLite 单连接线程安全。
 * 入参 null 安全：null 入参返回 false/空集合，并跳过 SQL 执行；SQL 异常与运行时异常（如
 * UUID 解析失败、连接未初始化）被捕获并记录日志后返回失败结果，不让异常向上传播。</p>
 *
 * @author oolongho
 */
public class ShopDao {

    private static final String SQL_INSERT = """
            INSERT INTO shops (id, owner_uuid, world, x, y, z, facing, balance, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SQL_UPDATE = """
            UPDATE shops
            SET owner_uuid=?, world=?, x=?, y=?, z=?, facing=?, balance=?, created_at=?
            WHERE id=?""";

    private static final String SQL_DELETE = "DELETE FROM shops WHERE id=?";

    private static final String SQL_FIND_BY_ID = """
            SELECT id, owner_uuid, world, x, y, z, facing, balance, created_at
            FROM shops WHERE id=?""";

    private static final String SQL_FIND_BY_OWNER = """
            SELECT id, owner_uuid, world, x, y, z, facing, balance, created_at
            FROM shops WHERE owner_uuid=?""";

    private static final String SQL_LOAD_ALL = """
            SELECT id, owner_uuid, world, x, y, z, facing, balance, created_at
            FROM shops""";

    private final DatabaseManager db;

    public ShopDao(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 插入商店记录。
     *
     * @param record 商店数据，null 时返回 false
     * @return true 表示成功插入一行
     */
    public boolean insert(ShopRecord record) {
        if (record == null) {
            return false;
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_INSERT)) {
            bindInsert(ps, record);
            return ps.executeUpdate() > 0;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to insert shop " + record.id() + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 更新商店记录（按 id 匹配）。
     *
     * @param record 商店数据，null 时返回 false
     * @return true 表示成功更新一行
     */
    public boolean update(ShopRecord record) {
        if (record == null) {
            return false;
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_UPDATE)) {
            bindUpdate(ps, record);
            return ps.executeUpdate() > 0;
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to update shop " + record.id() + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按 id 删除商店记录。外键级联会同时删除关联 shelves。
     *
     * @param id 商店 id，null 时返回 false
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
            db.getLogger().severe(() -> "Failed to delete shop " + id + ": " + e.getMessage());
            return false;
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按 id 查询商店。
     *
     * @param id 商店 id，null 时返回 empty
     * @return Optional 包装的商店记录
     */
    public Optional<ShopRecord> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_FIND_BY_ID)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to find shop " + id + ": " + e.getMessage());
            return Optional.empty();
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 按拥有者 UUID 查询其所有商店。
     *
     * @param owner 拥有者 UUID，null 时返回空列表
     * @return 商店记录列表，失败时返回空列表
     */
    public List<ShopRecord> findByOwner(UUID owner) {
        if (owner == null) {
            return List.of();
        }
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_FIND_BY_OWNER)) {
            ps.setString(1, owner.toString());
            return mapList(ps);
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to find shops by owner " + owner + ": " + e.getMessage());
            return List.of();
        } finally {
            db.getLock().unlock();
        }
    }

    /**
     * 加载全部商店记录（重启恢复用）。
     *
     * @return 商店记录列表，失败时返回空列表
     */
    public List<ShopRecord> loadAll() {
        db.getLock().lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(SQL_LOAD_ALL)) {
            return mapList(ps);
        } catch (SQLException | RuntimeException e) {
            db.getLogger().severe(() -> "Failed to load all shops: " + e.getMessage());
            return List.of();
        } finally {
            db.getLock().unlock();
        }
    }

    private static void bindInsert(PreparedStatement ps, ShopRecord r) throws SQLException {
        ps.setString(1, r.id());
        ps.setString(2, r.ownerUuid().toString());
        ps.setString(3, r.world());
        ps.setInt(4, r.x());
        ps.setInt(5, r.y());
        ps.setInt(6, r.z());
        ps.setString(7, r.facing());
        ps.setDouble(8, r.balance());
        ps.setLong(9, r.createdAt());
    }

    private static void bindUpdate(PreparedStatement ps, ShopRecord r) throws SQLException {
        ps.setString(1, r.ownerUuid().toString());
        ps.setString(2, r.world());
        ps.setInt(3, r.x());
        ps.setInt(4, r.y());
        ps.setInt(5, r.z());
        ps.setString(6, r.facing());
        ps.setDouble(7, r.balance());
        ps.setLong(8, r.createdAt());
        ps.setString(9, r.id());
    }

    private static ShopRecord map(ResultSet rs) throws SQLException {
        return new ShopRecord(
                rs.getString("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("facing"),
                rs.getDouble("balance"),
                rs.getLong("created_at")
        );
    }

    private static List<ShopRecord> mapList(PreparedStatement ps) throws SQLException {
        List<ShopRecord> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }
}
