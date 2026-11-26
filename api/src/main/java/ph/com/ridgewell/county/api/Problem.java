package ph.com.ridgewell.county.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.TreeMap;

/**
 * An RFC 9457 problem. Every API error has the same members: type, title, status, detail and
 * errors, a map from the JSON path of an offending field (such as {@code lines[0].quantity}) to a
 * message. Errors that concern no single field have an empty map.
 */
public final class Problem extends WebApplicationException {
  public Problem(int status, String detail) {
    this(status, detail, Map.of());
  }

  public Problem(int status, String detail, Map<String, String> errors) {
    super(response(status, detail, errors));
  }

  /** A 422 for one field, with the message shown beside it. */
  public static Problem field(String field, String message) {
    return new Problem(422, "Check the submitted fields", Map.of(field, message));
  }

  public static Response response(int status, String detail, Map<String, String> errors) {
    return Response.status(status)
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
                detail,
                "errors",
                new TreeMap<>(errors)))
        .build();
  }

  public static String title(int status) {
    return switch (status) {
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 406 -> "Not Acceptable";
      case 409 -> "Conflict";
      case 415 -> "Unsupported Media Type";
      case 422 -> "Unprocessable Content";
      default -> "Internal Server Error";
    };
  }
}
