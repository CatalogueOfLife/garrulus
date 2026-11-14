package org.catalogueoflife.nametracker.store.task;

/** Lower code runs first. */
public enum TaskPriority {
  LIVE(0), REPROCESS(1), BACKFILL(2);

  private final int code;

  TaskPriority(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  public static TaskPriority fromCode(int code) {
    for (TaskPriority p : values()) {
      if (p.code == code) {
        return p;
      }
    }
    throw new IllegalArgumentException("Unknown task priority " + code);
  }
}
