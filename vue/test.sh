#!/bin/bash

pushd $(dirname "$0")
export NVM_DIR=$HOME/.nvm
source $NVM_DIR/nvm.sh
nvm use v24.12.0
npm run test
popd