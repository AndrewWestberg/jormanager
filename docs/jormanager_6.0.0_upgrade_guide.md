# Jormanager 6.0.0 Upgrade

This guide will help you migrate to the new database format in JorManager 6.x. As with any database migration, take extreme care and follow every step to prevent data loss.

### Stop Jormanager
```
sudo systemctl stop jm.service
```

# BACKUP THE jormanager.mv.db file to a new location before doing anything!!!

### Prepare to migrate the db

Download the h2 database jar file and place them in your jormanager folder

https://bitbucket.org/muamw10/jormanager/downloads/

![](https://i.imgur.com/TBirUeu.png)

### Create a SQL backup of the 1.4.200 database

```
$ java -jar h2-1.4.200.jar
```

Login to the H2 console.

![](https://i.imgur.com/l7FZR2d.png)

In the window, run the `SCRIPT` command to backup the database to a .sql file. And click the Run button.  Wait until it completes.

```
SCRIPT NOPASSWORDS NOSETTINGS DROP BLOCKSIZE 2147483647 TO 'jormanager.sql';
```
![](https://i.imgur.com/bVMvR7f.png)
![](https://i.imgur.com/QO9Ukmp.png)


Close out of the browser window and CTRL+C on the command line to exit the java... command.

Make ANOTHER backup of your db file for good measure.

### Install postgresql

```
$ sudo apt-get install postgresql postgresql-contrib pgadmin4-desktop
```

```
$ sudo -u postgres psql
postgres=# create database jormanager;
postgres=# create user jormanager with encrypted password 'jormanager';
postgres=# grant all privileges on database jormanager to jormanager;
postgres=# \q
```

### Transmogrify the H2 database format for postgres

```
$ sed -E -i.bak 's/.*CREATE USER.*//;s/"PUBLIC"\.//g;s/CACHED TABLE/TABLE/;s/ SELECTIVITY [[:digit:]]+//g;s/ CLOB / TEXT /g;s/"([A-Z0-9_]+)"/\L\1/g;s/STRINGDECODE\(\x27([^\x27]*)\x27\)/E\x27\1\x27/g;s/STRINGDECODE\(.*DOCTYPE HTML.*\)\)/E\x27\x27)/g' jormanager.sql
```

### Load the data into postgres

```
$ psql --host=localhost --username=jormanager --dbname=jormanager --echo-errors --file=jormanager.sql > jormanager.sql.log 2>&1
```

Wait a LONG ASS time... for the data to get loaded.
![](https://i.imgur.com/7jG0W9G.gif)

### Configure JorManager

IF you named your postgres db something other than `jormanager`, add the following line to `application.properties`. For example, on testnet...

```
spring.datasource.url=jdbc:postgresql://localhost:5432/jormanager_test
```

IF your db username or password was set to something other than `jormanager`, you can add something like the following to `application.properties`

```
spring.datasource.username=jmuser
spring.datasource.password=MyJmP@55w0RD
```

### Complete the Setup

Download and Install the Jormanager 6.0.0 jar file and restart jormanager.

```
$ sudo systemctl start jm.service
```