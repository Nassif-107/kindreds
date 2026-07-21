# The Book of Kindreds (explorer)

`kindreds-explorer.html` is a standalone page — no external requests, no build step to view.
Every number in it is read from the mod's own data, so it cannot drift by hand; it drifts only
when nobody re-runs the build.

## Rebuilding

Order matters: `build_html.py` reads what the other three write.

```
python build_dossier.py    # race dossiers: lore, play, born-with (from birth_trait/*.json)
python build_branches.py   # what each branch opens (from skill_tree/*.json)
python build_data.py       # every node, effect, cost and deed
python build_html.py       # assembles kindreds-explorer.html
```

On Windows the scripts print non-ASCII, so run them with `PYTHONIOENCODING=utf-8` or the
console encoding will abort the final status line (the files are written UTF-8 regardless).

The three `kindreds-*.json` files are intermediates and are git-ignored.

## Publishing

The page is published as an Artifact. Re-publish to the **same URL** rather than minting a new
one, so old links keep working:
`https://claude.ai/code/artifact/70866b48-a3a7-4128-8bad-3cfe30481688`
