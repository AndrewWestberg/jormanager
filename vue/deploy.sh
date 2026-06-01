#!/bin/bash

pushd $(dirname "$0")
export NVM_DIR=$HOME/.nvm
source $NVM_DIR/nvm.sh
nvm use v24.12.0
npm run build
mkdir -p ../src/main/resources/static && rm -rf ../src/main/resources/static/* && cp -rf dist/. ../src/main/resources/static/
popd