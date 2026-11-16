#!/bin/bash
set -e
cd frontend
npm ci
nohup npm run dev > nohup-fe.out &
