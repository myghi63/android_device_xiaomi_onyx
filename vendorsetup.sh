# Sourced directly by build/envsetup.sh: it scans vendorsetup.sh files under
# device/, vendor/ and product/, and this repo lives in device/. Applies
# patches/frameworks/base/* to frameworks/base whenever envsetup.sh is sourced.
# Patches become commits on the project's HEAD, which the next repo sync
# discards, so re-source envsetup.sh after syncing. Check the output for
# "FAILED to apply".
__onyx_apply_patches() {
    local top root patch rel project
    top="$(gettop)"
    root="$top/device/xiaomi/onyx/patches"
    [ -d "$root" ] || return 0
    while IFS= read -r patch; do
        rel="${patch#"$root"/}"
        project="${rel%/*}"
        # git am commits the patch with the Subject line as the commit subject
        # (wrapped continuation lines joined with a space). Skipping on the
        # subject survives a later patch touching the same files, which a
        # plain reverse-check would falsely report as not applied.
        subject=$(awk '/^Subject: /{sub(/^Subject: (\[PATCH[^]]*\] )?/, ""); s=$0; while ((getline line) > 0) { if (line ~ /^[ \t]/) { sub(/^[ \t]+/, "", line); s = s " " line } else break } print s; exit }' "$patch")
        if [ -n "$subject" ] && git -C "$top/$project" log --format=%s -n 30 | grep -qxF -- "$subject"; then
            continue
        fi
        git -C "$top/$project" apply --reverse --check "$patch" >/dev/null 2>&1 && continue
        if git -C "$top/$project" -c user.name=onyx -c user.email=onyx@localhost \
                am -q --no-gpg-sign "$patch" >/dev/null 2>&1; then
            echo "device/xiaomi/onyx: applied $rel"
        else
            git -C "$top/$project" am --abort >/dev/null 2>&1
            echo "device/xiaomi/onyx: FAILED to apply $rel" >&2
        fi
    done < <(find "$root" -type f -name '*.patch' | sort)
}
__onyx_apply_patches
unset -f __onyx_apply_patches
