package org.catalogueoflife.nametracker.web.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Temporary admin endpoint (shared-secret header, constant-time compare, until ORCID admin roles in M4); not mapped at all (404) unless nametracker.admin.token is set. */
@RestController
@Conditional(AdminTokenConfigured.class)
public class AdminTaskController {

  public record EnqueueRequest(
      String type, @Nullable JsonNode payload, @Nullable TaskPriority priority, @Nullable String dedupKey) {}

  private final TaskQueue queue;
  private final byte[] token;

  public AdminTaskController(TaskQueue queue, @Value("${nametracker.admin.token}") String token) {
    this.queue = queue;
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping("/api/admin/tasks")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Long> enqueue(
      @RequestHeader(name = "X-Admin-Token", required = false) @Nullable String given,
      @RequestBody EnqueueRequest req) {
    // checked here, not in a path-matching filter: whatever path variant reached this handler is checked
    if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    if (req.type() == null || req.type().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
    }
    String payload = req.payload() == null || req.payload().isNull() ? "{}" : req.payload().toString();
    TaskPriority priority = req.priority() == null ? TaskPriority.LIVE : req.priority();
    return Map.of("id", queue.enqueue(req.type(), payload, priority, req.dedupKey()));
  }
}
