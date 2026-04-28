JAVA_HOME := $(shell /usr/libexec/java_home -v 26 2>/dev/null)
export JAVA_HOME

build:
	mvn -B package -DskipTests

run:
	./run.sh

.PHONY: build run
