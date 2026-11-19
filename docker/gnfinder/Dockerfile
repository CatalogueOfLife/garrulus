# gnfinder built from its source tag. gnfinder v1.5.0 is tagged and released on GitHub without binaries or a Docker
# image (gnames/gnfinder stops at v1.4.0, amd64 only), so Garrulus builds its own: natively for the host platform,
# which also ends the amd64 emulation on Apple Silicon. Same entrypoint and default port (8999) as the upstream image.
#
#   docker compose build gnfinder        # or: docker build -t nametracker/gnfinder:v1.5.0 docker/gnfinder
#
# Change GNFINDER_VERSION to move to another tag (and update the tag in docker-compose.yml and GnfinderClientIT).
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
