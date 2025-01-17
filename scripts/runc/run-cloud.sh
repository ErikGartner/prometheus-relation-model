#!/bin/bash
VERSION=0.8.2
JAR_NAME="prometheus-relation-model_2.10-"$VERSION".jar"
args=${@:2}

# Source a target file that specifies some common arguments:
# WORK_PATH (required)
# REMOTE_HOST (required)
# SPARK_SUBMIT (for exjobb8)
# Prometheus-specific args...

if [ ! -f "$1" ]; then
  echo "$1 does not exist, cannot be sourced"
  exit 1
else
  source $1
  : "${REMOTE_HOST:?Need to set REMOTE_HOST non-empty}"
  : "${WORK_PATH:?Need to set WORK_PATH non-empty}"
fi

SPARK_SUBMIT="${SPARK_SUBMIT:-spark-submit}"

# Controls the max resultsize for a collect()
SPARK_MAX_RESULTSIZE="${SPARK_MAX_RESULTSIZE:-8192m}"

# This is not the fastest GC, but works well under heavy GC load.
JVMOPTS=$JVMOPTS" -XX:+AggressiveOpts -XX:+PrintFlagsFinal -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+G1SummarizeConcMark -XX:InitiatingHeapOccupancyPercent=35"

# Pretty colours
L_RED="\e[91m"
RES="\e[0m"
GREEN="\e[32m"
CYAN="\e[95m"

printf "$CYAN == Environment == $RES\n"
printf "$CYAN%-25s %s $RES\n" "REMOTE_HOST:" "$REMOTE_HOST"
printf "$CYAN%-25s %s $RES\n" "WORK_PATH:" "$WORK_PATH"
printf "$CYAN%-25s %s $RES\n" "EXTRA_SPARK_OPTIONS:" "$EXTRA_SPARK_OPTIONS"
printf "$CYAN%-25s %s $RES\n" "args:" "$args"
printf "$CYAN%-25s %s $RES\n" "EXTRA_ENVS:" "$EXTRA_ENVS"
printf "$CYAN%-25s %s $RES\n" "EXTRA_JVM:" "$JVMOPTS"


function execute {
  "$@"
  local status=$?
  if [ $status -ne 0 ]; then
    printf "$L_RED Error with $1$RES\n" >&2
    printf "$CYAN Status code: $status$RES\n"
    exit $status
  fi

  return $status
}

printf "$GREEN == Packing jars == $RES\n"
execute sbt pack

# Run python inline to construct the classpath list
LIBS=$(python -c "import os; print(','.join(map(lambda fname: 'lib/' + fname, os.listdir('target/pack/lib'))))")

printf "$GREEN == Synchronizing dependencies and executables == $RES\n"
execute rsync -av --delete -e ssh --progress target/pack/lib/ $REMOTE_HOST:$WORK_PATH/lib/
execute scp target/scala-2.10/$JAR_NAME $REMOTE_HOST:$WORK_PATH/$JAR_NAME

SESSION_NAME="prometheus-"$(date +%R:%S)
printf "$GREEN == Running $JAR_NAME in Screen Session: \"$SESSION_NAME\" on $REMOTE_HOST == $RES\n"
printf "$GREEN >> Screen Session: $RED\"$SESSION_NAME\"$RES\n"
REMOTE_COMMAND="cd $WORK_PATH; "\
"$EXTRA_ENVS $SPARK_SUBMIT "\
"--conf spark.driver.maxResultSize=$SPARK_MAX_RESULTSIZE "\
"--conf spark.executor.extraJavaOptions=\"$JVMOPTS\" "\
"--jars $LIBS "\
"$EXTRA_SPARK_OPTIONS "\
"$JAR_NAME $args"
execute ssh -t $REMOTE_HOST "screen -h 10000 -L -S $SESSION_NAME  bash -c '$REMOTE_COMMAND; echo "Sleeping for 10m"; sleep 600'"
printf "$GREEN == Done == $RES\n"

