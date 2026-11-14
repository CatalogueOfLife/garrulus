package org.catalogueoflife.nametracker.finder.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The default finder (the pipeline) over HTTP, as defined by {@code finder-api.yaml}. */
@SpringBootApplication
public class FinderServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(FinderServiceApplication.class, args);
  }
}
