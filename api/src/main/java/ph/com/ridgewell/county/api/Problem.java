package ph.com.ridgewell.county.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;

public final class Problem extends WebApplicationException {
  public Problem(int status, String detail) {
    super(
        Response.status(status)
            .type("application/problem+json")
            .entity(
                Map.of(
                    "type",
                    "about:blank",
                    "title",
                    title(status),
                    "status",
                    status,
                    "detail",
                    detail))
            .build());
  }

  public static String title(int status) {
    return switch (status) {
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 404 -> "Not Found";
      case 409 -> "Conflict";
      case 422 -> "Unprocessable Content";
      default -> "Internal Server Error";
    };
  }
}
