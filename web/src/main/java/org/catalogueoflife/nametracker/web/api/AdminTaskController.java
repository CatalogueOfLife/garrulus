package org.catalogueoflife.nametracker.web.api;

import java.util.Map;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Temporary admin endpoint; not mapped at all (404) unless nametracker.admin.token is set. */
@RestController
@ConditionalOnExpression("!'${nametracker.admin.token:}'.isBlank()")
public class AdminTaskController {

  public record EnqueueRequest(
      String type, @Nullable JsonNode payload, @Nullable TaskPriority priority, @Nullable String dedupKey) {}

  private final TaskQueue queue;

  public AdminTaskController(TaskQueue queue) {
    this.queue = queue;
  }

  @PostMapping("/api/admin/tasks")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Long> enqueue(@RequestBody EnqueueRequest req) {
    if (req.type() == null || req.type().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
    }
    String payload = req.payload() == null || req.payload().isNull() ? "{}" : req.payload().toString();
    TaskPriority priority = req.priority() == null ? TaskPriority.LIVE : req.priority();
    return Map.of("id", queue.enqueue(req.type(), payload, priority, req.dedupKey()));
  }
}
