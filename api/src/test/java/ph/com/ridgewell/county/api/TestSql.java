package ph.com.ridgewell.county.api;

import java.sql.*;
import java.util.*;

/** Plain JDBC for test fixtures and migration checks. The application uses Panache. */
public final class TestSql {
  private TestSql() {}

  public static List<Map<String, Object>> rows(Connection c, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (ResultSet r = s.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        var meta = r.getMetaData();
        while (r.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnLabel(i), r.getObject(i));
          rows.add(row);
        }
        return rows;
      }
    }
  }

  public static Map<String, Object> one(Connection c, String sql, Object... args)
      throws SQLException {
    var rows = rows(c, sql, args);
    if (rows.size() != 1) throw new AssertionError("Expected one row, got " + rows.size());
    return rows.getFirst();
  }

  public static int execute(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      return s.executeUpdate();
    }
  }

  public static long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }
}
