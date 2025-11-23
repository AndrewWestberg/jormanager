In order to get JorManager 7 to run properly, you'll need to run the following commands on your database.

There are some old db migrations that had to be changed that only worked on the old hsql db and no longer work on the postgres database. Updating these checksums fixes the issue.


```
update databasechangelog set md5sum='8:8b26effa764fdab80c14522d73c2584a' where id='001';
update databasechangelog set md5sum='8:2c456874c82db94112d539dd745ee39f' where id='016';
update databasechangelog set md5sum='8:90e70065fdc520e852139986b629b6ca' where id='018';
```