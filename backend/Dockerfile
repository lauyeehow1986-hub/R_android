FROM rocker/r-ver:4.4.1

RUN apt-get update && apt-get install -y --no-install-recommends \
    libsodium-dev libcurl4-openssl-dev libssl-dev \
    libxml2-dev libfontconfig1-dev libharfbuzz-dev libfribidi-dev libfreetype6-dev \
    libpng-dev libjpeg-dev libtiff5-dev libgit2-dev libssh2-1-dev libicu-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

RUN install2.r --error --skipmissing \
    plumber \
    processx \
    base64enc \
    jsonlite \
    remotes \
    webutils

# Bake any packages declared in packages.txt at build time (the build has
# network; the hardened runtime has none). Empty manifest => no-op.
COPY packages.txt /tmp/packages.txt
RUN pkgs="$(grep -vE '^[[:space:]]*#|^[[:space:]]*$' /tmp/packages.txt || true)"; \
    if [ -n "$pkgs" ]; then install2.r --error --skipmissing $pkgs; fi; \
    rm -f /tmp/packages.txt

RUN useradd --create-home --shell /bin/bash rexec
RUN mkdir -p /data/sessions /data/rlib && chown -R rexec:rexec /data
WORKDIR /home/rexec/app
COPY plumber.R run.R ./
RUN chown -R rexec:rexec /home/rexec

USER rexec

ENV PORT=8000
EXPOSE 8000

CMD ["Rscript", "run.R"]
