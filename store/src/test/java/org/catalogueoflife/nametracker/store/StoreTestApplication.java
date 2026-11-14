package org.catalogueoflife.nametracker.store;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Test-only Boot application: store is a plain library, the real apps live in indexer and web. */
@SpringBootApplication
@MapperScan("org.catalogueoflife.nametracker.store")
public class StoreTestApplication {
}
