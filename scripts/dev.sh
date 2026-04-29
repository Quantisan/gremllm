#!/usr/bin/env bash
set -e

# Remove stale build artifacts so wait-on doesn't treat existing files as ready
rm -f target/main.js resources/public/compiled-js/manifest.edn

concurrently \
  --names "shadow,electron,dataspex,browser" \
  --prefix "[{name}]" \
  --prefix-colors "cyan,yellow,green,magenta" \
  "shadow-cljs watch main-dev renderer-dev" \
  "wait-on file:target/main.js file:resources/public/compiled-js/manifest.edn && ELECTRON_IS_DEV=1 electron-forge start" \
  "clj -A:dev -M dev/start_dataspex.clj 2>dataspex.log" \
  "wait-on tcp:7117 && open -g http://localhost:7117"
