# gnfinder for the integration test GnfinderClientIT only (Testcontainers builds this file). Development and production
# run gnfinder natively (CLAUDE.md, "gnfinder"). gnfinder v1.5.0 is tagged on GitHub without binaries or a Docker image
# (gnames/gnfinder stops at v1.4.0, amd64 only), so it is built from its source tag, natively for the host platform.
# Same entrypoint and default port (8999) as the upstream image.
#
# This file pins the gnfinder version of the project: change GNFINDER_VERSION together with the image tag in
# GnfinderClientIT and the `go install` tag in CLAUDE.md.
FROM golang:1.27-alpine AS build
ARG GNFINDER_VERSION=v1.5.0
RUN apk add --no-cache git
RUN git clone --depth 1 --branch ${GNFINDER_VERSION} https://github.com/gnames/gnfinder /src
WORKDIR /src
RUN CGO_ENABLED=0 go build -trimpath \
      -ldflags "-s -w -X github.com/gnames/gnfinder/pkg.Version=${GNFINDER_VERSION} -X github.com/gnames/gnfinder/pkg.Build=docker" \
      -o /out/gnfinder .

FROM alpine:3.22
# send logs to `docker logs` instead of a file inside the container (as upstream)
ENV GNF_LOG_DESTINATION=stderr
COPY --from=build /out/gnfinder /bin/gnfinder
ENTRYPOINT ["gnfinder"]
CMD ["-p", "8999"]
