package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.NONE;
import static jakarta.persistence.LockModeType.PESSIMISTIC_READ;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.*;
import ph.com.ridgewell.county.api.*;

@ApplicationScoped
public class Owners {
  @Inject Resellers resellers;

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

  static Views.Business view(Business b) {
    return new Views.Business(
        b.id, b.name, Times.clock(b.cutoff), Arrays.asList(b.deliveryDays));
  }

  private static Views.Product view(Product p) {
    return new Views.Product(p.id, p.name, p.unit, p.price, p.active);
  }

  @Transactional
  public List<Views.PriceTier> priceTiers(String subject) {
    return PriceTier.<PriceTier>list(
            "tenantId", Sort.descending("active").and("id"), tenant(subject).id)
        .stream()
        .map(t -> new Views.PriceTier(t.id, t.name, t.active))
        .toList();
  }

  @Transactional
  public Views.PriceTier priceTier(String subject, Long id, Inputs.PriceTier input) {
    long tenant = tenant(subject).id;
    PriceTier tier;
    if (id == null) {
      tier = new PriceTier();
      tier.tenantId = tenant;
      tier.active = input.active() == null || input.active();
    } else {
      tier =
          PriceTier.inTenant(tenant, id, PESSIMISTIC_WRITE)
              .orElseThrow(() -> new Problem(404, "Not found"));
      if (input.active() != null) tier.active = input.active();
    }
    tier.name = input.name().trim();
    tier.persist();
    PriceTier.flush();
    return new Views.PriceTier(tier.id, tier.name, tier.active);
  }

  @Transactional
  public Views.TierPrices tierPrices(String subject, long tierId) {
    long tenant = tenant(subject).id;
    var tier =
        PriceTier.inTenant(tenant, tierId, NONE).orElseThrow(() -> new Problem(404, "Not found"));
    Map<Long, BigDecimal> overrides = new HashMap<>();
    for (ProductTierPrice p :
        ProductTierPrice.<ProductTierPrice>list(
            "tenantId = ?1 and priceTierId = ?2", tenant, tierId)) overrides.put(p.productId, p.price);
    var products =
        Product.<Product>list("tenantId", Sort.descending("active").and("id"), tenant).stream()
            .map(
                p ->
                    new Views.TierProductPrice(
                        p.id, p.name, p.unit, p.price, overrides.get(p.id)))
            .toList();
    return new Views.TierPrices(tier.id, tier.name, tier.active, products);
  }

  @Transactional
  public Views.TierPrices setTierPrice(
      String subject, long productId, long tierId, Inputs.TierPrice input) {
    long tenant = tenant(subject).id;
    // Exclusive product lock: a placement reading this tier price waits for the edit to commit.
    Product.inTenant(tenant, productId, PESSIMISTIC_WRITE)
        .orElseThrow(() -> new Problem(404, "Not found"));
    var tier =
        PriceTier.inTenant(tenant, tierId, NONE).orElseThrow(() -> new Problem(404, "Not found"));
    BigDecimal price = input.price().setScale(2, RoundingMode.UNNECESSARY);
    var row = ProductTierPrice.of(tenant, productId, tierId).orElseGet(ProductTierPrice::new);
    row.tenantId = tenant;
    row.productId = productId;
    row.priceTierId = tierId;
    row.price = price;
    row.persist();
    ProductTierPrice.flush();
    return tierPrices(subject, tier.id);
  }

  @Transactional
  public void clearTierPrice(String subject, long productId, long tierId) {
    long tenant = tenant(subject).id;
    Product.inTenant(tenant, productId, PESSIMISTIC_WRITE)
        .orElseThrow(() -> new Problem(404, "Not found"));
    PriceTier.inTenant(tenant, tierId, NONE).orElseThrow(() -> new Problem(404, "Not found"));
    ProductTierPrice.delete(
        "tenantId = ?1 and productId = ?2 and priceTierId = ?3", tenant, productId, tierId);
  }

  @Transactional
  public Views.Reseller assignResellerTier(String subject, long id, Inputs.ResellerTier input) {
    long tenant = tenant(subject).id;
    var reseller =
        Reseller.inTenant(tenant, id, PESSIMISTIC_WRITE)
            .orElseThrow(() -> new Problem(404, "Not found"));
    if (input.priceTierId() == null) {
      reseller.priceTierId = null;
    } else {
      // Read-locked after the reseller (reseller -> tier), so a concurrent deactivation, which
      // holds the tier's write lock, finishes first and the active check sees its result.
      var tier =
          PriceTier.inTenant(tenant, input.priceTierId(), PESSIMISTIC_READ)
              .orElseThrow(() -> new Problem(404, "Not found"));
      if (!tier.active) throw Problem.field("priceTierId", "Price tier is not active");
      reseller.priceTierId = tier.id;
    }
    Reseller.flush();
    return resellers.view(reseller, null);
  }
}
