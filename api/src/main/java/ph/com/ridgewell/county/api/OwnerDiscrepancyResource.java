package ph.com.ridgewell.county.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import ph.com.ridgewell.county.domain.DiscrepancyRules;
import ph.com.ridgewell.county.persistence.Discrepancies;

/** The owner's short-delivery reports. Tenant comes from the verified subject. */
@Path("/")
@Authenticated
@Produces("application/json")
@Tag(name = "Owner Resource")
public class OwnerDiscrepancyResource {
  @Inject io.quarkus.security.identity.SecurityIdentity identity;
  @jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uri;
  @Inject Discrepancies discrepancies;

  private String subject() {
    return Subjects.of(identity);
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

  @POST
  @Path("orders/{id}/discrepancy/approve")
  public Views.Discrepancy approve(@PathParam("id") long id, @RequestBody(required = false) @Valid Inputs.Resolve input) {
    return discrepancies.approveByOwner(subject(), id, input);
  }

  @POST
  @Path("orders/{id}/discrepancy/decline")
  public Views.Discrepancy decline(@PathParam("id") long id, @RequestBody(required = false) @Valid Inputs.Resolve input) {
    return discrepancies.declineByOwner(subject(), id, input);
  }

  @GET
  @Path("discrepancies")
  public List<Views.DiscrepancySummary> list(@QueryParam("status") String status) {
    if (status == null) {
      if (uri.getQueryParameters().containsKey("status")) throw badStatus();
      return discrepancies.list(subject());
    }
    return discrepancies.listByStatus(subject(), statusFilter(status));
  }

  /** Statuses are lowercase on the wire: the filter takes exactly those. */
  private static DiscrepancyRules.Status statusFilter(String value) {
    for (var status : DiscrepancyRules.Status.values())
      if (status.wire().equals(value)) return status;
    throw badStatus();
  }

  private static Problem badStatus() {
    return Problem.field("status", "Use open, approved or declined");
  }
}
