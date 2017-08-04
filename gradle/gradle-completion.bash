# Bash breaks words on : by default. Subproject tasks have ':'
# Avoid inaccurate completions for subproject tasks
COMP_WORDBREAKS=$(echo "$COMP_WORDBREAKS" | sed -e 's/://g')

__gradle-set-project-root-dir() {
    local dir=`pwd`
    project_root_dir=`pwd`
    while [[ $dir != '/' ]]; do
        if [[ -f "$dir/settings.gradle" || -f "$dir/gradlew" ]]; then
            project_root_dir=$dir
            return 0
        fi
        dir="$(dirname "$dir")"
    done
    return 1
}

__gradle-init-cache-dir() {
    cache_dir="$HOME/.gradle/completion"
    mkdir -p $cache_dir
}

__gradle-set-build-file() {
    # Look for default build script in the settings file (settings.gradle by default)
    # Otherwise, default is the file 'build.gradle' in the current directory.
    gradle_build_file="$project_root_dir/build.gradle"
    if [[ -f "$project_root_dir/settings.gradle" ]]; then
        local build_file_name=$(grep "^rootProject\.buildFileName" "$project_root_dir/settings.gradle" | \
            sed -n -e "s/rootProject\.buildFileName = [\'\"]\(.*\)[\'\"]/\1/p")
        gradle_build_file="$project_root_dir/${build_file_name:-build.gradle}"
    fi
}

__gradle-set-cache-name() {
    # Cache name is constructed from the absolute path of the build file.
    cache_name=$(echo $gradle_build_file | sed -e 's/\//_/g')
}

__gradle-set-files-checksum() {
    # Cache MD5 sum of all Gradle scripts and modified timestamps
    if builtin command -v md5 > /dev/null; then
        gradle_files_checksum=$(md5 -q -s "$(cat "$cache_dir/$cache_name" | xargs ls -o 2>/dev/null)")
    elif builtin command -v md5sum > /dev/null; then
        gradle_files_checksum=$(cat "$cache_dir/$cache_name" | xargs ls -o 2>/dev/null | md5sum | awk '{print $1}')
    else
        echo "Cannot generate completions as neither md5 nor md5sum exist on \$PATH"
    fi
}

__gradle-generate-script-cache() {
    # Invalidate cache after 3 weeks by default
    local cache_ttl_mins=${GRADLE_CACHE_TTL_MINUTES:-30240}
    local script_exclude_pattern=${GRADLE_COMPLETION_EXCLUDE_PATTERN:-"/(build|integTest|out)/"}

    if [[ ! $(find $cache_dir/$cache_name -mmin -$cache_ttl_mins 2>/dev/null) ]]; then
        # Cache all Gradle scripts
        local gradle_build_scripts=$(find $project_root_dir -type f -name "*.gradle" -o -name "*.gradle.kts" 2>/dev/null | egrep -v "$script_exclude_pattern")
        printf "%s\n" "${gradle_build_scripts[@]}" > $cache_dir/$cache_name
    fi
}

__gradle-long-options() {
    local args="--build-cache           - Enables the Gradle build cache
--build-file            - Specifies the build file
--configure-on-demand   - Only relevant projects are configured
--console               - Type of console output to generate (plain auto rich)
--continue              - Continues task execution after a task failure
--continuous            - Continuous mode. Automatically re-run build after changes
--daemon                - Use the Gradle Daemon
--debug                 - Log at the debug level
--dry-run               - Runs the build with all task actions disabled
--exclude-task          - Specify a task to be excluded
--full-stacktrace       - Print out the full (very verbose) stacktrace
--gradle-user-home      - Specifies the Gradle user home directory
--gui                   - Launches the Gradle GUI app (Deprecated)
--help                  - Shows a help message
--include-build         - Run the build as a composite, including the specified build
--info                  - Set log level to INFO
--init-script           - Specifies an initialization script
--max-workers           - Set the maximum number of workers that Gradle may use
--no-build-cache        - Do not use the Gradle build cache
--no-daemon             - Do not use the Gradle Daemon
--no-rebuild            - Do not rebuild project dependencies
--no-scan               - Do not create a build scan
--no-search-upwards     - Do not search in parent directories for a settings.gradle
--offline               - Build without accessing network resources
--parallel              - Build projects in parallel
--profile               - Profile build time and create report
--project-cache-dir     - Specifies the project-specific cache directory
--project-dir           - Specifies the start directory for Gradle
--project-prop          - Sets a project property of the root project
--quiet                 - Log errors only
--recompile-scripts     - Forces scripts to be recompiled, bypassing caching
--refresh-dependencies  - Refresh the state of dependencies
--rerun-tasks           - Specifies that any task optimization is ignored
--scan                  - Create a build scan
--settings-file         - Specifies the settings file
--stacktrace            - Print out the stacktrace also for user exceptions
--status                - Print Gradle Daemon status
--stop                  - Stop all Gradle Daemons
--system-prop           - Set a system property
--version               - Prints Gradle version info
--warn                  - Log warnings and errors only"
    COMPREPLY=( $(compgen -W "$args" -- "${COMP_WORDS[COMP_CWORD]}") )
}

__gradle-properties() {
    local args="-Dorg.gradle.cache.reserved.mb=   - Reserve Gradle Daemon memory for operations
-Dorg.gradle.caching=             - Set true to enable Gradle build cache
-Dorg.gradle.daemon.debug=        - Set true to debug Gradle Daemon
-Dorg.gradle.daemon.idletimeout=  - Kill Gradle Daemon after # idle millis
-Dorg.gradle.debug=               - Set true to debug Gradle Client
-Dorg.gradle.jvmargs=             - Set JVM arguments
-Dorg.gradle.java.home=           - Set JDK home dir
-Dorg.gradle.logging.level=       - Set default Gradle log level (quiet warn lifecycle info debug)
-Dorg.gradle.parallel=            - Set true to enable parallel project builds (incubating)
-Dorg.gradle.parallel.intra=      - Set true to enable intra-project parallel builds (incubating)
-Dorg.gradle.workers.max=         - Set the number of workers Gradle is allowed to use"
    COMPREPLY=( $(compgen -W "$args" -- "${COMP_WORDS[COMP_CWORD]}") )
    return 0
}

__gradle-short-options() {
    local args="-?                      - Shows a help message
-a                      - Do not rebuild project dependencies
-b                      - Specifies the build file
-c                      - Specifies the settings file
-d                      - Log at the debug level
-g                      - Specifies the Gradle user home directory
-h                      - Shows a help message
-i                      - Set log level to INFO
-m                      - Runs the build with all task actions disabled
-p                      - Specifies the start directory for Gradle
-q                      - Log errors only
-s                      - Print out the stacktrace also for user exceptions
-t                      - Continuous mode. Automatically re-run build after changes
-u                      - Do not search in parent directories for a settings.gradle
-v                      - Prints Gradle version info
-w                      - Log warnings and errors only
-x                      - Specify a task to be excluded
-D                      - Set a system property
-I                      - Specifies an initialization script
-P                      - Sets a project property of the root project
-S                      - Print out the full (very verbose) stacktrace"
    COMPREPLY=( $(compgen -W "$args" -- "${COMP_WORDS[COMP_CWORD]}") )
}

__gradle-notify-tasks-cache-build() {
    # Notify user of cache rebuild
    echo -e " (Building completion cache. Please wait)\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\c"
    __gradle-generate-tasks-cache
    # Remove "please wait" message by writing a bunch of spaces then moving back to the left
    echo -e "                                         \b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\c"
}

__gradle-generate-tasks-cache() {
    __gradle-set-files-checksum

    # Use Gradle wrapper when it exists.
    local gradle_cmd="gradle"
    if [[ -x "$project_root_dir/gradlew" ]]; then
        gradle_cmd="$project_root_dir/gradlew"
    fi

    # Run gradle to retrieve possible tasks and cache.
    # Reuse Gradle Daemon if IDLE but don't start a new one.
    local gradle_tasks_output
    if [[ ! -z "$($gradle_cmd --status 2>/dev/null | grep IDLE)" ]]; then
        gradle_tasks_output="$($gradle_cmd -b $gradle_build_file --daemon -q tasks --all)"
    else
        gradle_tasks_output="$($gradle_cmd -b $gradle_build_file --no-daemon -q tasks --all)"
    fi
    local output_line
    local task_description
    local -a gradle_all_tasks=()
    local -a root_tasks=()
    local -a subproject_tasks=()
    for output_line in $gradle_tasks_output; do
        if [[ $output_line =~ ^([[:lower:]][[:alnum:][:punct:]]*)([[:space:]]-[[:space:]]([[:print:]]*))? ]]; then
            task_name="${BASH_REMATCH[1]}"
            task_description="${BASH_REMATCH[3]}"
            gradle_all_tasks+=( "$task_name  - $task_description" )
            # Completion for subproject tasks with ':' prefix
            if [[ $task_name =~ ^([[:alnum:][:punct:]]+):([[:alnum:]]+) ]]; then
                gradle_all_tasks+=( ":$task_name  - $task_description" )
                subproject_tasks+=( "${BASH_REMATCH[2]}" )
            else
                root_tasks+=( "$task_name" )
            fi
        fi
    done

    # subproject tasks can be referenced implicitly from root project
    if [[ $GRADLE_COMPLETION_UNQUALIFIED_TASKS == "true" ]]; then
        local -a implicit_tasks=()
        implicit_tasks=( $(comm -23 <(printf "%s\n" "${subproject_tasks[@]}" | sort) <(printf "%s\n" "${root_tasks[@]}" | sort)) )
        for task in $(printf "%s\n" "${implicit_tasks[@]}"); do
            gradle_all_tasks+=( $task )
        done
    fi

    printf "%s\n" "${gradle_all_tasks[@]}" > $cache_dir/$gradle_files_checksum
    echo $gradle_files_checksum > $cache_dir/$cache_name.md5
}

__gradle-completion-init() {
    local cache_dir cache_name gradle_build_file gradle_files_checksum project_root_dir

    local OLDIFS="$IFS"
    local IFS=$'\n'

    __gradle-init-cache-dir
    __gradle-set-project-root-dir
    __gradle-set-build-file
    if [[ -f $gradle_build_file ]]; then
        __gradle-set-cache-name
        __gradle-generate-script-cache
        __gradle-set-files-checksum
        __gradle-notify-tasks-cache-build
    fi

    IFS="$OLDIFS"

    return 0
}

_gradle() {
    local cache_dir cache_name gradle_build_file gradle_files_checksum project_root_dir
    local cur=${COMP_WORDS[COMP_CWORD]}
    # Set bash internal field separator to '\n'
    # This allows us to provide descriptions for options and tasks
    local OLDIFS="$IFS"
    local IFS=$'\n'

    if [[ ${cur} == --* ]]; then
        __gradle-long-options
    elif [[ ${cur} == -D* ]]; then
        __gradle-properties
    elif [[ ${cur} == -* ]]; then
        __gradle-short-options
    else
        __gradle-init-cache-dir
        __gradle-set-project-root-dir
        __gradle-set-build-file
        if [[ -f $gradle_build_file ]]; then
            __gradle-set-cache-name
            __gradle-generate-script-cache
            __gradle-set-files-checksum

            # The cache key is md5 sum of all gradle scripts, so it's valid if it exists.
            if [[ -f $cache_dir/$cache_name.md5 ]]; then
                local cached_checksum="$(cat $cache_dir/$cache_name.md5)"
                local -a cached_tasks
                if [[ -z $cur ]]; then
                    cached_tasks=( $(cat $cache_dir/$cached_checksum) )
                else
                    cached_tasks=( $(grep "^$cur" $cache_dir/$cached_checksum) )
                fi
                COMPREPLY=( $(compgen -W "${cached_tasks[*]}" -- "$cur") )
            else
                __gradle-notify-tasks-cache-build
            fi

            # Regenerate tasks cache in the background
            if [[ $gradle_files_checksum != "$(cat $cache_dir/$cache_name.md5)" || ! -f $cache_dir/$gradle_files_checksum ]]; then
                $(__gradle-generate-tasks-cache 1>&2 2>/dev/null &)
            fi
        else
            # Default tasks available outside Gradle projects
            local args="buildEnvironment     - Displays all buildscript dependencies declared in root project.
components           - Displays the components produced by root project.
dependencies         - Displays all dependencies declared in root project.
dependencyInsight    - Displays the insight into a specific dependency in root project.
dependentComponents  - Displays the dependent components of components in root project.
help                 - Displays a help message.
init                 - Initializes a new Gradle build.
model                - Displays the configuration model of root project.
projects             - Displays the sub-projects of root project.
properties           - Displays the properties of root project.
tasks                - Displays the tasks runnable from root project.
wrapper              - Generates Gradle wrapper files."
            COMPREPLY=( $(compgen -W "$args" -- "${COMP_WORDS[COMP_CWORD]}") )
        fi
    fi

    IFS="$OLDIFS"

    # Remove description ("[:space:]" and after) if only one possibility
    if [[ ${#COMPREPLY[*]} -eq 1 ]]; then
        COMPREPLY=( ${COMPREPLY[0]%%  *} )
    fi

    return 0
}
complete -F _gradle gradle
complete -F _gradle gradle.bat
complete -F _gradle gradlew
complete -F _gradle gradlew.bat
complete -F _gradle ./gradlew
complete -F _gradle ./gradlew.bat

if hash gw 2>/dev/null || alias gw >/dev/null 2>&1; then
    complete -F _gradle gw
fi
