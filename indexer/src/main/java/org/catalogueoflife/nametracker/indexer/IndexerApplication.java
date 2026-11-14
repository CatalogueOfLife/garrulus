package org.catalogueoflife.nametracker.indexer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Harvesting, analysis and matching. Migrates the shared schema and runs the pipeline task workers. */
@SpringBootApplication
@MapperScan("org.catalogueoflife.nametracker.store")
@EnableScheduling
public class IndexerApplication {

  public static void main(String[] args) {
    SpringApplication.run(IndexerApplication.class, args);
  }
}
