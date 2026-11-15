package org.catalogueoflife.nametracker.web;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** User-facing, mostly read, always on. Never migrates the shared schema; the indexer owns that. */
@SpringBootApplication
@MapperScan(basePackages = "org.catalogueoflife.nametracker.store", annotationClass = Mapper.class)
public class WebApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebApplication.class, args);
  }
}
