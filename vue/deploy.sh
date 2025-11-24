#!/bin/bash

pushd $(dirname "$0")
export NVM_DIR=$HOME/.nvm
source $NVM_DIR/nvm.sh
nvm use v16.17.0
npm run build
rsync -av --progress --delete dist/ ../src/main/resources/static
popd