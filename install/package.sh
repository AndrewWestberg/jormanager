#!/bin/bash
VERSION="0.2.10-SNAPSHOT"
tar -cjvf /home/westbam/Development/jormanager/install/build/JorManager-${VERSION}.tar.bz2 \
-C /home/westbam/Development/jormanager/build/libs/ jormanager-${VERSION}.jar \
-C /home/westbam/Development/jormanager/install itn_rewards_v1-configXX.yaml \
-C /home/westbam/Development/jormanager/install application.properties \
-C /home/westbam/Development/jormanager/install 00-jormanager.conf \
-C /home/westbam/Development/jormanager/install jormanager.service