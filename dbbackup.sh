#!/bin/bash
java -cp ~/.gradle/caches/modules-2/files-2.1/com.h2database/h2/1.4.200/f7533fe7cb8e99c87a43d325a77b4b678ad9031a/h2-1.4.200.jar org.h2.tools.Script -url jdbc:h2:file:/home/westbam/Development/jormanager/build/libs/jormanager -user sa -script dbbackup.zip -options compression zip
