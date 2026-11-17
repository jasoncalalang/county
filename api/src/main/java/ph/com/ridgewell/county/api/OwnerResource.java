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
  @Inject Views views;
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
    return views.as(
        owners.me(subject, jwt.getClaim("name"), jwt.getClaim("email")), Views.Me.class);
  }

  @POST
  @Path("tenants")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Business.class)))
  public Response create(@jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return Response.status(201)
        .entity(views.as(owners.create(subject(), input), Views.Business.class))
        .build();
  }

  @GET
  @Path("settings")
  public Views.Business settings() {
    return views.as(owners.settings(subject(), null), Views.Business.class);
  }

  @PUT
  @Path("settings")
  public Views.Business settings(
      @jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return views.as(owners.settings(subject(), input), Views.Business.class);
  }

  @GET
  @Path("products")
  public List<Views.Product> products() {
    return views.list(owners.products(subject()), Views.Product.class);
  }

  @POST
  @Path("products")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Product.class)))
  public Response product(@jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return Response.status(201)
        .entity(views.as(owners.product(subject(), null, input), Views.Product.class))
        .build();
  }

  @PUT
  @Path("products/{id}")
  public Views.Product product(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return views.as(owners.product(subject(), id, input), Views.Product.class);
  }

  @GET
  @Path("resellers")
  public List<Views.Reseller> resellers() {
    return views.list(owners.resellers(subject()), Views.Reseller.class);
  }

  @POST
  @Path("resellers")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Reseller.class)))
  public Response reseller(@jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return Response.status(201)
        .entity(views.as(owners.reseller(subject(), null, input), Views.Reseller.class))
        .build();
  }

  @PUT
  @Path("resellers/{id}")
  public Views.Reseller reseller(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return views.as(owners.reseller(subject(), id, input), Views.Reseller.class);
  }

  @POST
  @Path("resellers/{id}/{action:block|unblock|reissue-link}")
  public Views.Reseller resellerAction(
      @PathParam("id") long id, @PathParam("action") String action) {
    return views.as(owners.resellerAction(subject(), id, action), Views.Reseller.class);
  }

  @GET
  @Path("orders")
  public List<Views.OrderSummary> orders(
      @QueryParam("date") String date, @QueryParam("status") String status) {
    if (date == null && uri.getQueryParameters().containsKey("date"))
      throw new Problem(422, "Invalid delivery date");
    return views.list(orders.list(subject(), date, status), Views.OrderSummary.class);
  }

  @GET
  @Path("orders/{id}")
  public Views.Order order(@PathParam("id") long id) {
    return views.as(orders.get(subject(), id), Views.Order.class);
  }

  @POST
  @Path("orders/{id}/confirm")
  public Views.Order confirm(@PathParam("id") long id) {
    return views.as(orders.transition(subject(), null, id, "confirmed", false), Views.Order.class);
  }

  @POST
  @Path("orders/{id}/cancel")
  public Views.Order cancel(@PathParam("id") long id) {
    return views.as(orders.transition(subject(), null, id, "cancelled", false), Views.Order.class);
  }

  @POST
  @Path("orders/{id}/deliver")
  public Views.Order deliver(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Deliver input) {
    return views.as(
        orders.transition(subject(), null, id, "delivered", input.paid()), Views.Order.class);
  }

  @POST
  @Path("orders/{id}/mark-paid")
  public Views.Order paid(@PathParam("id") long id) {
    return views.as(orders.transition(subject(), null, id, "paid", true), Views.Order.class);
  }

  @GET
  @Path("production-run")
  public Views.ProductionRun production(@QueryParam("date") String date) {
    return views.as(orders.production(subject(), date), Views.ProductionRun.class);
  }
}
