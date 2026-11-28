package ph.com.ridgewell.county.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import ph.com.ridgewell.county.persistence.Discrepancies;

/** The short-delivery reports of one reseller's orders. Scope comes from the link's token. */
@Path("/order/{token}/orders/{id}/discrepancy")
@Produces("application/json")
public class ResellerDiscrepancyResource {
  @Inject Discrepancies discrepancies;

  @POST
  @APIResponse(
      responseCode = "200",
      description = "Idempotent replay",
      content = @Content(schema = @Schema(implementation = Views.Discrepancy.class)))
  @APIResponse(
      responseCode = "201",
      description = "Report filed",
      content = @Content(schema = @Schema(implementation = Views.Discrepancy.class)))
  public Response file(
      @PathParam("token") String token,
      @PathParam("id") long id,
      @NotNull @Valid Inputs.Discrepancy input) {
    var result = discrepancies.fileByReseller(token, id, input);
    return Response.status(result.replay() ? 200 : 201).entity(result.report()).build();
  }

  @GET
  public Views.Discrepancy get(@PathParam("token") String token, @PathParam("id") long id) {
    return discrepancies.forReseller(token, id);
  }

  @POST
  @Path("approve")
  public Views.Discrepancy approve(
      @PathParam("token") String token, @PathParam("id") long id, Inputs.Resolve input) {
    throw new UnsupportedOperationException();
  }

  @POST
  @Path("decline")
  public Views.Discrepancy decline(
      @PathParam("token") String token, @PathParam("id") long id, Inputs.Resolve input) {
    throw new UnsupportedOperationException();
  }
}
