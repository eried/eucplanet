"""Rule 17: a push to any branch but main must carry a BRANCH.md update.

PreToolUse hook for Bash / PowerShell. Reads the tool call on stdin; when it
is a `git push`, checks the commits about to go out and blocks (exit 2) if
none of them touches BRANCH.md. Add BRANCHMD_OK anywhere in the command (a
shell comment is fine) when the push really has nothing for testers, such as
a docs-only or CI-only change. Anything unexpected lets the push through: a
broken guard must never block work.
"""
import json
import re
import subprocess
import sys


def git(cwd, *args):
    r = subprocess.run(["git", "-C", cwd, *args], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0
    cmd = (payload.get("tool_input") or {}).get("command") or ""
    if not re.search(r"\bgit\b[^\n;&|]*\bpush\b", cmd):
        return 0
    if "BRANCHMD_OK" in cmd or "--delete" in cmd or re.search(r":main\b", cmd):
        return 0
    cwd = payload.get("cwd") or "."
    branch = git(cwd, "rev-parse", "--abbrev-ref", "HEAD")
    if not branch or branch in ("HEAD", "main"):
        return 0
    base = git(cwd, "rev-parse", "--abbrev-ref", "@{u}") or (
        f"origin/{branch}" if git(cwd, "rev-parse", "--verify", "-q", f"origin/{branch}") else None)
    if base is None:
        return 0  # a new branch: nothing on GitHub to compare against yet
    files = git(cwd, "diff", "--name-only", f"{base}..HEAD")
    if files is None or not files:
        return 0
    if "BRANCH.md" in files.splitlines():
        return 0
    sys.stderr.write(
        f"Rule 17: pushing {branch} without a BRANCH.md update. BRANCH.md is the "
        "release text testers read. Add one short line under 'Worked on' and, if a "
        "rider can check it, one under 'Please test'; drop lines that are answered. "
        "If this push has nothing for testers (docs or CI only), add BRANCHMD_OK "
        "to the command and push again.\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
