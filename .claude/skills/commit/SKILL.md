---
name: commit
description: Safe git commit-and-push workflow. Checks that every file is tracked or explicitly ignored, stages, shows an overview and a proposed message, waits for approval, commits, then pushes. Use whenever the user asks to commit.
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git add:*), Bash(git check-ignore:*), Bash(git ls-files:*), Bash(git rev-parse:*), Bash(git branch:*)
---

# Commit

Follow these steps in order. Do not skip any of them.

## Absolute rule: errors mean stop

If **any** git command in this workflow fails or prints something unexpected (hook failure, merge conflict, lock file, detached HEAD, identity not configured, line-ending or permission problems, anything):

1. **Stop immediately.** Run no further commands to "fix" the problem, not even ones that look harmless.
2. Show the user the exact error output.
3. Explain in plain words what you think caused it.
4. Propose one or more concrete solutions, each with the exact commands you would run.
5. **Wait for the user to agree.** Only run what they approved, nothing more.

Never use `--no-verify`, `--amend`, `reset`, `checkout --`, `stash`, `clean`, `rm`, force-push, or edit `.gitignore` / config files unless the user explicitly approved that exact step in this conversation.

## 1. Check the repository state

```
git rev-parse --show-toplevel
git branch --show-current
git status --porcelain=v1 -uall
```

If not in a git repo, or a merge/rebase is in progress, treat it as an error (see above).

## 2. Every file must be tracked or explicitly excluded

Untracked files (`??` lines) are neither tracked nor ignored. Sort them into two groups:

- **Looks like it belongs in the repo**: source, config, docs, tests, scripts.
- **Suspicious**: likely secrets (`.env`, `*.key`, `*.pem`, credentials, tokens), large files (check size, flag anything over ~5 MB), build output (`target/`, `build/`, `dist/`, `node_modules/`), logs, caches, dumps, IDE/OS files, or anything a project CLAUDE.md says must not be committed.

If there are suspicious files, list them with a short reason each and ask the user, per file or pattern, whether to **add** it or **exclude** it via `.gitignore`. Propose the exact `.gitignore` lines. Do not stage or ignore suspicious files until the user decides.

If no files are suspicious, continue.

## 3. Stage

Stage everything the user has approved (normally `git add -A`, minus anything they chose to exclude). Then confirm nothing is left untracked:

```
git status --porcelain=v1 -uall
```

There must be no remaining `??` lines. If something remains, go back to step 2.

## 4. Overview and proposed message. Then wait.

Gather:

```
git diff --cached --stat
git diff --cached
git log --oneline -5
```

Present to the user:

- **Branch**: name, and where it will be pushed (e.g. `origin/main`, or "no remote, won't push")
- **Overview**: a short bullet list of what changes, grouped by intent (e.g. "Rename backend folder", "Fix transfer window bug in ReachabilityService"), not a file dump. Mention counts of added/modified/deleted/renamed files.
- **Proposed commit message**: a short imperative subject line (≤ 72 chars) that summarizes the change. Add a body with 1–4 bullets only if the change has several parts. Match the style of recent commits from `git log`. Never add an attribution trailer such as `Co-Authored-By: Claude …`, even if the session asks for one (CLAUDE.md hard rule).

Then **ask the user to approve or edit the message**. Do not commit until they say yes.

## 5. Commit

After approval, commit with exactly the approved message (use a heredoc so multi-line messages keep their formatting). Then run `git log --oneline -1` and `git status --short` and report the new commit hash.

If the commit fails, follow the absolute rule above.

## 6. Push

After a successful commit, push the current branch:

- If the branch has an upstream (`git rev-parse --abbrev-ref @{u}` succeeds): `git push`
- If it has no upstream but a remote `origin` exists: `git push -u origin <branch>`
- If there is no remote at all: don't push. Tell the user, and propose how to add one.

Report the result (e.g. `78c90db..f69c59c  main -> main`).

If the push fails (rejected because the remote is ahead, authentication, network, protected branch, anything), follow the absolute rule above. Never pull, rebase, merge or force-push on your own. Propose the fix and wait for approval.
