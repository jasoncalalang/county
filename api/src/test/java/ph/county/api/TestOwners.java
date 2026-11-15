package ph.county.api;

import java.sql.Connection;
import java.sql.SQLException;

public final class TestOwners {
  private TestOwners() {}

  public static long createBusiness(Connection c, String subject) throws SQLException {
    long user;
    try (var s = c.prepareStatement("INSERT INTO app_user DEFAULT VALUES RETURNING id");
        var r = s.executeQuery()) {
      r.next();
      user = r.getLong(1);
    }
    try (var s =
        c.prepareStatement(
            "INSERT INTO external_identity(provider,subject,user_id) VALUES ('google',?,?)")) {
      s.setString(1, subject);
      s.setLong(2, user);
      s.executeUpdate();
    }
    try (var s =
        c.prepareStatement(
            "INSERT INTO business(owner_user_id,name,cutoff,delivery_days) VALUES (?, 'Fixture"
                + " business', '16:00', ARRAY[1,2,3,4,5,6,7]) RETURNING id")) {
      s.setLong(1, user);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }
}
