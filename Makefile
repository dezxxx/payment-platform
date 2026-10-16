# on Windows: winget install ezwinports.make; recipes avoid shell built-ins and pipes, since make runs them through cmd

ifeq ($(OS),Windows_NT)
    WRAPPER := gradlew.bat
else
    WRAPPER := gradlew
endif

# absolute paths with forward slashes - the only form both cmd and sh accept
API      := $(CURDIR)/individuals-api
PERSON   := $(CURDIR)/person-service

DOCKER_COMPOSE := docker compose

# compose names volumes <folder>_<volume>
PROJECT := $(notdir $(CURDIR))

# Nexus credentials from .env, so publishing needs no exports typed by hand
-include .env
export NEXUS_USERNAME ?= $(NEXUS_ADMIN_USER)
export NEXUS_PASSWORD ?= $(NEXUS_ADMIN_PASSWORD)

.DEFAULT_GOAL := all
.PHONY: all help verify verify-down build jar publish-local publish test it check up down restart rebuild reset-db logs ps clean

all: up

help:
	@echo make              start the stack - the images build both services inside Docker
	@echo make verify       everything in order: person-service tests, client to Nexus, individuals-api tests, images from scratch, stack up
	@echo make verify-down  stop the stack first, then verify
	@echo make build        publish person-service-client locally, then build both modules with tests
	@echo make jar          publish person-service-client locally, then package both jars
	@echo make publish-local   person-service-client into the local Maven repository
	@echo make publish      person-service-client into Nexus - needs NEXUS_USERNAME and NEXUS_PASSWORD
	@echo make test         unit tests of both modules - needs nothing running
	@echo make it           integration tests of both modules - stops the stack first
	@echo make check        person-service: contract, tests and the 80% coverage gate
	@echo make up           start Nexus, wait for it, then build the image and start the stack
	@echo make down         stop the stack, keep the volumes
	@echo make restart      down, then up
	@echo make rebuild      rebuild both images from scratch, then start
	@echo make reset-db     wipe both databases - Keycloak users and persons - then start
	@echo make logs         follow both application logs
	@echo make ps           what is running
	@echo make clean        remove build output of every module

# the same, after stopping the stack: integration tests need the memory it holds
verify-down: down
	$(MAKE) verify

# integration tests need memory: stop the stack yourself before this if it is up
verify:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) clean check publishClientPublicationToMavenLocal
	$(DOCKER_COMPOSE) up -d --wait nexus
	cd $(PERSON) && $(PERSON)/$(WRAPPER) publishClientPublicationToNexusRepository
	cd $(API) && $(API)/$(WRAPPER) clean build --refresh-dependencies
	$(DOCKER_COMPOSE) build --no-cache person-service individuals-api
	$(DOCKER_COMPOSE) up -d --wait

build: publish-local
	cd $(PERSON) && $(PERSON)/$(WRAPPER) build
	cd $(API) && $(API)/$(WRAPPER) build

jar: publish-local
	cd $(PERSON) && $(PERSON)/$(WRAPPER) bootJar
	cd $(API) && $(API)/$(WRAPPER) bootJar

publish-local:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) publishClientPublicationToMavenLocal

publish:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) publishClientPublicationToNexusRepository

test:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) test
	cd $(API) && $(API)/$(WRAPPER) test

it: down
	cd $(PERSON) && $(PERSON)/$(WRAPPER) integrationTest
	cd $(API) && $(API)/$(WRAPPER) integrationTest

check:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) check

# Nexus first: the image build downloads person-service-client from it
up:
	$(DOCKER_COMPOSE) up -d --wait nexus
	$(DOCKER_COMPOSE) up -d --build --wait

down:
	$(DOCKER_COMPOSE) down

restart: down up

rebuild:
	@$(DOCKER_COMPOSE) up -d --wait nexus
	@$(DOCKER_COMPOSE) build --no-cache person-service individuals-api
	@$(DOCKER_COMPOSE) up -d

reset-db: down
	docker volume rm $(PROJECT)_keycloak-postgres-data $(PROJECT)_person-postgres-data
	$(MAKE) up

logs:
	$(DOCKER_COMPOSE) logs -f person-service individuals-api

ps:
	$(DOCKER_COMPOSE) ps

clean:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) clean
	cd $(API) && $(API)/$(WRAPPER) clean
