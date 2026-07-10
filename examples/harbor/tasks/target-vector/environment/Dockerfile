FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update -qq \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y \
       python3 python3-pip python3-venv curl ca-certificates tar \
    && python3 -m venv /opt/harbor-acp-venv \
    && /opt/harbor-acp-venv/bin/pip install --no-cache-dir \
       agent-client-protocol \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /app /logs/agent /logs/verifier \
    && chmod 0777 /app /logs/agent /logs/verifier
WORKDIR /app
