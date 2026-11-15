package org.catalogueoflife.nametracker.store;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Test-only Boot application: store is a plain library, the real apps live in indexer and web. */
@SpringBootApplication
@MapperScan(basePackages = "org.catalogueoflife.nametracker.store", annotationClass = Mapper.class)
public class StoreTestApplication {
}
