package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/** A verified (provider, subject) pair mapped to one user (ADR 0002). */
@Entity
@Table(name = "external_identity")
@IdClass(ExternalIdentity.Key.class)
public class ExternalIdentity extends PanacheEntityBase {
  public static final String GOOGLE = "google";

  @Id public String provider;
  @Id public String subject;

  @Column(name = "user_id")
  public long userId;

  public static ExternalIdentity google(String subject) {
    return findById(new Key(GOOGLE, subject));
  }

  public static class Key implements Serializable {
    public String provider;
    public String subject;

    public Key() {}

    public Key(String provider, String subject) {
      this.provider = provider;
      this.subject = subject;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && provider.equals(k.provider) && subject.equals(k.subject);
    }

    @Override
    public int hashCode() {
      return Objects.hash(provider, subject);
    }
  }
}
