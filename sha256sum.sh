#!/bin/bash
pushd /home/westbam/Development/jormanager/build/libs
sha256sum jormanager-$1-SNAPSHOT.jar > jormanager-$1-SNAPSHOT.jar.sha256
echo "jormanager-$1-SNAPSHOT.jar.sha256"
echo "---------------------------------"
cat jormanager-$1-SNAPSHOT.jar.sha256
popd
