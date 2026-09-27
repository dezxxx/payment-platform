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
# On Windows run it from Git Bash, not from PowerShell or cmd. make is a
# Windows program: started from PowerShell it runs every recipe through cmd,
# where there is no sh, no grep and no ./gradlew. Git Bash has all three.
# In IntelliJ IDEA: Settings -> Tools -> Terminal -> Shell path -> the bash.exe
# of your Git installation.

SHELL := /bin/sh

API     := individuals-api
CLIENT  := person-client
CONTRACT := person-service

.DEFAULT_GOAL := all
.PHONY: all help build jar publish-local publish test it check up down restart logs ps clean

## all: build everything and start the stack - the one command
all: jar up

## help: list the targets
help:
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## //'

## build: publish person-client locally, then build individuals-api with tests
build: publish-local
	cd $(API) && ./gradlew build

## jar: publish person-client locally, then package the application jar
jar: publish-local
	cd $(API) && ./gradlew bootJar

## publish-local: person-client into the local Maven repository
publish-local:
	cd $(CLIENT) && ./gradlew publishToMavenLocal

## publish: person-client into Nexus (needs NEXUS_USERNAME and NEXUS_PASSWORD)
publish:
	cd $(CLIENT) && ./gradlew publish

## test: unit tests only - needs nothing running
test:
	cd $(API) && ./gradlew test

## it: integration tests - they start their own containers, so stop the stack first
it: down
	cd $(API) && ./gradlew integrationTest

## check: contract validation of person-service
check:
	cd $(CONTRACT) && ./gradlew check

## up: start the stack, rebuilding the image from the jar built beforehand
up:
	docker compose up -d --build

## down: stop the stack, keep the volumes
down:
	docker compose down

## restart: down, then up
restart: down up

## logs: follow the application log
logs:
	docker compose logs -f $(API)

## ps: what is running
ps:
	docker compose ps

## clean: remove build output of every module
clean:
	cd $(CLIENT) && ./gradlew clean
	cd $(API) && ./gradlew clean
	cd $(CONTRACT) && ./gradlew clean
