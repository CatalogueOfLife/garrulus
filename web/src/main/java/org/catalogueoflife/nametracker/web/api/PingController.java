package org.catalogueoflife.nametracker.web.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

  /** Health path for the deploy scripts. */
  @GetMapping("/api/ping")
  public String ping() {
    return "pong";
  }
}
