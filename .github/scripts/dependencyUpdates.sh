#!/bin/bash
set -eu
trap 'exit 0' SIGINT

BOLD="\033[1m"
RESET="\033[0m"
UNDERLINE="\033[4m"

find . -type f -name "settings.gradle.kts" | while read -r gradle_file; do
  project_dir=$(dirname "${gradle_file#./}")
  project=$( [[ "$project_dir" == "." ]] && echo "caffeine" || echo "$project_dir" )
  echo -e "\n${BOLD}${UNDERLINE}${project}${RESET}"
  echo -e "${BOLD}Evaluating...${RESET}"

  gradle=$( [[ -f "$project_dir/gradlew" ]] && echo "./$project_dir/gradlew" || echo "./gradlew" )
  output=$(JAVA_VERSION=21 \
    $gradle --project-dir "$project_dir" dependencyUpdates --no-parallel --refresh-dependencies -q "$@" | \
    sed -e '/^------------------------------------------------------------/,/^$/d' \
        -e '/The following dependencies are using the latest milestone version:/,/^$/d' \
        -e '/Gradle release-candidate updates:/d' \
        -e '/ - Gradle:.*UP-TO-DATE.*/,/^$/d' \
        -e '/^$/d')
  if [[ "$project_dir" == "gradle/plugins" ]]; then
    output=$(echo "$output" | sed '/ - Gradle:.*/,/^$/d')
  fi

  tput cuu1; tput el
  echo "${output:-UP-TO-DATE}"
done
