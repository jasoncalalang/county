package ph.com.ridgewell.county.api;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** The short-delivery reports of one reseller's orders. Scope comes from the link's token. */
@Path("/order/{token}/orders/{id}/discrepancy")
@Produces("application/json")
public class ResellerDiscrepancyResource {
  @POST
  public Response file(
      @PathParam("token") String token, @PathParam("id") long id, Inputs.Discrepancy input) {
    throw new UnsupportedOperationException();
  }

  @GET
  public Views.Discrepancy get(@PathParam("token") String token, @PathParam("id") long id) {
    throw new UnsupportedOperationException();
  }
}
