JAVA_HOME := $(shell /usr/libexec/java_home -v 26 2>/dev/null)
export JAVA_HOME

build:
	mvn -B package -DskipTests

up:
	docker compose run --rm karpathy-wiki import
	docker compose up -d --force-recreate wiki

run:
	./run.sh

.PHONY: build up run
