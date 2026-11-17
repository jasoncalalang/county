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

  @GET
  public Views.Catalog catalog(@PathParam("token") String token) {
    return orders.catalog(token);
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
        .entity(result.order())
        .build();
  }

  @GET
  @Path("orders/{id}")
  public Views.Order order(@PathParam("token") String token, @PathParam("id") long id) {
    return orders.getPublic(token, id);
  }

  @POST
  @Path("orders/{id}/cancel")
  public Views.Order cancel(@PathParam("token") String token, @PathParam("id") long id) {
    return orders.transition(null, token, id, "cancelled", false);
  }
}
