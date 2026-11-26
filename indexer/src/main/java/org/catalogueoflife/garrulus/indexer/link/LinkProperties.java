package org.catalogueoflife.garrulus.indexer.link;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code garrulus.links.*}: {@code enabled} switches every lookup off, {@code <name>.enabled} (default true) one of
 * them; {@code source-cron} and {@code retry-cron} are read by {@link LinkScheduler}.
 */
@Component
public class LinkProperties {

  private final Environment env;
  private final boolean enabled;

  public LinkProperties(Environment env, @Value("${garrulus.links.enabled:true}") boolean enabled) {
    this.env = env;
    this.enabled = enabled;
  }

  /** The master switch. */
  public boolean enabled() {
    return enabled;
  }

  /** The lookup's own switch, read when asked, with the master switch. */
  public boolean enabled(String lookup) {
    return enabled && env.getProperty("garrulus.links." + lookup + ".enabled", Boolean.class, true);
  }
}
