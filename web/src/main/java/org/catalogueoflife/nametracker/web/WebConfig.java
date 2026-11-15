package org.catalogueoflife.nametracker.web;

import java.nio.file.Path;
import java.time.Clock;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.blob.FileBlobStore;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

  @Bean
  TaskQueue taskQueue(TaskMapper mapper) {
    return new TaskQueue(mapper);
  }

  /** Read-only use: web never calls put. */
  @Bean
  BlobStore blobStore(@Value("${nametracker.blobs.dir:./data/blobs}") String dir) {
    return new FileBlobStore(Path.of(dir));
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
