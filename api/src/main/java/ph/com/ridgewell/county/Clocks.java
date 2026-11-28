package ph.com.ridgewell.county;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

/** The one place that chooses the clock, so tests can run at a fixed time. */
@Singleton
public class Clocks {
  @Produces
  @Singleton
  Clock clock() {
    return Clock.systemUTC();
  }
}
