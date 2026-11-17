package ph.com.ridgewell.county.api;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.stream.Collectors;

@Provider
public class Problems implements ExceptionMapper<Exception> {
  @Override
  public Response toResponse(Exception exception) {
    if (exception instanceof Problem problem) return problem.getResponse();
    if (uniqueViolation(exception))
      return new Problem(409, "A record with those details already exists").getResponse();
    int status = 500;
    String detail = "The request could not be completed";
    Map<String, String> fields = Map.of();
    if (exception instanceof ConstraintViolationException violations) {
      status = 422;
      detail = "Check the submitted fields";
      fields =
          violations.getConstraintViolations().stream()
              .collect(
                  Collectors.toMap(
                      v -> v.getPropertyPath().toString(), v -> v.getMessage(), (a, b) -> a));
    } else if (exception instanceof IllegalArgumentException
        || exception instanceof java.time.DateTimeException) {
      status = 422;
      detail = "Invalid input";
    } else if (exception instanceof WebApplicationException web) {
      status = web.getResponse().getStatus();
      detail = Problem.title(status);
    }
    return Response.status(status)
        .type("application/problem+json")
        .entity(
            Map.of(
                "type",
                "about:blank",
                "title",
                Problem.title(status),
                "status",
                status,
                "detail",
                detail,
                "errors",
                fields))
        .build();
  }

  private static boolean uniqueViolation(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause())
      if (t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
    return false;
  }
}
