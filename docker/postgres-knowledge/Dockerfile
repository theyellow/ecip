# docker/postgres-knowledge/Dockerfile
# PostgreSQL 16 with pgvector and Apache AGE extensions
FROM postgres:16

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    postgresql-server-dev-16 \
    ca-certificates \
    libreadline-dev \
    zlib1g-dev \
    flex \
    bison \
    && rm -rf /var/lib/apt/lists/*

# Install pgvector
RUN cd /tmp \
    && git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git \
    && cd pgvector \
    && make \
    && make install \
    && rm -rf /tmp/pgvector

# Install Apache AGE
RUN cd /tmp \
    && git clone --branch PG16/v1.5.0-rc0 https://github.com/apache/age.git \
    && cd age \
    && make \
    && make install \
    && rm -rf /tmp/age

# Clean build dependencies
RUN apt-get purge -y --auto-remove build-essential git postgresql-server-dev-16 \
    ca-certificates libreadline-dev zlib1g-dev flex bison \
    && rm -rf /var/lib/apt/lists/*

# Copy extension initialization script
COPY init-extensions.sql /docker-entrypoint-initdb.d/00-init-extensions.sql
