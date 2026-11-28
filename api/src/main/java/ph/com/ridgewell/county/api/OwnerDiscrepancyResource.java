package ph.com.ridgewell.county.api;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** The owner's short-delivery reports. Tenant comes from the verified subject. */
@Path("/")
@Authenticated
@Produces("application/json")
public class OwnerDiscrepancyResource {
  @POST
  @Path("orders/{id}/discrepancy")
  public Response file(@PathParam("id") long id, Inputs.Discrepancy input) {
    throw new UnsupportedOperationException();
  }

  @GET
  @Path("orders/{id}/discrepancy")
  public Views.Discrepancy get(@PathParam("id") long id) {
    throw new UnsupportedOperationException();
  }
}
