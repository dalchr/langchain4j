#!/bin/bash
# Stops the running Ktor server (or any process bound to port 8080)
if lsof -ti:8080 >/dev/null 2>&1; then
  kill $(lsof -ti:8080) 2>/dev/null
  echo "Server on port 8080 stopped."
else
  echo "No process found on port 8080."
fi
