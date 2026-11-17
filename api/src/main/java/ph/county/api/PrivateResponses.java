package ph.county.api;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;

@Provider
public class PrivateResponses implements ContainerResponseFilter {
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    response.getHeaders().putSingle("Cache-Control", "no-store");
    response.getHeaders().putSingle("Referrer-Policy", "no-referrer");
  }
}
