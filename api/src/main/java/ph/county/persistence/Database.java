package ph.county.persistence;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import ph.county.api.Problem;

@ApplicationScoped
public class Database {
  @Inject AgroalDataSource source;

  @FunctionalInterface
  public interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  public <T> T transaction(Work<T> work) {
    try (Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      if ("23505".equals(e.getSQLState()))
        throw new Problem(409, "A record with those details already exists");
      throw new IllegalStateException("Database operation failed", e);
    }
  }

  public static List<Map<String, Object>> rows(Connection c, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        var meta = r.getMetaData();
        while (r.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object value = r.getObject(i);
            if (value instanceof Timestamp t) value = t.toInstant().toString();
            else if (value instanceof java.sql.Date || value instanceof Time)
              value = value.toString();
            else if (value instanceof java.sql.Array a)
              value = Arrays.asList((Object[]) a.getArray());
            row.put(camel(meta.getColumnLabel(i)), value);
          }
          rows.add(row);
        }
        return rows;
      }
    }
  }

  public static Map<String, Object> one(Connection c, String sql, Object... args)
      throws SQLException {
    var rows = rows(c, sql, args);
    if (rows.isEmpty()) throw new Problem(404, "Not found");
    return rows.getFirst();
  }

  public static int execute(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      return s.executeUpdate();
    }
  }

  private static void bind(PreparedStatement s, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
  }

  public static long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  private static String camel(String snake) {
    StringBuilder out = new StringBuilder();
    boolean cap = false;
    for (char c : snake.toCharArray()) {
      if (c == '_') cap = true;
      else {
        out.append(cap ? Character.toUpperCase(c) : c);
        cap = false;
      }
    }
    return out.toString();
  }
}
