# Local commands mirror the CI jobs one-for-one, so "works on my machine"
# and "works in the pipeline" cannot drift.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo $$JAVA_HOME)
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
run: ## Run against docker-compose Postgres
	$(MVN) spring-boot:run -Dspring-boot.run.profiles=local

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
