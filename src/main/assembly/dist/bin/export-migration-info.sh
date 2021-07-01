#!/bin/sh

TEMPOUT=miginfo.sql
OUTFILE=${1:-"miginfo.zip"}

pg_dump --data -U dd_migration_info dd_migration_info > $TEMPOUT
zip $OUTFILE $TEMPOUT
rm $TEMPOUT
