package ph.com.ridgewell.county.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import ph.com.ridgewell.county.persistence.Orders;

@Path("/order/{token}")
@Produces("application/json")
public class ResellerResource {
  @Inject Orders orders;
  @Inject Views views;

  @GET
  public Views.Catalog catalog(@PathParam("token") String token) {
    return views.as(orders.catalog(token), Views.Catalog.class);
  }

  @POST
  @Path("orders")
  @APIResponse(
      responseCode = "200",
      description = "Idempotent replay",
      content = @Content(schema = @Schema(implementation = Views.Order.class)))
  @APIResponse(
      responseCode = "201",
      description = "Order created",
      content = @Content(schema = @Schema(implementation = Views.Order.class)))
  public Response place(
      @PathParam("token") String token,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Place input) {
    var result = orders.place(token, input);
    return Response.status(result.replay() ? 200 : 201)
        .entity(views.as(result.order(), Views.Order.class))
        .build();
  }

  @GET
  @Path("orders/{id}")
  public Views.Order order(@PathParam("token") String token, @PathParam("id") long id) {
    return views.as(orders.getPublic(token, id), Views.Order.class);
  }

  @POST
  @Path("orders/{id}/cancel")
  public Views.Order cancel(@PathParam("token") String token, @PathParam("id") long id) {
    return views.as(orders.transition(null, token, id, "cancelled", false), Views.Order.class);
  }
}
