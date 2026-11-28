package ph.com.ridgewell.county.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import ph.com.ridgewell.county.persistence.*;

@Path("/")
@Authenticated
@Produces("application/json")
public class OwnerResource {
  @Inject io.quarkus.security.identity.SecurityIdentity identity;
  @Inject Owners owners;
  @Inject Orders orders;
  @Inject Quotes quotes;

  private String subject() {
    return Subjects.of(identity);
  }

  @GET
  @Path("me")
  public Views.Me me() {
    String subject = subject();
    var jwt = (JsonWebToken) identity.getPrincipal();
    return owners.me(subject, jwt.getClaim("name"), jwt.getClaim("email"));
  }

  @POST
  @Path("tenants")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Business.class)))
  public Response create(@jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return Response.status(201)
        .entity(owners.create(subject(), input))
        .build();
  }

  @GET
  @Path("settings")
  public Views.Business settings() {
    return owners.settings(subject(), null);
  }

  @PUT
  @Path("settings")
  public Views.Business settings(
      @jakarta.validation.constraints.NotNull @Valid Inputs.Business input) {
    return owners.settings(subject(), input);
  }

  /**
   * The delivery date an order placed now would get with these settings, for the settings and
   * set-up forms. Takes unsaved values, so it needs a signed-in owner but no business.
   */
  @GET
  @Path("settings/preview")
  public Views.SettingsPreview preview(
      @QueryParam("cutoff") String cutoff, @QueryParam("deliveryDays") List<String> deliveryDays) {
    subject();
    return Owners.preview(cutoff, deliveryDays);
  }

  @GET
  @Path("products")
  public List<Views.Product> products() {
    return owners.products(subject());
  }

  @POST
  @Path("products")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Product.class)))
  public Response product(@jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return Response.status(201)
        .entity(owners.product(subject(), null, input))
        .build();
  }

  @PUT
  @Path("products/{id}")
  public Views.Product product(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.Product input) {
    return owners.product(subject(), id, input);
  }

  @GET
  @Path("production-run")
  public Views.ProductionRun production(@QueryParam("date") String date) {
    return orders.production(subject(), date);
  }

  @GET
  @Path("price-tiers")
  public List<Views.PriceTier> priceTiers() {
    return owners.priceTiers(subject());
  }

  @POST
  @Path("price-tiers")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.PriceTier.class)))
  public Response priceTier(
      @jakarta.validation.constraints.NotNull @Valid Inputs.PriceTier input) {
    return Response.status(201).entity(owners.priceTier(subject(), null, input)).build();
  }

  @PUT
  @Path("price-tiers/{id}")
  public Views.PriceTier priceTier(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.PriceTier input) {
    return owners.priceTier(subject(), id, input);
  }

  @GET
  @Path("price-tiers/{id}/prices")
  public Views.TierPrices tierPrices(@PathParam("id") long id) {
    return owners.tierPrices(subject(), id);
  }

  @PUT
  @Path("products/{id}/tier-prices/{tierId}")
  public Views.TierPrices setTierPrice(
      @PathParam("id") long id,
      @PathParam("tierId") long tierId,
      @jakarta.validation.constraints.NotNull @Valid Inputs.TierPrice input) {
    return owners.setTierPrice(subject(), id, tierId, input);
  }

  @DELETE
  @Path("products/{id}/tier-prices/{tierId}")
  @APIResponse(responseCode = "204", description = "No Content")
  public Response clearTierPrice(@PathParam("id") long id, @PathParam("tierId") long tierId) {
    owners.clearTierPrice(subject(), id, tierId);
    return Response.noContent().build();
  }

  @PUT
  @Path("resellers/{id}/price-tier")
  public Views.Reseller resellerTier(
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.ResellerTier input) {
    return owners.assignResellerTier(subject(), id, input);
  }

  @GET
  @Path("quotes")
  public List<Views.QuoteSummary> quotes(
      @QueryParam("status") String status, @QueryParam("resellerId") String resellerId) {
    return quotes.list(subject(), status, resellerId);
  }

  @POST
  @Path("quotes")
  @APIResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = Views.Quote.class)))
  public Response quote(@jakarta.validation.constraints.NotNull @Valid Inputs.Quote input) {
    return Response.status(201).entity(quotes.create(subject(), input)).build();
  }

  @GET
  @Path("quotes/{id}")
  public Views.Quote quote(@PathParam("id") long id) {
    return quotes.get(subject(), id);
  }

  @POST
  @Path("quotes/{id}/send")
  public Views.Quote sendQuote(@PathParam("id") long id) {
    return quotes.send(subject(), id);
  }

  @POST
  @Path("quotes/{id}/cancel")
  public Views.Quote cancelQuote(@PathParam("id") long id) {
    return quotes.cancel(subject(), id);
  }
}
