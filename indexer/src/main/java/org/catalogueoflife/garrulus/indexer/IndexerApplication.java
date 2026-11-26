package org.catalogueoflife.garrulus.indexer;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Harvesting, analysis and matching. Migrates the shared schema and runs the pipeline task workers. */
@SpringBootApplication
@MapperScan(basePackages = "org.catalogueoflife.garrulus.store", annotationClass = Mapper.class)
@EnableScheduling
public class IndexerApplication {

  public static void main(String[] args) {
    SpringApplication.run(IndexerApplication.class, args);
  }
}
