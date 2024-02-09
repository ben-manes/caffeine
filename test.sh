#!/bin/sh

for f in `cat test-paths.txt`
  do
  sed -i -e "s/paths =.*/paths = \\[ \"${f}\" \\]/g" simulator/src/main/resources/reference.conf
  result=`./gradlew simulator:run -q | grep "║" | grep -v "Policy" | awk '{print $4}'`
  echo "$result $f"
  done
