package ph.com.ridgewell.county.api;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import java.util.List;
import ph.com.ridgewell.county.persistence.Orders;
import ph.com.ridgewell.county.persistence.Quotes;

@Path("/order/{token}")
@Produces("application/json")
public class ResellerResource {
  @Inject Orders orders;
  @Inject Quotes quotes;

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

  @GET
  @Path("quotes")
  public List<Views.QuoteSummary> quotes(@PathParam("token") String token) {
    return quotes.open(token);
  }

  @GET
  @Path("quotes/{id}")
  public Views.Quote quote(@PathParam("token") String token, @PathParam("id") long id) {
    return quotes.getPublic(token, id);
  }

  @POST
  @Path("quotes/{id}/accept")
  @APIResponse(
      responseCode = "200",
      description = "Already accepted: the order this quote placed",
      content = @Content(schema = @Schema(implementation = Views.Order.class)))
  @APIResponse(
      responseCode = "201",
      description = "Order placed at the quoted prices",
      content = @Content(schema = @Schema(implementation = Views.Order.class)))
  public Response accept(
      @PathParam("token") String token,
      @PathParam("id") long id,
      @jakarta.validation.constraints.NotNull @Valid Inputs.AcceptQuote input) {
    var result = quotes.accept(token, id, input);
    return Response.status(result.replay() ? 200 : 201).entity(result.order()).build();
  }

  @POST
  @Path("quotes/{id}/decline")
  public Views.Quote decline(@PathParam("token") String token, @PathParam("id") long id) {
    return quotes.decline(token, id);
  }
}
