package ph.county.api;

import io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyReactiveViolationException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;

@Provider
@Priority(1)
public class ValidationProblems implements ExceptionMapper<ResteasyReactiveViolationException> {
  public Response toResponse(ResteasyReactiveViolationException e) {
    return new Problems().toResponse(e);
  }
}
