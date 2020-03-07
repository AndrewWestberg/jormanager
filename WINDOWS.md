
# For JorManager Windows Installation

## Required Steps

1.  Install Java/copy Java folder to desired location

    1.  Optionally, add this path to system path environment variable, covered in Optional Steps, below

<!-- -->

2.  Download the "kill" tool from <https://developer.microsoft.com/en-us/windows/downloads/windows-10-sdk/>. During installation, you will only check the box for "Debugging Tools for Windows". You will point to this file in your application.properties file.

<!-- -->

3.  Install Visual C++ Redistributable [vc\_redist.x64.exe](https://aka.ms/vs/16/release/vc_redist.x64.exe) **THIS IS A DEPENDENCY FOR JORMUNGANDR**

4.  Create Windows Firewall rule to allow inbound Jormungandr traffic. Use GUI or PowerShell and include all of the ports you will be using for your ports (the bold numbers from your itn\_rewards\_v1-config0x.yaml files)

> listen\_address: "/ip4/0.0.0.0/tcp/**3300**"
>
> public\_address: "/ip4/111.222.333.444/tcp/**3300**"
>
Here is PowerShell command (run as administrator)
>
> New-NetFirewallRule -Name "JorManager Rule (in)" -Description "Allows inbound traffic for JorManager on ports 3300-3304" -DisplayName "JorManagerInboundRule" -Enabled:True -Profile Private -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3300-3304

5.  Download the latest build of JorManager from <https://bitbucket.org/muamw10/jormanager/downloads/>. Follow the Manual Setup (depreciated) instructions from <https://bitbucket.org/muamw10/jormanager/src/develop/>. Firewall rules in this list of steps has been covered, above. These steps walk you through:

    1.  Extracting JorManager to the folder of your choice (like C:\\JorManager)
    2.  Renaming the JorManager snapshot build to jormanager.jar
    3.  Creating your config.yaml files. There is a sample file on that page.
    4.  Editing your config.yaml files

<!-- -->

6.  Download Jormungandr build from <https://github.com/input-output-hk/jormungandr/releases>. Choose the one that ends in "msvc.zip".

    1.  Copy/extract both jcli.exe and jormungandr.exe to the folder you have chosen for JorManager (like C:\\JorManager)

    2.  Copy the jormungandr.exe and paste multiple times, with one copy for the number of instances you want to have running. For example, for four running nodes, you would copy and paste to end up with jormungandr00.exe, jormungandr01.exe, jormungandr02.exe, and jormungandr03.exe

<!-- -->

7.  Customize JorManager's application.properties file with Windows-specific changes (there are other customizations common to Linux and Windows that are covered in JorManager instructions):

    1.  All relative paths should start with "./" For example,  
        > **./www/html/blocks.json**

    2.  Set path to the kill command. For example,  
        > **jormanager.kill.path=C:/Program Files (x86)/Windows Kits/10/Debuggers/x64/kill.exe**

<!-- -->

8.  Run JorManager as follows, depending on your paths:  
    > **C:\\Users\\joepublic\\Downloads\\openjdk-13.0.2\_windows-x64\_bin\\jdk-13.0.2\\bin\\java.exe -jar jormanager.jar**

## Optional Steps

1.  Install Notepad ++ for ease of editing config files, including compare ability

2.  Install PeaZip for extracting archives

3.  Install Google Chrome browser

4.  Install NSSM to run JorManager as a service, in case of computer restart

5.  Insure time sync is solid with Internet based source. <https://www.pretentiousname.com/timesync/>

6.  Add Java path to the path variable (from elevated command prompt: "set path=%path%;\[path to java.exe\] for example, "set path=%path%;C:\\Users\\joepublic\\Downloads\\openjdk-13.0.2\_windows-x64\_bin\\jdk-13.0.2\\bin")

## End Results

You will end up with a folder that contains the following:

1.  A single jormanager.jar file
2.  A single jcli.exe file
3.  A single application.properties file, which you have customized for your Windows environment using this guide
4.  Multiple storage0x folders, from 00 through the number of nodes you want minus one (00-03 for four nodes)
5.  Multiple jormungandr0x.exe files, from 00 through the number of nodes you want minus one (00-03 for four nodes)
6.  Multiple itn\_rewards\_v1-config0X.yaml files, from 00 through the number of nodes you want minus one (00-03 for four nodes).
    1.  Each will have a unique:
        1.  TCP port for listening (3300 to 330x to match your nodes, like the file and folder names above)
        2.  Public ID (note for best results, insure the first 16 characters are identical)
        3.  Rest listen port (3400 to 340x to match your nodes, like the file and folder names above)
    <!-- -->

    2.  Best practices:

        1.  You can put your own trusted peers in between the \#-----JorManager\_ignored----- lines, so JorManager will always trust them

        2.  You can specify peers list under this to assist in bootstrapping. There are different methods for approaching how many/which to use, and some people bootstrap with blank and then kill the node and swap with one populated with trusted peers.

<!-- -->

7.  You could place kill.exe and/or the java files in this folder, but ultimately it does not matter, as you specify the paths

# Config File Changes Behavior

1.  Changes to JorManager's application.properties files are immediate (based on the refresh schedule). You can change it and expect JorManager to change shortly.

2.  Changes to the Jormungandr config files (itn\_rewards\_v1-config0X.yaml) only take effect when the Jormungandr node is started. If you want it to be immediate, you will need to stop the node so that JorManager can restart it.

 

## Updating JorManager:

1.  Kill the JorManager Process. This will bring it down, while leaving the Jormungandr processes running

    1.  If you are running JorManager as a service using NSSM or another method, you will kill the OpenJDK Platform binary or other Java version, depending on the version of Java being used.

    2.  You DO NOT kill any of the jormungandr0x.exe processes

<!-- -->

2.  Replace the JorManager executable

3.  Start JorManager again

## Updating Jormungandr:

1.  Use REST command to shut down the instance--specify the port number to the desired instance in the command. Note, the jcli file comes with Jormungandr and is typically dropped into the JorManager folder too:  
    > ./jcli rest v0 shutdown get --host "<http://127.0.0.1:3403/api>"

2.  Replace the Jormungandr executable, renaming to jormungandr0x.exe

3.  JorManager will automatically fire it up with the new .exe
