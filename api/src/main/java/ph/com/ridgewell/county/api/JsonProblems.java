package ph.com.ridgewell.county.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Request bodies Jackson cannot decode. Quarkus ships its own mapper for these with a different
 * body; these take precedence so decoding failures use the {@link Problem} shape too.
 */
public final class JsonProblems {
  private JsonProblems() {}

  @Provider
  @Priority(1)
  public static class Mismatched implements ExceptionMapper<MismatchedInputException> {
    public Response toResponse(MismatchedInputException e) {
      return Problems.json(e);
    }
  }

  @Provider
  @Priority(1)
  public static class Unreadable implements ExceptionMapper<JsonProcessingException> {
    public Response toResponse(JsonProcessingException e) {
      return Problems.json(e);
    }
  }
}
