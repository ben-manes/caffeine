# Bash breaks words on : by default. Subproject tasks have ':'
# Avoid inaccurate completions for subproject tasks
COMP_WORDBREAKS=$(echo "$COMP_WORDBREAKS" | sed -e 's/://g')

_gradle()
{
    local cur=${COMP_WORDS[COMP_CWORD]}
    local args
    # Use Gradle wrapper when it exists.
    local gradle_cmd='gradle'
    if [[ -x ./gradlew ]]; then
        gradle_cmd='./gradlew'
    fi

    local cache_dir="$HOME/.gradle/completion"
    mkdir -p $cache_dir
    # Invalidate cache after 3 weeks by default
    local cache_ttl_mins=${GRADLE_CACHE_TTL_MINUTES:-30240}
    local script_exclude_pattern=${GRADLE_COMPLETION_EXCLUDE_PATTERN:-"/(build|integTest|out)/"}

    # Set bash internal field separator to '\n'
    # This allows us to provide descriptions for options and tasks
    local OLDIFS="$IFS"
    local IFS=$'\n'

    if [[ ${cur} == --* ]]; then
        args="--build-file            - Specifies the build file
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
--no-daemon             - Do not use the Gradle Daemon
--no-rebuild            - Do not rebuild project dependencies
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
--rerun-task            - Specifies that any task optimization is ignored
--settings-file         - Specifies the settings file
--stacktrace            - Print out the stacktrace also for user exceptions
--status                - Print Gradle Daemon status
--stop                  - Stop all Gradle Daemons
--system-prop           - Set a system property
--version               - Prints Gradle version info"
        COMPREPLY=( $(compgen -W "$args" -- "$cur") )
    elif [[ ${cur} == -D* ]]; then
        args="-Dorg.gradle.cache.reserved.mb=   - Reserve Gradle Daemon memory for operations
-Dorg.gradle.daemon.debug=        - Set true to debug Gradle Daemon
-Dorg.gradle.daemon.idletimeout=  - Kill Gradle Daemon after # idle millis
-Dorg.gradle.debug=               - Set true to debug Gradle Client
-Dorg.gradle.jvmargs=             - Set JVM arguments
-Dorg.gradle.java.home=           - Set JDK home dir
-Dorg.gradle.parallel=            - Set true to enable parallel project builds (incubating)
-Dorg.gradle.parallel.intra=      - Set true to enable intra-project parallel builds (incubating)"
        COMPREPLY=( $(compgen -W "$args" -- "$cur") )
    elif [[ ${cur} == -* ]]; then
        args="-?                      - Shows a help message
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
-x                      - Specify a task to be excluded
-D                      - Set a system property
-I                      - Specifies an initialization script
-P                      - Sets a project property of the root project
-S                      - Print out the full (very verbose) stacktrace"
        COMPREPLY=( $(compgen -W "$args" -- "$cur") )
    else
        # Look for default build script in the settings file (settings.gradle by default)
        # Otherwise, default is the file 'build.gradle' in the current directory.
        local gradle_buildfile=build.gradle
        if [[ -f settings.gradle ]]; then
            local build_file_name=$(grep "^rootProject\.buildFileName" settings.gradle | \
                sed -n -e "s/rootProject\.buildFileName = [\'\"]\(.*\)[\'\"]/\1/p")
            gradle_buildfile=${build_file_name:-build.gradle}
        fi

        local gradle_files_checksum
        # If we're in a Gradle project, check if completion cache is up-to-date
        if [[ -f $gradle_buildfile ]]; then
            # Cache name is constructed from the absolute path of the build file.
            local cache_name=$(echo $(pwd)/$gradle_buildfile | sed -e 's/\//_/g')
            if [[ ! $(find $cache_dir/$cache_name -mmin -$cache_ttl_mins 2>/dev/null) ]]; then
                # Cache all Gradle scripts
                local gradle_build_scripts=$(find . -type f -name "*.gradle" -o -name "*.gradle.kts" 2>/dev/null | egrep -v "$script_exclude_pattern")
                printf "%s\n" "${gradle_build_scripts[@]}" > $cache_dir/$cache_name
            fi

            # Cache MD5 sum of all Gradle scripts and modified timestamps
            if builtin command -v md5 > /dev/null; then
                gradle_files_checksum=$(md5 -q -s "$(cat "$cache_dir/$cache_name" | xargs ls -o 2>/dev/null)")
            elif builtin command -v md5sum > /dev/null; then
                gradle_files_checksum=$(cat "$cache_dir/$cache_name" | xargs ls -o 2>/dev/null | md5sum | awk '{print $1}')
            else
                echo "Cannot generate completions as neither md5 nor md5sum exist on \$PATH"
                return 1
            fi

            if [[ ! -f $cache_dir/$cache_name.md5 || $gradle_files_checksum != "$(cat $cache_dir/$cache_name.md5)" || ! -f $cache_dir/$gradle_files_checksum ]]; then
                # Notify user of cache rebuild
                echo -e " (Building completion cache. Please wait)\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\c"

                # Run gradle to retrieve possible tasks and cache.
                # Reuse Gradle Daemon if IDLE but don't start a new one.
                local gradle_tasks_output
                if [[ ! -z "$($gradle_cmd --status 2>/dev/null | grep IDLE)" ]]; then
                    gradle_tasks_output="$($gradle_cmd --daemon -q tasks --all)"
                else
                    gradle_tasks_output="$($gradle_cmd --no-daemon -q tasks --all)"
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
                        if [[ $task_name =~ ^([[:alnum:]:]+):([[:alnum:]]+) ]]; then
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

                # Remove "please wait" message by writing a bunch of spaces then moving back to the left
                echo -e "                                         \b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\c"
            fi
        else
            return 1
        fi

        if [[ -f $cache_dir/$gradle_files_checksum ]]; then
            # Optimize here - this is the slowest part of completion
            local -a cached_tasks
            if [[ -z $cur ]]; then
                cached_tasks=( $(cat $cache_dir/$gradle_files_checksum) )
            else
                cached_tasks=( $(grep "^$cur" $cache_dir/$gradle_files_checksum) )
            fi
            COMPREPLY=( $(compgen -W "${cached_tasks[*]}" -- "$cur") )
        else
            echo "D'oh! There's a bug in the completion script, please submit an issue to eriwen/gradle-completion"
            # previous steps failed, maybe cache dir is not readable/writable
            return 1
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
complete -F _gradle gradlew
complete -F _gradle ./gradlew

if hash gw 2>/dev/null; then
    complete -F _gradle gw
fi