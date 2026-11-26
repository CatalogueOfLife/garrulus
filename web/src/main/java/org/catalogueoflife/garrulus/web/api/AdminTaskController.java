package org.catalogueoflife.garrulus.web.api;

import java.util.Map;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.web.auth.AdminOrcids;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Enqueues a task by hand (admin token or admin session, {@link AdminAccess}); not mapped at all (404) without either
 * way configured ({@link AdminAccessConfigured}).
 */
@RestController
@Conditional(AdminAccessConfigured.class)
public class AdminTaskController {

  public record EnqueueRequest(
      String type, @Nullable JsonNode payload, @Nullable TaskPriority priority, @Nullable String dedupKey) {}

  private final TaskQueue queue;
  private final AdminAccess access;

  public AdminTaskController(TaskQueue queue, AdminAccess access) {
    this.queue = queue;
    this.access = access;
  }

  @PostMapping("/api/admin/tasks")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Long> enqueue(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @RequestBody EnqueueRequest req) {
    // checked here, not in a path-matching filter: whatever path variant reached this handler is checked
    access.check(given);
    if (req.type() == null || req.type().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
    }
    String payload = req.payload() == null || req.payload().isNull() ? "{}" : req.payload().toString();
    TaskPriority priority = req.priority() == null ? TaskPriority.LIVE : req.priority();
    return Map.of("id", queue.enqueue(req.type(), payload, priority, req.dedupKey()));
  }
}
