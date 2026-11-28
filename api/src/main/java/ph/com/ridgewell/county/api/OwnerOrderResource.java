package ph.com.ridgewell.county.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import ph.com.ridgewell.county.persistence.OrderActions;
import ph.com.ridgewell.county.persistence.Orders;

/** The owner's orders: list, detail and the actions. Tenant comes from the verified subject. */
@Path("/")
@Authenticated
@Produces("application/json")
@Tag(name = "Owner Resource")
public class OwnerOrderResource {
  @Inject io.quarkus.security.identity.SecurityIdentity identity;
  @jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uri;
  @Inject Orders orders;
  @Inject OrderActions actions;

  private String subject() {
    String s = identity.getPrincipal() instanceof JsonWebToken jwt ? jwt.getSubject() : null;
    if (s == null || s.isBlank()) throw new Problem(401, "Sign in required");
    return s;
  }

  @GET
  @Path("orders")
  public List<Views.OrderSummary> orders(
      @QueryParam("date") String date, @QueryParam("status") String status) {
    if (date == null && uri.getQueryParameters().containsKey("date"))
      throw Problem.field("date", "Enter a date such as 2026-11-05");
    return orders.list(subject(), date, status);
  }

  @GET
  @Path("orders/{id}")
  public Views.Order order(@PathParam("id") long id) {
    return orders.get(subject(), id);
  }

  @POST
  @Path("orders/{id}/confirm")
  public Views.Order confirm(@PathParam("id") long id) {
    return actions.confirm(subject(), id);
  }

  @POST
  @Path("orders/{id}/cancel")
  public Views.Order cancel(@PathParam("id") long id) {
    return actions.cancelByOwner(subject(), id);
  }

  @POST
  @Path("orders/{id}/deliver")
  public Views.Order deliver(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Deliver input) {
    return actions.deliver(subject(), id, input.paid());
  }

  @POST
  @Path("orders/{id}/mark-paid")
  public Views.Order paid(@PathParam("id") long id) {
    return actions.markPaid(subject(), id);
  }
}
