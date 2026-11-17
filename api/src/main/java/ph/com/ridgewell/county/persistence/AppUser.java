package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/** A person who signs in. Profile fields are descriptive only, never a linking key. */
@Entity
@Table(name = "app_user")
public class AppUser extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "display_name")
  public String displayName;

  public String email;
}
