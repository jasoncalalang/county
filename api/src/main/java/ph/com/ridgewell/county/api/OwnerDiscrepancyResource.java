package ph.com.ridgewell.county.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import ph.com.ridgewell.county.persistence.Discrepancies;

/** The owner's short-delivery reports. Tenant comes from the verified subject. */
@Path("/")
@Authenticated
@Produces("application/json")
@Tag(name = "Owner Resource")
public class OwnerDiscrepancyResource {
  @Inject io.quarkus.security.identity.SecurityIdentity identity;
  @Inject Discrepancies discrepancies;

  private String subject() {
    String s = identity.getPrincipal() instanceof JsonWebToken jwt ? jwt.getSubject() : null;
    if (s == null || s.isBlank()) throw new Problem(401, "Sign in required");
    return s;
  }

  @POST
  @Path("orders/{id}/discrepancy")
  @APIResponse(
      responseCode = "200",
      description = "Idempotent replay",
      content = @Content(schema = @Schema(implementation = Views.Discrepancy.class)))
  @APIResponse(
      responseCode = "201",
      description = "Report filed",
      content = @Content(schema = @Schema(implementation = Views.Discrepancy.class)))
  public Response file(
      @PathParam("id") long id, @NotNull @Valid Inputs.Discrepancy input) {
    var result = discrepancies.fileByOwner(subject(), id, input);
    return Response.status(result.replay() ? 200 : 201).entity(result.report()).build();
  }

  @GET
  @Path("orders/{id}/discrepancy")
  public Views.Discrepancy get(@PathParam("id") long id) {
    return discrepancies.forOwner(subject(), id);
  }
}
