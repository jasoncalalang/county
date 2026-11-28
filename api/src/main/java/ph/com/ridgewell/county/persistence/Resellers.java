package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import ph.com.ridgewell.county.api.Inputs;
import ph.com.ridgewell.county.api.Problem;
import ph.com.ridgewell.county.api.Views;
import ph.com.ridgewell.county.domain.PhilippineMobile;

/**
 * The owner's resellers: list, create, update, and the block, unblock and reissue actions. The
 * tenant comes from the verified subject. Each change locks the reseller, which is the first lock
 * of every workflow (ADR 0013).
 */
@ApplicationScoped
public class Resellers {
  @ConfigProperty(name = "county.public-url")
  String publicUrl;

  @Inject ResellerLinks links;

  @Transactional
  public List<Views.Reseller> list(String subject) {
    long tenant = Owners.tenant(subject).id;
    Map<Long, Instant> lastOrders = new HashMap<>();
    for (Object[] row :
        SalesOrder.getEntityManager()
            .createQuery(
                "select o.resellerId, max(o.placedAt) from SalesOrder o"
                    + " where o.tenantId = ?1 group by o.resellerId",
                Object[].class)
            .setParameter(1, tenant)
            .getResultList()) lastOrders.put((Long) row[0], (Instant) row[1]);
    return Reseller.<Reseller>list("tenantId", Sort.by("id"), tenant).stream()
        .map(r -> view(r, lastOrders.get(r.id)))
        .toList();
  }

  @Transactional
  public Views.Reseller create(String subject, Inputs.Reseller input) {
    long tenant = Owners.tenant(subject).id;
    String mobile = mobile(input);
    var reseller = new Reseller();
    reseller.tenantId = tenant;
    links.issue(reseller);
    reseller.name = input.name().trim();
    reseller.mobile = mobile;
    reseller.persist();
    Reseller.flush();
    return view(reseller, null);
  }

  @Transactional
  public Views.Reseller update(String subject, long id, Inputs.Reseller input) {
    long tenant = Owners.tenant(subject).id;
    String mobile = mobile(input);
    var reseller = locked(tenant, id);
    reseller.name = input.name().trim();
    reseller.mobile = mobile;
    Reseller.flush();
    return view(reseller, null);
  }

  @Transactional
  public Views.Reseller block(String subject, long id) {
    var reseller = locked(Owners.tenant(subject).id, id);
    reseller.status = "blocked";
    Reseller.flush();
    return view(reseller, null);
  }

  /** Back to active if the reseller has ordered, else invited. The link is not touched. */
  @Transactional
  public Views.Reseller unblock(String subject, long id) {
    var reseller = locked(Owners.tenant(subject).id, id);
    reseller.status = reseller.hasOrdered ? "active" : "invited";
    Reseller.flush();
    return view(reseller, null);
  }

  /** A new token and a fresh 90 days. A blocked reseller stays blocked. */
  @Transactional
  public Views.Reseller reissueLink(String subject, long id) {
    var reseller = locked(Owners.tenant(subject).id, id);
    links.issue(reseller);
    Reseller.flush();
    return view(reseller, null);
  }

  private static Reseller locked(long tenant, long id) {
    return Reseller.inTenant(tenant, id, PESSIMISTIC_WRITE)
        .orElseThrow(() -> new Problem(404, "Not found"));
  }

  private static String mobile(Inputs.Reseller input) {
    try {
      return PhilippineMobile.normalize(input.mobile());
    } catch (IllegalArgumentException e) {
      throw Problem.field("mobile", e.getMessage());
    }
  }

  Views.Reseller view(Reseller r, Instant lastOrderAt) {
    return new Views.Reseller(
        r.id,
        r.name,
        r.mobile,
        r.status,
        r.token,
        publicUrl.replaceAll("/$", "") + "/order/" + r.token,
        Times.instant(r.tokenExpiresAt),
        Times.instant(lastOrderAt),
        r.priceTierId);
  }
}
