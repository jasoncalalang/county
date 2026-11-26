package ph.com.ridgewell.county.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import ph.com.ridgewell.county.persistence.*;

@Path("/")
@Authenticated
@Produces("application/json")
public class OwnerResource {
  @Inject io.quarkus.security.identity.SecurityIdentity identity;
  @Inject Owners owners;
  @jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uri;
  @Inject Orders orders;

  private String subject() {
    String s = identity.getPrincipal() instanceof JsonWebToken jwt ? jwt.getSubject() : null;
    if (s == null || s.isBlank()) throw new Problem(401, "Sign in required");
    return s;
  }

  @GET
  @Path("me")
  public Views.Me me() {
    String subject = subject();
    var jwt = (JsonWebToken) identity.getPrincipal();
    return owners.me(subject, jwt.getClaim("name"), jwt.getClaim("email"));
  }

  @POST
  @Path("tenants")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Business.class)))
  public Response create(@jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return Response.status(201)
        .entity(owners.create(subject(), input))
        .build();
  }

  @GET
  @Path("settings")
  public Views.Business settings() {
    return owners.settings(subject(), null);
  }

  @PUT
  @Path("settings")
  public Views.Business settings(
      @jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return owners.settings(subject(), input);
  }

  /**
   * The delivery date an order placed now would get with these settings, for the settings and
   * set-up forms. Takes unsaved values, so it needs a signed-in owner but no business.
   */
  @GET
  @Path("settings/preview")
  public Views.SettingsPreview preview(
      @QueryParam("cutoff") String cutoff, @QueryParam("deliveryDays") List<String> deliveryDays) {
    subject();
    return Owners.preview(cutoff, deliveryDays);
  }

  @GET
  @Path("products")
  public List<Views.Product> products() {
    return owners.products(subject());
  }

  @POST
  @Path("products")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Product.class)))
  public Response product(@jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return Response.status(201)
        .entity(owners.product(subject(), null, input))
        .build();
  }

  @PUT
  @Path("products/{id}")
  public Views.Product product(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return owners.product(subject(), id, input);
  }

  @GET
  @Path("resellers")
  public List<Views.Reseller> resellers() {
    return owners.resellers(subject());
  }

  @POST
  @Path("resellers")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Reseller.class)))
  public Response reseller(@jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return Response.status(201)
        .entity(owners.reseller(subject(), null, input))
        .build();
  }

  @PUT
  @Path("resellers/{id}")
  public Views.Reseller reseller(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return owners.reseller(subject(), id, input);
  }

  @POST
  @Path("resellers/{id}/{action:block|unblock|reissue-link}")
  public Views.Reseller resellerAction(
      @PathParam("id") long id, @PathParam("action") String action) {
    return owners.resellerAction(subject(), id, action);
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
    return orders.transition(subject(), null, id, "confirmed", false);
  }

  @POST
  @Path("orders/{id}/cancel")
  public Views.Order cancel(@PathParam("id") long id) {
    return orders.transition(subject(), null, id, "cancelled", false);
  }

  @POST
  @Path("orders/{id}/deliver")
  public Views.Order deliver(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Deliver input) {
    return orders.transition(subject(), null, id, "delivered", input.paid());
  }

  @POST
  @Path("orders/{id}/mark-paid")
  public Views.Order paid(@PathParam("id") long id) {
    return orders.transition(subject(), null, id, "paid", true);
  }

  @GET
  @Path("production-run")
  public Views.ProductionRun production(@QueryParam("date") String date) {
    return orders.production(subject(), date);
  }
}
