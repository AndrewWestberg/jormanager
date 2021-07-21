#!/bin/bash

echo Use atlassian.com password

curl -s -u muamw10 -X POST https://api.bitbucket.org/2.0/repositories/muamw10/jormanager/downloads -F files=@build/libs/jormanager-$1-SNAPSHOT.jar
curl -s -u muamw10 -X POST https://api.bitbucket.org/2.0/repositories/muamw10/jormanager/downloads -F files=@build/libs/jormanager-$1-SNAPSHOT.jar.sha256
