package org.catalogueoflife.nametracker.web.api.public_;

import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.catalogueoflife.nametracker.web.api.public_.dto.XrDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The COL extended release that names are matched against. */
@RestController
public class XrController {

  private final IndexMapper index;

  public XrController(IndexMapper index) {
    this.index = index;
  }

  @GetMapping("/api/xr")
  public XrDto current() {
    XrRelease r = index.currentRelease();
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no XR release known yet");
    }
    return new XrDto(r.key(), r.alias());
  }
}
