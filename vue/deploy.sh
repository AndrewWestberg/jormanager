#!/bin/bash

pushd $(dirname "$0")
npm run build
rsync -av --progress --delete dist/ ../src/main/resources/static
popd