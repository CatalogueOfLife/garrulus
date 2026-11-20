package org.catalogueoflife.nametracker.indexer.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IssnlSwitchTest {

  @Test
  void switchOffMakesNoCall() throws Exception {
    // a null mapper, datasource and URL that is no URL: any use would throw
    IssnlRefreshHandler h = new IssnlRefreshHandler(null, null, null, null, false, "not a url", Duration.ofSeconds(1));
    h.handle(null);
  }

  @Test
  void issnlDefaultsOff() {
    new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("nametracker.dotenv=optional:file:/nonexistent/garrulus-tests.env[.properties]")
        .run(ctx -> assertThat(ctx.getEnvironment().getProperty("nametracker.discovery.issnl.enabled", Boolean.class))
            .isFalse());
  }
}
