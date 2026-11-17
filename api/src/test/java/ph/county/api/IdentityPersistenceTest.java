package ph.county.api;

import static org.junit.jupiter.api.Assertions.*;
import static ph.county.persistence.Database.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import ph.county.persistence.Owners;

@QuarkusTest
class IdentityPersistenceTest {
  @Inject AgroalDataSource source;
  @Inject Owners owners;

  private long userId(Map<String, Object> me) {
    return ((Number) ((Map<?, ?>) me.get("user")).get("id")).longValue();
  }

  @Test
  void equalEmailsNeverLinkDifferentSubjectsAndProviderNamespaceIsSeparate() throws Exception {
    String subject = UUID.randomUUID().toString();
    long other;
    try (var c = source.getConnection()) {
      other =
          id(one(c, "INSERT INTO app_user(email) VALUES (?) RETURNING id", "shared@example.test"));
      execute(
          c,
          "INSERT INTO external_identity(provider,subject,user_id) VALUES ('other-provider',?,?)",
          subject,
          other);
    }
    long google = userId(owners.me(subject, "Google Owner", "shared@example.test"));
    long second =
        userId(owners.me(UUID.randomUUID().toString(), "Second Owner", "shared@example.test"));
    assertNotEquals(other, google);
    assertNotEquals(google, second);
    assertEquals(google, userId(owners.me(subject, "Renamed", "new@example.test")));
    assertThrows(Problem.class, () -> owners.settings(subject, null));
  }

  @Test
  void concurrentFirstSignInCreatesExactlyOneUserAndIdentity() throws Exception {
    String subject = UUID.randomUUID().toString();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(4)) {
      List<Future<Long>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return userId(owners.me(subject));
                }));
      start.countDown();
      Set<Long> ids = new HashSet<>();
      for (var f : futures) ids.add(f.get(10, TimeUnit.SECONDS));
      assertEquals(1, ids.size());
      try (var c = source.getConnection()) {
        assertEquals(
            1L,
            one(
                    c,
                    "SELECT count(*) AS count FROM external_identity WHERE provider='google' AND"
                        + " subject=?",
                    subject)
                .get("count"));
      }
    }
  }

  @Test
  void migrationPreservesExistingBusinessesAndCreatesUsableUserSequence() throws Exception {
    try (var c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        execute(c, "CREATE SCHEMA " + schema);
        execute(c, "SET LOCAL search_path TO " + schema);
        for (String file : List.of("V1__ordering.sql", "V2__owner_identities.sql")) {
          if (file.startsWith("V2")) {
            execute(
                c,
                "INSERT INTO business(id,owner_subject,name,cutoff,delivery_days) VALUES"
                    + " (41,'legacy-owner','Legacy','16:00',ARRAY[1])");
          }
          try (var in = getClass().getResourceAsStream("/db/migration/" + file);
              var s = c.createStatement()) {
            assertNotNull(in);
            s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
          }
        }
        var tenant = Owners.tenant(c, "legacy-owner");
        assertEquals(41L, id(tenant));
        assertEquals("Legacy", tenant.get("name"));
        assertEquals(41L, tenant.get("ownerUserId"));
        assertTrue(id(one(c, "INSERT INTO app_user DEFAULT VALUES RETURNING id")) > 41);
      } finally {
        c.rollback();
      }
    }
  }
}
