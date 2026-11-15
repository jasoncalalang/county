package ph.county.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import ph.county.persistence.Orders;

@Path("/order/{token}")
@Produces("application/json")
public class ResellerResource {
  @Inject Orders orders;

  @GET
  public Object catalog(@PathParam("token") String token) {
    return orders.catalog(token);
  }

  @POST
  @Path("orders")
  public Response place(
      @PathParam("token") String token,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Place input) {
    var result = orders.place(token, input);
    return Response.status(result.replay() ? 200 : 201).entity(result.order()).build();
  }

  @GET
  @Path("orders/{id}")
  public Object order(@PathParam("token") String token, @PathParam("id") long id) {
    return orders.getPublic(token, id);
  }

  @POST
  @Path("orders/{id}/cancel")
  public Object cancel(@PathParam("token") String token, @PathParam("id") long id) {
    return orders.transition(null, token, id, "cancelled", false);
  }
}
