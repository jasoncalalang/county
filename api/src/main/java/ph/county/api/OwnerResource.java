package ph.county.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import ph.county.persistence.*;

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
  public Object me() {
    return owners.me(subject());
  }

  @POST
  @Path("tenants")
  public Response create(@jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return Response.status(201).entity(owners.create(subject(), input)).build();
  }

  @GET
  @Path("settings")
  public Object settings() {
    return owners.settings(subject(), null);
  }

  @PUT
  @Path("settings")
  public Object settings(@jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return owners.settings(subject(), input);
  }

  @GET
  @Path("products")
  public Object products() {
    return owners.products(subject());
  }

  @POST
  @Path("products")
  public Response product(@jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return Response.status(201).entity(owners.product(subject(), null, input)).build();
  }

  @PUT
  @Path("products/{id}")
  public Object product(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return owners.product(subject(), id, input);
  }

  @GET
  @Path("resellers")
  public Object resellers() {
    return owners.resellers(subject());
  }

  @POST
  @Path("resellers")
  public Response reseller(@jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return Response.status(201).entity(owners.reseller(subject(), null, input)).build();
  }

  @PUT
  @Path("resellers/{id}")
  public Object reseller(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return owners.reseller(subject(), id, input);
  }

  @POST
  @Path("resellers/{id}/{action:block|unblock|reissue-link}")
  public Object resellerAction(@PathParam("id") long id, @PathParam("action") String action) {
    return owners.resellerAction(subject(), id, action);
  }

  @GET
  @Path("orders")
  public Object orders(@QueryParam("date") String date, @QueryParam("status") String status) {
    if (date == null && uri.getQueryParameters().containsKey("date"))
      throw new Problem(422, "Invalid delivery date");
    return orders.list(subject(), date, status);
  }

  @GET
  @Path("orders/{id}")
  public Object order(@PathParam("id") long id) {
    return orders.get(subject(), id);
  }

  @POST
  @Path("orders/{id}/confirm")
  public Object confirm(@PathParam("id") long id) {
    return orders.transition(subject(), null, id, "confirmed", false);
  }

  @POST
  @Path("orders/{id}/cancel")
  public Object cancel(@PathParam("id") long id) {
    return orders.transition(subject(), null, id, "cancelled", false);
  }

  @POST
  @Path("orders/{id}/deliver")
  public Object deliver(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Deliver input) {
    return orders.transition(subject(), null, id, "delivered", input.paid());
  }

  @POST
  @Path("orders/{id}/mark-paid")
  public Object paid(@PathParam("id") long id) {
    return orders.transition(subject(), null, id, "paid", true);
  }

  @GET
  @Path("production-run")
  public Object production(@QueryParam("date") String date) {
    return orders.production(subject(), date);
  }
}
