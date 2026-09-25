FROM ubuntu:25.04

ARG APP_NAME
ENV APP_NAME $APP_NAME

# the whole point of this module: ffmpeg and imagemagick, and the CPU they monopolize,
# run here rather than inside the api.
RUN apt-get update
RUN apt-get install -y ca-certificates imagemagick ffmpeg
RUN rm -rf /var/lib/apt/lists/*

COPY target/${APP_NAME} /app
CMD ["/app"]
