package ph.com.ridgewell.county.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** The single exception mapper (v1 design, section 6). Every error leaves as a {@link Problem}. */
@Provider
public class Problems implements ExceptionMapper<Exception> {
  @Override
  public Response toResponse(Exception exception) {
    if (exception instanceof Problem problem) return problem.getResponse();
    if (uniqueViolation(exception))
      return Problem.response(409, "A record with those details already exists", Map.of());
    if (exception instanceof ConstraintViolationException violations)
      return Problem.response(422, "Check the submitted fields", fields(violations));
    JsonProcessingException json = cause(exception, JsonProcessingException.class);
    if (json != null) return json(json);
    if (exception instanceof IllegalArgumentException
        || exception instanceof java.time.DateTimeException)
      return Problem.response(422, "Invalid input", Map.of());
    if (exception instanceof WebApplicationException web) {
      int status = web.getResponse().getStatus();
      return Problem.response(status, Problem.title(status), Map.of());
    }
    return Problem.response(500, "The request could not be completed", Map.of());
  }

  /**
   * A body that is not JSON, or not an object, is a 400. A field of the wrong JSON type is a 422
   * keyed by its path, the same key bean validation would use for that field.
   */
  static Response json(JsonProcessingException error) {
    if (error instanceof MismatchedInputException mismatch) {
      String path = path(mismatch.getPath());
      if (!path.isEmpty())
        return Problem.response(
            422, "Check the submitted fields", Map.of(path, expected(mismatch.getTargetType())));
    }
    return Problem.response(400, "The request body is not a valid JSON object", Map.of());
  }

  private static String path(List<JsonMappingException.Reference> references) {
    StringBuilder path = new StringBuilder();
    for (var reference : references) {
      if (reference.getFieldName() != null) {
        if (!path.isEmpty()) path.append('.');
        path.append(reference.getFieldName());
      } else if (reference.getIndex() >= 0
          && !(reference.getFrom() instanceof Collection<?> c && !(c instanceof List<?>)))
        path.append('[').append(reference.getIndex()).append(']');
    }
    return path.toString();
  }

  private static String expected(Class<?> type) {
    if (type == null) return "Invalid value";
    if (type == Integer.class || type == int.class || type == Long.class || type == long.class)
      return "Enter a whole number";
    if (type == BigDecimal.class) return "Enter a number";
    if (type == Boolean.class || type == boolean.class) return "Enter true or false";
    if (type == UUID.class) return "Enter a UUID";
    if (type == String.class) return "Enter text";
    if (Collection.class.isAssignableFrom(type) || type.isArray()) return "Enter a list";
    return "Invalid value";
  }

  /**
   * Field paths without the resource method and parameter: {@code lines[0].quantity}, not {@code
   * place.input.lines[0].quantity}. A missing body is reported as {@code body}.
   */
  static Map<String, String> fields(ConstraintViolationException violations) {
    Map<String, String> fields = new TreeMap<>();
    for (ConstraintViolation<?> v : violations.getConstraintViolations())
      fields.putIfAbsent(field(v.getPropertyPath()), v.getMessage());
    return fields;
  }

  private static String field(Path path) {
    StringBuilder field = new StringBuilder();
    for (Path.Node node : path) {
      ElementKind kind = node.getKind();
      if (kind == ElementKind.METHOD
          || kind == ElementKind.CONSTRUCTOR
          || kind == ElementKind.PARAMETER
          || kind == ElementKind.CROSS_PARAMETER
          || kind == ElementKind.RETURN_VALUE) continue;
      // An element of a List carries its index; Set elements have none and report the set.
      if (node.isInIterable() && node.getIndex() != null)
        field.append('[').append(node.getIndex()).append(']');
      if (kind == ElementKind.CONTAINER_ELEMENT || node.getName() == null) continue;
      if (!field.isEmpty()) field.append('.');
      field.append(node.getName());
    }
    return field.isEmpty() ? "body" : field.toString();
  }

  private static <T extends Throwable> T cause(Throwable error, Class<T> type) {
    for (Throwable t = error; t != null; t = t.getCause()) if (type.isInstance(t)) return type.cast(t);
    return null;
  }

  private static boolean uniqueViolation(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause())
      if (t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
    return false;
  }
}
