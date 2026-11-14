package org.catalogueoflife.nametracker.web;

import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

  @Bean
  TaskQueue taskQueue(TaskMapper mapper) {
    return new TaskQueue(mapper);
  }
}
