#!/usr/bin/env bash
# v0.12.1 release mechanics — run from the main repo clone AFTER PR #239 is merged.
# Steps 1-3 are local and reversible; step 4 (the tag pushes) is the irreversible publish.
set -euo pipefail
cd /home/vox/projects/voxel-server-support
git fetch --all --prune

# 1. fast-forward the four support branches to their port branches (pure FF — verified ancestors)
for pair in "support/mc26.1-v0.12 port/sodium-26.1" "support/mc1.21.11-v0.12 port/sodium-1.21.11" \
            "support/mc1.21.10 port/sodium-1.21.10" "support/mc1.21.1 port/sodium-1.21.1"; do
  set -- $pair
  git merge-base --is-ancestor "origin/$1" "origin/$2" || { echo "NOT a fast-forward: $1 → $2"; exit 1; }
  git push origin "origin/$2:refs/heads/$1"
done

# 2. annotated tags with --cleanup=verbatim (the ### headers survive), from the committed notes
NOTES=/home/vox/projects/lss-main-deploy/docs/planning
git tag -a v0.12.1           -F $NOTES/release-tag-v0.12.1.txt           --cleanup=verbatim origin/main
git tag -a v0.12.1+mc26.1    -F $NOTES/release-tag-v0.12.1-mc26.1.txt    --cleanup=verbatim origin/port/sodium-26.1
git tag -a v0.12.1+mc1.21.11 -F $NOTES/release-tag-v0.12.1-mc1.21.11.txt --cleanup=verbatim origin/port/sodium-1.21.11
git tag -a v0.12.1+mc1.21.10 -F $NOTES/release-tag-v0.12.1-mc1.21.10.txt --cleanup=verbatim origin/port/sodium-1.21.10
git tag -a v0.12.1+mc1.21.1  -F $NOTES/release-tag-v0.12.1-mc1.21.1.txt  --cleanup=verbatim origin/port/sodium-1.21.1

# 3. verify the annotations (headers present, right commits)
for t in v0.12.1 v0.12.1+mc26.1 v0.12.1+mc1.21.11 v0.12.1+mc1.21.10 v0.12.1+mc1.21.1; do
  echo "== $t → $(git rev-parse --short $t^{commit})"; git for-each-ref --format='%(contents)' refs/tags/$t | grep -c '^### '
done

# 4. PUBLISH — one tag per push (>3 tags in one push fires ZERO workflows), main first; watch each run.
# git push origin v0.12.1           && gh run watch $(gh run list --limit 1 --json databaseId -q '.[0].databaseId') --exit-status
# git push origin v0.12.1+mc26.1    && ...
# git push origin v0.12.1+mc1.21.11 && ...
# git push origin v0.12.1+mc1.21.10 && ...
# git push origin v0.12.1+mc1.21.1  && ...
# then: gh release view v0.12.1 (notes rendered?), Modrinth listing check; VSS publish is separate (MODRINTH_PAT expired).
