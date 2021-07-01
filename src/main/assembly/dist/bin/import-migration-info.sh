#!/bin/sh

INFILE=${1:-"miginfo.zip"}

unzip $INFILE

psql -U dd_migration_info dd_migration_info < miginfo.sql
rm miginfo.sql
