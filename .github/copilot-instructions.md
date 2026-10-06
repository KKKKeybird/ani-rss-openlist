# Ani-RSS OpenList fork review notes

This fork maintains the native OpenList downloader independently of upstream Ani-RSS.
For upstream synchronization pull requests, focus on whether upstream changes break
the OpenList task lifecycle, download progress, path changes, rename and move handling,
deletion, persisted task state, or the downloader selection UI. Check that the OpenList
API calls still match the current OpenList API and that the fork's release and Docker
workflows keep publishing to this fork's repository and GHCR namespace.

An upstream release merge must preserve the fork's OpenList implementation. If a
behavioral regression is plausible or a test is missing, leave a concrete review
comment and do not approve the pull request.

Collection compatibility must preserve preview filters, episode offsets, subtitle naming,
path-and-size file matching, collision rejection, persistent per-file plans, restart
recovery, and verification after cloud moves. OpenList downloads the complete torrent;
excluded files remain in the task's isolated staging directory.

Passing existing tests is not sufficient to approve upstream synchronization. Inspect
semantic changes and add meaningful regression coverage for changed behavior. Do not
remove or weaken the OpenList tests or restore unconditional automated merging.
