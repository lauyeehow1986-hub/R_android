FROM rocker/r-ver:4.4.1

RUN apt-get update && apt-get install -y --no-install-recommends \
    libsodium-dev \
    && rm -rf /var/lib/apt/lists/*

RUN install2.r --error --skipmissing \
    plumber \
    processx \
    base64enc \
    jsonlite

RUN useradd --create-home --shell /bin/bash rexec
RUN mkdir -p /data/sessions && chown -R rexec:rexec /data
WORKDIR /home/rexec/app
COPY plumber.R run.R ./
RUN chown -R rexec:rexec /home/rexec

USER rexec

ENV PORT=8000
EXPOSE 8000

CMD ["Rscript", "run.R"]
