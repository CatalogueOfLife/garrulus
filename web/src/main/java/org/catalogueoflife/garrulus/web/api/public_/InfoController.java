package org.catalogueoflife.garrulus.web.api.public_;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Facts about the service itself, for the About page. */
@RestController
public class InfoController {

  /** The service's facts: the first publication year it keeps. */
  public record Info(int firstYear) {}

  private final Info info;

  /** {@code garrulus.first-year} is the indexer's cut-off; both apps read the same GARRULUS_FIRST_YEAR. */
  public InfoController(@Value("${garrulus.first-year:2000}") int firstYear) {
    this.info = new Info(firstYear);
  }

  @GetMapping("/api/info")
  public Info info() {
    return info;
  }
}
