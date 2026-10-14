#!/usr/bin/env python3
"""
Prints every JDK Bug System issue matching a JQL query, one tab-separated line each:
key, status, affected versions, fix versions, summary. Pages through the anonymous REST search.

usage: jbs.py '<JQL>'
"""

import json
import sys
import urllib.parse
import urllib.request

SEARCH = "https://bugs.openjdk.org/rest/api/2/search?"
FIELDS = "summary,status,versions,fixVersions"


def names(versions):
    return ",".join(v["name"] for v in versions) or "-"


def main():
    jql = sys.argv[1]
    start = 0
    while True:
        query = urllib.parse.urlencode(
            {"jql": jql, "startAt": start, "maxResults": 100, "fields": FIELDS})
        request = urllib.request.Request(SEARCH + query, headers={"Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=60) as response:
            page = json.load(response)
        for issue in page["issues"]:
            f = issue["fields"]
            print("\t".join([issue["key"], f["status"]["name"],
                             "affects=" + names(f.get("versions", [])),
                             "fix=" + names(f.get("fixVersions", [])), f["summary"]]))
        start += len(page["issues"])
        if not page["issues"] or start >= page["total"]:
            print(f"# {start} of {page['total']}", file=sys.stderr)
            return


if __name__ == "__main__":
    main()
