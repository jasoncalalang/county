package ph.com.ridgewell.county.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import ph.com.ridgewell.county.persistence.Resellers;

/** The owner's resellers and their order links. Tenant comes from the verified subject. */
@Path("/")
@Authenticated
@Produces("application/json")
@Tag(name = "Owner Resource")
public class OwnerResellerResource {
  @Inject io.quarkus.security.identity.SecurityIdentity identity;
  @Inject Resellers resellers;

  private String subject() {
    return Subjects.of(identity);
  }

  @GET
  @Path("resellers")
  public List<Views.Reseller> resellers() {
    return resellers.list(subject());
  }

  @POST
  @Path("resellers")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Reseller.class)))
  public Response reseller(@jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return Response.status(201).entity(resellers.create(subject(), input)).build();
  }

  @PUT
  @Path("resellers/{id}")
  public Views.Reseller reseller(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Reseller input) {
    return resellers.update(subject(), id, input);
  }

  @POST
  @Path("resellers/{id}/block")
  public Views.Reseller block(@PathParam("id") long id) {
    return resellers.block(subject(), id);
  }

  @POST
  @Path("resellers/{id}/unblock")
  public Views.Reseller unblock(@PathParam("id") long id) {
    return resellers.unblock(subject(), id);
  }

  @POST
  @Path("resellers/{id}/reissue-link")
  public Views.Reseller reissueLink(@PathParam("id") long id) {
    return resellers.reissueLink(subject(), id);
  }
}
