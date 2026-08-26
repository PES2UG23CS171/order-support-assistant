# Local commands mirror the CI jobs one-for-one, so "works on my machine"
# and "works in the pipeline" cannot drift.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo $$JAVA_HOME)

# Testcontainers looks for a Docker socket at /var/run/docker.sock. Colima,
# Rancher Desktop and rootless Docker all put it somewhere else, so point it at
# whatever the active context actually uses. The override tells Ryuk which path to
# bind-mount inside its own container. On a CI runner both already resolve to the
# default, making this a no-op rather than a special case.
DOCKER_ENDPOINT := $(shell docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)
ifneq ($(DOCKER_ENDPOINT),)
export DOCKER_HOST := $(DOCKER_ENDPOINT)
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE := /var/run/docker.sock
endif

MVN = JAVA_HOME=$(JAVA_HOME) ./mvnw

.PHONY: help
help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "};{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

.PHONY: unit
unit: ## Unit suite only (no Docker needed)
	$(MVN) -B verify -Punit

.PHONY: integration
integration: ## Integration suite only (Testcontainers)
	$(MVN) -B verify -Pintegration

.PHONY: system
system: ## System suite only (black-box HTTP + Testcontainers)
	$(MVN) -B verify -Psystem

.PHONY: verify
verify: ## Everything, with the merged coverage gate
	$(MVN) -B verify

.PHONY: run
run: ## Run against docker-compose Postgres (chat needs OPENAI_API_KEY)
	$(MVN) spring-boot:run -Dspring-boot.run.profiles=local

.PHONY: run-ollama
run-ollama: ## Run fully local: Ollama chat, local embeddings, no API key
	$(MVN) spring-boot:run -Dspring-boot.run.profiles=local,ollama

.PHONY: probe-model
probe-model: ## Check an Ollama model against what this project needs (MODEL=qwen2.5:7b)
	python3 scripts/probe-ollama-model.py $(or $(MODEL),qwen2.5:7b)

.PHONY: db
db: ## Start Postgres+pgvector only
	docker compose up -d postgres

.PHONY: up
up: ## Build and run the whole stack
	docker compose up --build

.PHONY: down
down: ## Tear down the stack and its volume
	docker compose down -v

.PHONY: demo
demo: ## Walk the guardrail demo against a running app
	./scripts/demo.sh
