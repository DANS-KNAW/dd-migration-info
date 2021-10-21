dd-migration-info
=================

Migration info service

SYNOPSIS
--------

    dd-migration-info { run-service | load-from-dataverse }


DESCRIPTION
-----------



ARGUMENTS
---------

    Options:

       -h, --help      Show help message
       -v, --version   Show version of this program

    Subcommand: load-from-dataverse - Loads all records from the configured Dataverse instance
       -h, --help   Show help message
    ---

    Subcommand: run-service - Starts DD Migration Info as a daemon that services HTTP requests
       -h, --help   Show help message
    ---

EXAMPLES
--------

    dd-migration-info -o value

INSTALLATION AND CONFIGURATION
------------------------------
Currently this project is built as an RPM package for RHEL7/CentOS7 and later. The RPM will install the binaries to
`/opt/dans.knaw.nl/dd-migration-info` and the configuration files to `/etc/opt/dans.knaw.nl/dd-migration-info`. 

To install the module on systems that do not support RPM, you can copy and unarchive the tarball to the target host.
You will have to take care of placing the files in the correct locations for your system yourself. For instructions
on building the tarball, see next section.

BUILDING FROM SOURCE
--------------------
Prerequisites:

* Java 8 or higher
* Maven 3.3.3 or higher
* RPM

Steps:
    
    git clone https://github.com/DANS-KNAW/dd-migration-info.git
    cd dd-migration-info 
    mvn clean install

If the `rpm` executable is found at `/usr/local/bin/rpm`, the build profile that includes the RPM 
packaging will be activated. If `rpm` is available, but at a different path, then activate it by using
Maven's `-P` switch: `mvn -Pprm install`.

Alternatively, to build the tarball execute:

    mvn clean install assembly:single
