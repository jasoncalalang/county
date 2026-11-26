package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import ph.com.ridgewell.county.api.*;
import ph.com.ridgewell.county.domain.PhilippineMobile;

@ApplicationScoped
public class Owners {
  @ConfigProperty(name = "county.public-url")
  String publicUrl;

  private static final SecureRandom RANDOM = new SecureRandom();

  /** The business owned by the signed-in Google subject, or 404. */
  static Business tenant(String subject) {
    var identity = ExternalIdentity.google(subject);
    var business = identity == null ? null : Business.ownedBy(identity.userId);
    if (business == null) throw new Problem(404, "Not found");
    return business;
  }

  // Authentication has already verified the Google token. Profile fields are
  // descriptive only: email is never an account-linking or authorization key.
  private static AppUser user(String subject, String name, String email) {
    if (subject == null || subject.isBlank()) throw new Problem(401, "Sign in required");
    // Serialize first-sign-in provisioning for this identity. Hash collisions merely
    // serialize unrelated identities; the composite primary key is authoritative.
    AppUser.getEntityManager()
        .createNativeQuery("SELECT count(*) FROM pg_advisory_xact_lock(hashtextextended(?1,0))")
        .setParameter(1, ExternalIdentity.GOOGLE + ":" + subject)
        .getSingleResult();
    var identity = ExternalIdentity.google(subject);
    if (identity == null) {
      var created = new AppUser();
      created.displayName = name;
      created.email = email;
      created.persist();
      identity = new ExternalIdentity();
      identity.provider = ExternalIdentity.GOOGLE;
      identity.subject = subject;
      identity.userId = created.id;
      identity.persist();
      return created;
    }
    AppUser existing = AppUser.findById(identity.userId);
    if (name != null) existing.displayName = name;
    if (email != null) existing.email = email;
    return existing;
  }

  @Transactional
  public Views.Me me(String subject) {
    return me(subject, null, null);
  }

  @Transactional
  public Views.Me me(String subject, String displayName, String email) {
    var user = user(subject, displayName, email);
    var business = Business.ownedBy(user.id);
    return new Views.Me(
        subject,
        new Views.User(user.id, user.displayName, user.email),
        business == null ? null : view(business));
  }

  @Transactional
  public Views.Business create(String subject, Inputs.Business input) {
    var business = new Business();
    business.ownerUserId = user(subject, null, null).id;
    apply(business, input);
    business.persist();
    return view(business);
  }

  @Transactional
  public Views.Business settings(String subject, Inputs.Business input) {
    var business = tenant(subject);
    if (input != null) {
      apply(business, input);
      Business.flush();
    }
    return view(business);
  }

  private static void apply(Business business, Inputs.Business input) {
    business.name = input.name().trim();
    business.cutoff = cutoff(input.cutoff());
    business.deliveryDays = input.deliveryDays().toArray(Integer[]::new);
  }

  /** A cut-off time such as {@code 16:00}, or a field error. */
  static LocalTime cutoff(String value) {
    try {
      return LocalTime.parse(value);
    } catch (java.time.DateTimeException | NullPointerException e) {
      throw Problem.field("cutoff", "Enter a time such as 16:00");
    }
  }

  /** What the settings page shows for unsaved values: no business is required. */
  public static Views.SettingsPreview preview(String cutoff, List<String> deliveryDays) {
    LocalTime time = cutoff(cutoff);
    Set<Integer> days = new HashSet<>();
    for (String day : deliveryDays == null ? List.<String>of() : deliveryDays) {
      try {
        int value = Integer.parseInt(day.trim());
        if (value < 1 || value > 7) throw new NumberFormatException();
        days.add(value);
      } catch (NumberFormatException e) {
        throw Problem.field("deliveryDays", "Use days 1 (Monday) to 7 (Sunday)");
      }
    }
    if (days.isEmpty()) throw Problem.field("deliveryDays", "Choose at least one delivery day");
    return new Views.SettingsPreview(
        Orders.delivery(time, days.toArray(Integer[]::new)).toString(), Orders.ZONE.getId());
  }

  @Transactional
  public List<Views.Product> products(String subject) {
    return Product.<Product>list(
            "tenantId", Sort.descending("active").and("id"), tenant(subject).id)
        .stream()
        .map(Owners::view)
        .toList();
  }

  @Transactional
  public Views.Product product(String subject, Long id, Inputs.Product input) {
    long tenant = tenant(subject).id;
    Product product;
    if (id == null) {
      product = new Product();
      product.tenantId = tenant;
      product.active = input.active() == null || input.active();
    } else {
      // Exclusive lock: a placement reading this product waits for the edit to commit.
      product =
          Product.inTenant(tenant, id, PESSIMISTIC_WRITE)
              .orElseThrow(() -> new Problem(404, "Not found"));
      if (!product.unit.equals(input.unit())
          && OrderLine.count("tenantId = ?1 and productId = ?2", tenant, id) > 0)
        throw new Problem(409, "Unit cannot change after ordering");
      if (input.active() != null) product.active = input.active();
    }
    product.name = input.name().trim();
    product.unit = input.unit();
    product.price = input.price().setScale(2, RoundingMode.UNNECESSARY);
    product.persist();
    Product.flush();
    return view(product);
  }

  @Transactional
  public List<Views.Reseller> resellers(String subject) {
    long tenant = tenant(subject).id;
    Map<Long, Object> lastOrders = new HashMap<>();
    for (Object[] row :
        PurchaseOrder.getEntityManager()
            .createQuery(
                "select o.resellerId, max(o.placedAt) from PurchaseOrder o"
                    + " where o.tenantId = ?1 group by o.resellerId",
                Object[].class)
            .setParameter(1, tenant)
            .getResultList()) lastOrders.put((Long) row[0], row[1]);
    return Reseller.<Reseller>list("tenantId", Sort.by("id"), tenant).stream()
        .map(r -> view(r, lastOrders.get(r.id)))
        .toList();
  }

  @Transactional
  public Views.Reseller reseller(String subject, Long id, Inputs.Reseller input) {
    long tenant = tenant(subject).id;
    String mobile;
    try {
      mobile = PhilippineMobile.normalize(input.mobile());
    } catch (IllegalArgumentException e) {
      throw Problem.field("mobile", e.getMessage());
    }
    Reseller reseller;
    if (id == null) {
      reseller = new Reseller();
      reseller.tenantId = tenant;
      reseller.token = token();
    } else
      reseller =
          Reseller.inTenant(tenant, id, PESSIMISTIC_WRITE)
              .orElseThrow(() -> new Problem(404, "Not found"));
    reseller.name = input.name().trim();
    reseller.mobile = mobile;
    reseller.persist();
    Reseller.flush();
    return view(reseller, null);
  }

  @Transactional
  public Views.Reseller resellerAction(String subject, long id, String action) {
    var reseller =
        Reseller.inTenant(tenant(subject).id, id, PESSIMISTIC_WRITE)
            .orElseThrow(() -> new Problem(404, "Not found"));
    switch (action) {
      case "block" -> reseller.status = "blocked";
      case "unblock" -> reseller.status = reseller.hasOrdered ? "active" : "invited";
      case "reissue-link" -> reseller.token = token();
      default -> throw new Problem(404, "Not found");
    }
    Reseller.flush();
    return view(reseller, null);
  }

  static Views.Business view(Business b) {
    return new Views.Business(
        b.id, b.name, Times.clock(b.cutoff), Arrays.asList(b.deliveryDays));
  }

  private static Views.Product view(Product p) {
    return new Views.Product(p.id, p.name, p.unit, p.price, p.active);
  }

  private Views.Reseller view(Reseller r, Object lastOrderAt) {
    return new Views.Reseller(
        r.id,
        r.name,
        r.mobile,
        r.status,
        r.token,
        publicUrl.replaceAll("/$", "") + "/order/" + r.token,
        lastOrderAt == null ? null : Times.instant((java.time.Instant) lastOrderAt));
  }

  private static String token() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
