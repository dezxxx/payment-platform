# Commands for the whole platform. Every module is its own Gradle build.
#   make        start Nexus, build the image, start the stack
#   make help   the list of targets
# On Windows install make once: winget install ezwinports.make
# Works from Git Bash, PowerShell, cmd and the IDE, so recipes use no shell
# built-ins or pipes (on Windows make runs them through cmd).

ifeq ($(OS),Windows_NT)
    WRAPPER := gradlew.bat
else
    WRAPPER := gradlew
endif

# absolute paths with forward slashes - the only form both cmd and sh accept
API      := $(CURDIR)/individuals-api
CLIENT   := $(CURDIR)/person-client
CONTRACT := $(CURDIR)/person-service

# one place to switch to the old docker-compose if needed
DOCKER_COMPOSE := docker compose

# compose names volumes <folder>_<volume>
PROJECT := $(notdir $(CURDIR))

.DEFAULT_GOAL := all
.PHONY: all help build jar publish-local publish test it check up down restart rebuild reset-db logs ps clean

# all: start the stack - the image builds itself; the one command
all: up

# help: list the targets
help:
	@echo make              start the stack - the image builds individuals-api inside Docker
	@echo make build        publish person-client locally, then build with tests
	@echo make jar          publish person-client locally, then package the jar
	@echo make publish-local   person-client into the local Maven repository
	@echo make publish      person-client into Nexus - needs NEXUS_USERNAME and NEXUS_PASSWORD
	@echo make test         unit tests only - needs nothing running
	@echo make it           integration tests - stops the stack first
	@echo make check        contract validation of person-service
	@echo make up           start Nexus, wait for it, then build the image and start the stack
	@echo make down         stop the stack, keep the volumes
	@echo make restart      down, then up
	@echo make rebuild      rebuild the image from scratch, then start
	@echo make reset-db     wipe both databases - Keycloak users and persons - then start
	@echo make logs         follow the application log
	@echo make ps           what is running
	@echo make clean        remove build output of every module

# build: publish person-client locally, then build individuals-api with tests
build: publish-local
	cd $(API) && $(API)/$(WRAPPER) build

# jar: publish person-client locally, then package the application jar
jar: publish-local
	cd $(API) && $(API)/$(WRAPPER) bootJar

# publish-local: person-client into the local Maven repository
publish-local:
	cd $(CLIENT) && $(CLIENT)/$(WRAPPER) publishToMavenLocal

# publish: person-client into Nexus (needs NEXUS_USERNAME and NEXUS_PASSWORD)
publish:
	cd $(CLIENT) && $(CLIENT)/$(WRAPPER) publish

# test: unit tests only - needs nothing running
test:
	cd $(API) && $(API)/$(WRAPPER) test

# it: integration tests - they start their own containers, so stop the stack first
it: down
	cd $(API) && $(API)/$(WRAPPER) integrationTest

# check: contract validation of person-service
check:
	cd $(CONTRACT) && $(CONTRACT)/$(WRAPPER) check

# up: Nexus first - the image build downloads person-client from it - then the rest;
# --wait returns only when every container with a healthcheck reports healthy
up:
	$(DOCKER_COMPOSE) up -d --wait nexus
	$(DOCKER_COMPOSE) up -d --build --wait

# down: stop the stack, keep the volumes
down:
	$(DOCKER_COMPOSE) down

# restart: down, then up
restart: down up

# rebuild: build the image from scratch, ignoring the layer cache, then start
rebuild:
	@$(DOCKER_COMPOSE) up -d --wait nexus
	@$(DOCKER_COMPOSE) build --no-cache individuals-api
	@$(DOCKER_COMPOSE) up -d

# reset-db: empty Keycloak and person databases, then start again. Keycloak
# re-imports the realm; the person-service stub forgets who registered.
# Nexus, metrics, traces and logs are kept
reset-db: down
	docker volume rm $(PROJECT)_keycloak-postgres-data $(PROJECT)_person-postgres-data
	$(MAKE) up

# logs: follow the application log
logs:
	$(DOCKER_COMPOSE) logs -f individuals-api

# ps: what is running
ps:
	$(DOCKER_COMPOSE) ps

# clean: remove build output of every module
clean:
	cd $(CLIENT) && $(CLIENT)/$(WRAPPER) clean
	cd $(API) && $(API)/$(WRAPPER) clean
	cd $(CONTRACT) && $(CONTRACT)/$(WRAPPER) clean
