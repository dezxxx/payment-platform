# Commands for the whole platform, from one place.
#
# This folder is a Git root, not a Gradle project: every module is its own
# build, so bringing the stack up means building a jar in one folder and then
# running compose in this one. The targets below carry that order, so nobody
# has to remember it.
#
#   make            everything: build the jar and start the stack
#   make help       the list of targets
#
# Requires make. It ships with Linux and macOS; on Windows install it once:
#   winget install ezwinports.make
#
# Runs from anywhere: Git Bash, PowerShell, cmd, or the green arrow in the IDE.
# That is why no recipe uses a shell built-in, a pipe or a POSIX tool - on
# Windows make hands recipes to cmd, which has none of them. The wrapper is
# picked per platform for the same reason: gradlew is a shell script and
# gradlew.bat is its batch twin.

ifeq ($(OS),Windows_NT)
    WRAPPER := gradlew.bat
else
    WRAPPER := gradlew
endif

# Absolute, with forward slashes: the one spelling both shells accept. A bare
# gradlew.bat is not found by cmd, which does not search the current directory,
# and .\gradlew.bat is not found by sh, which reads the backslash as an escape.
API      := $(CURDIR)/individuals-api
CLIENT   := $(CURDIR)/person-client
CONTRACT := $(CURDIR)/person-service

# One name for the tool, in case a machine only has the old docker-compose.
DOCKER_COMPOSE := docker compose

.DEFAULT_GOAL := all
.PHONY: all help build jar publish-local publish test it check up down restart rebuild logs ps clean

# all: build everything and start the stack - the one command
all: jar up

# help: list the targets
help:
	@echo make              build the jar and start the stack
	@echo make build        publish person-client locally, then build with tests
	@echo make jar          publish person-client locally, then package the jar
	@echo make publish-local   person-client into the local Maven repository
	@echo make publish      person-client into Nexus - needs NEXUS_USERNAME and NEXUS_PASSWORD
	@echo make test         unit tests only - needs nothing running
	@echo make it           integration tests - stops the stack first
	@echo make check        contract validation of person-service
	@echo make up           start the stack, rebuilding the image
	@echo make down         stop the stack, keep the volumes
	@echo make restart      down, then up
	@echo make rebuild      rebuild the image from scratch, then start
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

# up: start the stack, rebuilding the image from the jar built beforehand
up:
	$(DOCKER_COMPOSE) up -d --build

# down: stop the stack, keep the volumes
down:
	$(DOCKER_COMPOSE) down

# restart: down, then up
restart: down up

# rebuild: build the image from scratch, ignoring the layer cache, then start
rebuild:
	@$(DOCKER_COMPOSE) build --no-cache individuals-api
	@$(DOCKER_COMPOSE) up -d

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
