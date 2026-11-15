package org.catalogueoflife.nametracker.indexer.match;

import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.task.Task;
import org.springframework.stereotype.Component;

/** {@code XR_CHECK}, payload {@code {}}, dedup key {@code xr-check:<date>}, daily. A CLB outage defers the task. */
@Component
public class XrCheckHandler implements TaskHandler {

  public static final String TYPE = "XR_CHECK";

  private final XrReleaseService xr;

  public XrCheckHandler(XrReleaseService xr) {
    this.xr = xr;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    xr.check();
  }
}
