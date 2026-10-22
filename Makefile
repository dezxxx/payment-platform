# on Windows: winget install ezwinports.make; recipes avoid shell built-ins and pipes, since make runs them through cmd

ifeq ($(OS),Windows_NT)
    WRAPPER := gradlew.bat
else
    WRAPPER := gradlew
endif

# absolute paths with forward slashes - the only form both cmd and sh accept
API         := $(CURDIR)/individuals-api
PERSON      := $(CURDIR)/person-service
TRANSACTION := $(CURDIR)/transaction-service

DOCKER_COMPOSE := docker compose

# compose names volumes <folder>_<volume>
PROJECT := $(notdir $(CURDIR))

# Nexus credentials from .env, so publishing needs no exports typed by hand
-include .env
export NEXUS_USERNAME ?= $(NEXUS_ADMIN_USER)
export NEXUS_PASSWORD ?= $(NEXUS_ADMIN_PASSWORD)

.DEFAULT_GOAL := all
.PHONY: all help verify verify-down up down restart rebuild reset-db logs ps \
        build jar publish-local publish test it check clean \
        person-build person-jar person-test person-it person-check person-publish-local person-publish person-clean \
        individuals-build individuals-jar individuals-test individuals-it individuals-clean \
        transaction-build transaction-jar transaction-test transaction-it transaction-check transaction-publish-local transaction-publish transaction-clean

all: up

help:
	@echo == platform ==
	@echo   make              start the stack - the images build the services inside Docker
	@echo   make verify       everything in order: person-service and transaction-service tests, their clients to Nexus, individuals-api tests, images from scratch, stack up
	@echo   make verify-down  stop the stack first, then verify
	@echo   make up           start Nexus, wait for it, then build the images and start the stack
	@echo   make down         stop the stack, keep the volumes
	@echo   make restart      down, then up
	@echo   make rebuild      rebuild the images from scratch, then start
	@echo   make reset-db     wipe every database - Keycloak users, persons, wallets and transactions - then start
	@echo   make logs         follow the application logs
	@echo   make ps           what is running
	@echo == all modules ==
	@echo   make build        publish the clients locally, then build every module with tests
	@echo   make jar          publish the clients locally, then package every jar
	@echo   make publish-local   every client into the local Maven repository
	@echo   make publish      every client into Nexus - needs NEXUS_USERNAME and NEXUS_PASSWORD
	@echo   make test         unit tests of every module - needs nothing running
	@echo   make it           integration tests of every module - stops the stack first
	@echo   make check        person-service and transaction-service: contract and tests
	@echo   make clean        remove build output of every module
	@echo == person-service ==
	@echo   make person-build person-jar person-test person-it person-check
	@echo   make person-publish-local person-publish person-clean
	@echo == individuals-api ==
	@echo   make individuals-build individuals-jar individuals-test individuals-it individuals-clean
	@echo == transaction-service ==
	@echo   make transaction-build transaction-jar transaction-test transaction-it transaction-check
	@echo   make transaction-publish-local transaction-publish transaction-clean

# ---------------------------------------------------------------- platform

# the same, after stopping the stack: integration tests need the memory it holds
verify-down: down
	$(MAKE) verify

# integration tests need memory: stop the stack yourself before this if it is up
verify:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) clean check publishClientPublicationToMavenLocal
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) clean check publishClientPublicationToMavenLocal
	$(DOCKER_COMPOSE) up -d --wait nexus
	cd $(PERSON) && $(PERSON)/$(WRAPPER) publishClientPublicationToNexusRepository
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) publishClientPublicationToNexusRepository
	cd $(API) && $(API)/$(WRAPPER) clean build --refresh-dependencies
	$(DOCKER_COMPOSE) build --no-cache person-service transaction-service individuals-api
	$(DOCKER_COMPOSE) up -d --wait

# Nexus first: the image build downloads person-service-client from it
up:
	$(DOCKER_COMPOSE) up -d --wait nexus
	$(DOCKER_COMPOSE) up -d --build --wait

down:
	$(DOCKER_COMPOSE) down

restart: down up

rebuild:
	@$(DOCKER_COMPOSE) up -d --wait nexus
	@$(DOCKER_COMPOSE) build --no-cache person-service transaction-service individuals-api
	@$(DOCKER_COMPOSE) up -d

reset-db: down
	docker volume rm $(PROJECT)_keycloak-postgres-data $(PROJECT)_person-postgres-data \
		$(PROJECT)_transaction-postgres-0-data $(PROJECT)_transaction-postgres-1-data
	$(MAKE) up

logs:
	$(DOCKER_COMPOSE) logs -f person-service transaction-service individuals-api

ps:
	$(DOCKER_COMPOSE) ps

# ---------------------------------------------------------------- all modules

# the clients go first: individuals-api resolves them as artifacts
build: publish-local person-build transaction-build individuals-build

jar: publish-local person-jar transaction-jar individuals-jar

publish-local: person-publish-local transaction-publish-local

publish: person-publish transaction-publish

test: person-test transaction-test individuals-test

it: down person-it transaction-it individuals-it

check: person-check transaction-check

clean: person-clean transaction-clean individuals-clean

# ---------------------------------------------------------------- person-service

person-build:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) build

person-jar:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) bootJar

person-test:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) test

person-it:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) integrationTest

# contract, unit and integration tests, the 80% coverage gate
person-check:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) check

person-publish-local:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) publishClientPublicationToMavenLocal

person-publish:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) publishClientPublicationToNexusRepository

person-clean:
	cd $(PERSON) && $(PERSON)/$(WRAPPER) clean

# ---------------------------------------------------------------- individuals-api

individuals-build:
	cd $(API) && $(API)/$(WRAPPER) build

individuals-jar:
	cd $(API) && $(API)/$(WRAPPER) bootJar

individuals-test:
	cd $(API) && $(API)/$(WRAPPER) test

individuals-it:
	cd $(API) && $(API)/$(WRAPPER) integrationTest

individuals-clean:
	cd $(API) && $(API)/$(WRAPPER) clean

# ---------------------------------------------------------------- transaction-service

transaction-build:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) build

transaction-jar:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) bootJar

transaction-test:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) test

transaction-it:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) integrationTest

# contract, unit and integration tests
transaction-check:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) check

transaction-publish-local:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) publishClientPublicationToMavenLocal

transaction-publish:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) publishClientPublicationToNexusRepository

transaction-clean:
	cd $(TRANSACTION) && $(TRANSACTION)/$(WRAPPER) clean
