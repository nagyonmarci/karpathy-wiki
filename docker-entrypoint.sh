#!/bin/sh
# Auto-compile on interactive startup so the wiki is populated from the start.
# When a specific command is passed (e.g. "status", "lint"), skip auto-compile
# to avoid running it twice.
if [ $# -eq 0 ]; then
  java -jar app.jar compile
fi
exec java -jar app.jar "$@"
