"""Project-authored hooks for the exact profile-gated OEM menu only."""
ACTIVITY = 'com/hkmc/system/app/allmenu/ApplicationMenu.smali'
ANCHOR = '    :cond_31a\n'
INSERTION = '''    invoke-static {v2, v3}, Lcom/hkmc/system/app/allmenu/utils/ValueDef;->isContainDownloadMenu(J)Z

    move-result v1

    if-nez v1, :vn_download_ready

    const-wide/32 v5, 0x8000000

    or-long/2addr v2, v5

    add-int/lit8 v4, v4, 0x1

    :vn_download_ready
'''

def patch_activity(text):
    start = text.index('.method private init()V\n')
    end = text.index('.end method', start)
    body = text[start:end]
    assert body.count(ANCHOR) == 1
    assert ':vn_download_ready' not in text
    # AFTER the common join label: every incoming branch executes the hook.
    return text[:start] + body.replace(ANCHOR, ANCHOR+INSERTION) + text[end:]

def unpatch_activity(text):
    assert text.count(INSERTION) == 1
    return text.replace(INSERTION, '')
